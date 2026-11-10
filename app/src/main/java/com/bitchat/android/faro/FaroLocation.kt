package com.bitchat.android.faro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.bitchat.android.geohash.FusedLocationProvider
import com.bitchat.android.geohash.LocationProvider
import com.bitchat.android.geohash.SystemLocationProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Comparte la posición con los grupos que lo tienen permitido, gastando lo mínimo:
 *
 * - Solo trabaja si hay algún grupo con la ubicación activada (consentimiento) o un SOS.
 * - Usa primero la última posición conocida (no enciende nada) y solo pide una nueva si es
 *   antigua. Nunca hay seguimiento continuo del GPS.
 * - Frecuencia según la batería: > 50 % cada 5 min; 20–50 % cada 15 min; < 20 % solo SOS.
 * - Si no te has movido más de 100 m, no reenvía hasta pasados 30 min.
 */
object FaroLocation {
    private const val MOVE_THRESHOLD_M = 100f
    private const val MAX_SILENCE_MS = 30 * 60_000L
    private const val FRESH_ENOUGH_MS = 2 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var appContext: Context? = null
    private var provider: LocationProvider? = null

    private val _myLocation = MutableStateFlow<Location?>(null)
    /** Mi última posición conocida (para el mapa y la brújula). */
    val myLocation: StateFlow<Location?> = _myLocation.asStateFlow()

    private val _sos = MutableStateFlow(false)
    val sosActive: StateFlow<Boolean> = _sos.asStateFlow()

    /** Quien me está siguiendo en directo y hasta cuándo. */
    data class Follower(val id: String, val name: String, val untilMs: Long)

    private val _followers = MutableStateFlow<List<Follower>>(emptyList())
    val followers: StateFlow<List<Follower>> = _followers.asStateFlow()

    /**
     * Alguien me pide seguirme en directo: envío mi ubicación cada 30 s durante como mucho
     * 10 min. Gasta algo más de batería solo ese rato, así que no se acepta por debajo del 20 %.
     */
    fun onFollowRequest(id: String, name: String, minutes: Int) {
        val ctx = appContext ?: return
        if (minutes <= 0) {
            _followers.update { list -> list.filterNot { it.id == id } }
            return
        }
        if (batteryPercent(ctx) < 20) return
        val until = System.currentTimeMillis() + minutes.coerceAtMost(10) * 60_000L
        _followers.update { list -> list.filterNot { it.id == id } + Follower(id, name, until) }
        lastSentAt = 0L
        requestSoon()
    }

    // ---------- «Voy para allá» ----------

    private val _trip = MutableStateFlow<FaroProtocol.Trip?>(null)
    /** Mi viaje en curso, si hay. */
    val trip: StateFlow<FaroProtocol.Trip?> = _trip.asStateFlow()
    private const val ARRIVED_M = 150f

    fun startTrip(destName: String, lat: Double, lon: Double, mode: FaroProtocol.TravelMode) {
        val t = FaroProtocol.Trip(true, destName, lat, lon, etaFrom(_myLocation.value, lat, lon, mode), mode)
        _trip.value = t
        FaroGroups.sendTrip(t)
        lastSentAt = 0L
        requestSoon()
    }

    /** Fin del viaje: al llegar (solo o con «Ya he llegado») o al pararlo. */
    fun stopTrip() {
        val t = _trip.value ?: return
        _trip.value = null
        FaroGroups.sendTrip(t.copy(active = false))
    }

    private fun etaFrom(from: Location?, lat: Double, lon: Double, mode: FaroProtocol.TravelMode): Long {
        val d = from?.let { val to = Location("faro").apply { latitude = lat; longitude = lon }; it.distanceTo(to) } ?: 0f
        // Las calles no van en línea recta: se cuenta un 30 % más de camino.
        val hours = d * 1.3 / 1000.0 / mode.kmh
        return System.currentTimeMillis() + (hours * 3_600_000L).toLong()
    }

    // ---------- Llegadas y salidas de los lugares ----------

    /** Lugares (grupo:lugar) en los que estoy. Null hasta la primera posición, para no avisar al arrancar. */
    private var inside: Set<String>? = null

