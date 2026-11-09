package com.bitchat.android.faro

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos

/**
 * Mapa sin conexión de la zona donde está el usuario, en cualquier parte del mundo.
 *
 * Cuando hay internet, descarga (con el sistema de zonas de MapLibre) dos piezas pequeñas
 * alrededor de su posición: la ciudad con detalle de calles y la comarca con carreteras y
 * pueblos. Sin conexión, el mapa usa lo descargado.
 *
 * - Si no hay mapa todavía, descarga con cualquier conexión (son pocos MB).
 * - Si el usuario se aleja más de 25 km, sustituye la zona, pero solo con Wi‑Fi.
 */
object FaroOfflineMap {
    private const val TAG = "FaroOfflineMap"
    const val STYLE_URL = "asset://mapa/estilo-oscuro.json"
    private const val REFRESH_DISTANCE_M = 25_000f

    private data class Piece(val name: String, val radiusKm: Double, val minZoom: Double, val maxZoom: Double)

    private val PIECES = listOf(
        Piece("ciudad", 10.0, 10.0, 14.0),
        Piece("comarca", 60.0, 5.0, 11.0),
    )

    sealed class State {
        object None : State()
        data class Downloading(val percent: Int) : State()
        data class Ready(val megabytes: Double) : State()
        object Error : State()
    }

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private var busy = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        main.launch {
            runCatching { MapLibre.getInstance(context.applicationContext) }
            refreshState()
            FaroLocation.myLocation.filterNotNull().collect { ensureFor(it) }
        }
        // Al recuperar conexión, vuelve a intentarlo con la última posición conocida.
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.launch { FaroLocation.myLocation.value?.let { ensureFor(it) } ?: FaroLocation.refreshForDisplay() }
                }
            })
        }
        FaroLocation.refreshForDisplay()
    }

    private fun online(): Pair<Boolean, Boolean> {
        val ctx = appContext ?: return false to false
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false to false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false to false) ?: return false to false
        val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return ok to unmetered
    }

    private fun manager(): OfflineManager? = appContext?.let { runCatching { OfflineManager.getInstance(it) }.getOrNull() }

    private var force = false

    /** Vuelve a guardar la zona donde estoy ahora (desde Ajustes). */
    fun updateNow() {
        main.launch {
            force = true
            FaroLocation.myLocation.value?.let { ensureFor(it) } ?: FaroLocation.refreshForDisplay()
        }
    }

    /** Borra el mapa guardado. Se volverá a guardar solo la próxima vez que haya conexión. */
    fun deleteAll() {
        val mgr = manager() ?: return
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().filter { meta(it)?.optString("faro").isNullOrEmpty().not() }.forEach {
                    it.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() = Unit
                        override fun onError(error: String) = Unit
                    })
                }
                _state.value = State.None
            }
            override fun onError(error: String) = Unit
        })
    }

    private fun ensureFor(location: Location) {
        if (busy) return
        val (isOnline, unmeteredNet) = online()
        val unmetered = unmeteredNet || !FaroSettings.mapWifiOnly || force
        if (!isOnline) return
        val mgr = manager() ?: return
        busy = true
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val ours = offlineRegions.orEmpty().filter { meta(it)?.optString("faro").isNullOrEmpty().not() }
                val center = ours.firstNotNullOfOrNull { r -> meta(r)?.let { Location("faro").apply { latitude = it.optDouble("lat"); longitude = it.optDouble("lon") } } }
                val nearEnough = center != null && center.distanceTo(location) < REFRESH_DISTANCE_M
                val complete = ours.size == PIECES.size
                when {
                    nearEnough && complete && !force -> { busy = false; resumeIncomplete(ours) }
                    ours.isNotEmpty() && !unmetered -> { busy = false } // Cambiar de zona: solo con Wi‑Fi.
                    else -> {
                        force = false
                        ours.forEach { it.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() = Unit
                            override fun onError(error: String) = Unit
                        }) }
                        download(mgr, location.latitude, location.longitude)
                    }
                }
            }

            override fun onError(error: String) {
                busy = false
                Log.w(TAG, "No se pudieron listar las zonas: $error")
            }
        })
    }

    private fun meta(region: OfflineRegion): JSONObject? =
        runCatching { JSONObject(String(region.metadata, Charsets.UTF_8)) }.getOrNull()

    private fun resumeIncomplete(regions: List<OfflineRegion>) {
        regions.forEach { r -> observe(r) }
    }

    private fun download(mgr: OfflineManager, lat: Double, lon: Double) {
        val ctx = appContext ?: return
        _state.value = State.Downloading(0)
        var pending = PIECES.size
        PIECES.forEach { piece ->
            val definition = OfflineTilePyramidRegionDefinition(
                STYLE_URL,
                boundsAround(lat, lon, piece.radiusKm),
                piece.minZoom,
                piece.maxZoom,
                ctx.resources.displayMetrics.density.coerceAtMost(2f),
            )
            val metadata = JSONObject().put("faro", piece.name).put("lat", lat).put("lon", lon).toString().toByteArray()
            mgr.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    observe(offlineRegion)
                    if (--pending == 0) busy = false
                }

                override fun onError(error: String) {
                    Log.w(TAG, "No se pudo crear la zona ${piece.name}: $error")
                    _state.value = State.Error
                    if (--pending == 0) busy = false
                }
            })
        }
    }

    private val progress = mutableMapOf<Long, OfflineRegionStatus>()

    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                progress[region.id] = status
                publish()
                if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }

            override fun onError(error: OfflineRegionError) {
                Log.w(TAG, "Error descargando el mapa: ${error.message}")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                Log.w(TAG, "Límite de teselas superado: $limit")
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun publish() {
        val all = progress.values
        if (all.isEmpty()) return
        val required = all.sumOf { it.requiredResourceCount }.coerceAtLeast(1)
        val done = all.sumOf { it.completedResourceCount }
        val bytes = all.sumOf { it.completedResourceSize }
        _state.value = if (all.size >= PIECES.size && all.all { it.isComplete }) {
            State.Ready(bytes / 1_000_000.0)
        } else {
            State.Downloading((done * 100 / required).toInt().coerceIn(0, 99))
        }
    }

    private fun refreshState() {
        val mgr = manager() ?: return
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().filter { meta(it) != null }.forEach { r ->
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            status ?: return
                            progress[r.id] = status
                            publish()
                        }

                        override fun onError(error: String?) = Unit
                    })
                }
            }

            override fun onError(error: String) = Unit
        })
    }

    private fun boundsAround(lat: Double, lon: Double, radiusKm: Double): LatLngBounds {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return LatLngBounds.from(
            (lat + dLat).coerceAtMost(85.0),
            (lon + dLon).coerceAtMost(180.0),
            (lat - dLat).coerceAtLeast(-85.0),
            (lon - dLon).coerceAtLeast(-180.0),
        )
    }
}