    private fun checkPlaces(loc: Location) {
        val now = FaroGroups.groups.value.filter { it.shareLocation }.flatMap { g ->
            FaroGroups.places.value[g.id].orEmpty().filter { p ->
                val to = Location("faro").apply { latitude = p.lat; longitude = p.lon }
                loc.distanceTo(to) <= p.radiusM + (if (loc.hasAccuracy()) loc.accuracy.coerceAtMost(100f) else 0f)
            }.map { g.id + ":" + it.id }
        }.toSet()
        val before = inside
        inside = now
        if (before == null) return
        (now - before).forEach { k -> FaroGroups.sendArrival(k.substringBefore(":"), k.substringAfter(":"), enter = true) }
        (before - now).forEach { k -> FaroGroups.sendArrival(k.substringBefore(":"), k.substringAfter(":"), enter = false) }
    }

    // ---------- Movimiento ----------

    @Volatile private var moving = false
    private var lastFix: Location? = null
    @Volatile private var motionArmed = false

    /**
     * Sensor de «movimiento significativo»: lo gestiona el propio chip del móvil y casi no gasta.
     * Avisa una vez cuando empiezas a moverte, y entonces se pide una posición al momento.
     */
    private fun armMotionSensor() {
        if (motionArmed) return
        val ctx = appContext ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        motionArmed = sm.requestTriggerSensor(object : android.hardware.TriggerEventListener() {
            override fun onTrigger(event: android.hardware.TriggerEvent?) {
                motionArmed = false
                moving = true
                requestSoon()
            }
        }, sensor)
    }

    // ---------- Sin batería ----------

    @Volatile private var dyingSent = false

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (charging || pct > 10) { dyingSent = false; return }
        if (pct > 5 || dyingSent) return
        dyingSent = true
        scope.launch {
            val ctx = appContext ?: return@launch
            if (!hasPermission(ctx)) return@launch
            // Vale una posición de hace un rato: no merece la pena gastar GPS con un 5 %.
            val loc = currentLocation(allowFresh = true, freshMs = 10 * 60_000L) ?: return@launch
            val pos = FaroProtocol.Position(
                loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toInt() else 0, pct,
                if (_sos.value) FaroProtocol.Position.Status.NEED_HELP else FaroProtocol.Position.Status.BATTERY_DYING,
            )
            val targets = if (_sos.value) FaroGroups.groups.value else FaroGroups.groups.value.filter { it.shareLocation }
            targets.forEach { FaroGroups.sendPosition(it.id, pos) }
        }
    }

    /** La persona seguida corta el seguimiento desde su aviso. */
    fun stopFollowers() {
        _followers.value = emptyList()
    }

    private var lastSent: Location? = null
    private var lastSentAt = 0L

    @Synchronized
    fun init(context: Context) {
        if (job != null) return
        appContext = context.applicationContext
        provider = runCatching {
            val ok = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
            if (ok) FusedLocationProvider(context.applicationContext) else SystemLocationProvider(context.applicationContext)
        }.getOrNull()
        job = scope.launch { loop() }
        // Al bajar del 5 % se envía la última ubicación con el aviso «sin batería».
        runCatching {
            context.applicationContext.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { onBattery(i ?: return) }
            }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    /** Despierta el ciclo (por ejemplo, al dar permiso en un grupo). */
    fun requestSoon() {
        wake.trySend(Unit)
    }

    /** SOS: envía al momento posición y «necesito ayuda» a todos los grupos. */
    fun sendSos(active: Boolean) {
        _sos.value = active
        lastSentAt = 0L
        refreshEmergency()
        requestSoon()
    }

    /**
     * Modo emergencia de la malla: mientras pido ayuda, o alguien de mis grupos la ha pedido en
     * las últimas 3 h y no ha dicho que está bien. Se apaga solo.
     */
    fun refreshEmergency() {
        com.bitchat.android.mesh.EmergencyMode.set(_sos.value || FaroGroups.someoneNeedsHelp(3 * 3_600_000L))
    }

    /** Pide una posición para mostrarla (por ejemplo, al abrir el mapa). No la envía. */
    fun refreshForDisplay() {
        scope.launch { currentLocation(allowFresh = true)?.let { _myLocation.value = it } }
    }

    private suspend fun loop() {
        while (true) {
            val interval = runCatching { tick() }.getOrDefault(15 * 60_000L)
            withTimeoutOrNull(interval) { wake.receive() }
        }
    }

    /** Devuelve cuánto esperar hasta la siguiente vuelta. */
    private suspend fun tick(): Long {
        val ctx = appContext ?: return 15 * 60_000L
        val sos = _sos.value
        val sharing = FaroGroups.groups.value.filter { it.shareLocation }
        val targets = if (sos) FaroGroups.groups.value else sharing
        if (targets.isEmpty() || !hasPermission(ctx)) return 60 * 60_000L

        val battery = batteryPercent(ctx)
        refreshEmergency()
        val now0 = System.currentTimeMillis()
        _followers.update { list -> list.filter { it.untilMs > now0 } }
        val following = _followers.value.isNotEmpty() && battery >= 20
        val traveling = _trip.value != null
        val interval = when {
            following -> 30_000L
            // «Voy para allá»: cada 2 min mientras dura (con poca batería, cada 5).
            traveling && !sos -> if (battery > 20) 2 * 60_000L else 5 * 60_000L
            // Pidiendo ayuda: la ubicación sale más espaciada según baja la batería, para que el
            // móvil aguante muchas horas (con el 50 % sobra para más de 7 h).
            sos && battery > 50 -> 2 * 60_000L
            sos && battery > 20 -> 5 * 60_000L
            sos && battery > 10 -> 10 * 60_000L
            sos -> 15 * 60_000L
            // Máximo ahorro: solo se envía la ubicación si pido ayuda.
            FaroSettings.battery == FaroSettings.Battery.MAX -> return 30 * 60_000L
            FaroSettings.battery == FaroSettings.Battery.SAVER && battery >= 20 -> 15 * 60_000L
            // Según el movimiento: más a menudo si te mueves, casi nunca si estás quieto. Si
            // estando quieto echas a andar, el sensor de movimiento despierta el ciclo antes.
            battery > 50 && moving -> 2 * 60_000L
            battery > 50 -> 15 * 60_000L
            battery >= 20 && moving -> 5 * 60_000L
            battery >= 20 -> 30 * 60_000L
            else -> return 30 * 60_000L // Batería crítica: solo SOS.
        }

        val loc = currentLocation(allowFresh = true, freshMs = if (following) 20_000L else FRESH_ENOUGH_MS) ?: return interval
        _myLocation.value = loc
        checkPlaces(loc)
        moving = (lastFix?.distanceTo(loc) ?: 0f) >= MOVE_THRESHOLD_M
        lastFix = loc
        if (!moving) armMotionSensor()
        _trip.value?.let { t ->
            val to = Location("faro").apply { latitude = t.lat; longitude = t.lon }
            if (loc.distanceTo(to) <= ARRIVED_M) stopTrip()
            else _trip.value = t.copy(etaMs = etaFrom(loc, t.lat, t.lon, t.mode)).also { FaroGroups.sendTrip(it) }
        }
        val now = System.currentTimeMillis()
        val moved = lastSent?.distanceTo(loc) ?: Float.MAX_VALUE
        if (!sos && !following && _trip.value == null && moved < MOVE_THRESHOLD_M && now - lastSentAt < MAX_SILENCE_MS) return interval

        val pos = FaroProtocol.Position(
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toInt() else 0,
            batteryPct = battery,
            status = when {
                sos -> FaroProtocol.Position.Status.NEED_HELP
                _trip.value != null -> FaroProtocol.Position.Status.ON_THE_WAY
                else -> FaroProtocol.Position.Status.OK
            },
        )
        targets.forEach { FaroGroups.sendPosition(it.id, pos) }
        lastSent = loc
        lastSentAt = now
        return interval
    }

    private suspend fun currentLocation(allowFresh: Boolean, freshMs: Long = FRESH_ENOUGH_MS): Location? {
        val p = provider ?: return null
        val ctx = appContext ?: return null
        if (!hasPermission(ctx)) return null
        val last = suspendCancellableCoroutine<Location?> { c -> p.getLastKnownLocation { if (c.isActive) c.resume(it) } }
        val age = last?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        if (last != null && (age < freshMs || !allowFresh)) return last
        val fresh = withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine<Location?> { c -> p.requestFreshLocation { if (c.isActive) c.resume(it) } }
        }
        return fresh ?: last
    }

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun batteryPercent(ctx: Context): Int {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else 100
    }
}
