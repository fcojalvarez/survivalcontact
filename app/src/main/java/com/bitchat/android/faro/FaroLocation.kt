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
    }

    /** Despierta el ciclo (por ejemplo, al dar permiso en un grupo). */
    fun requestSoon() {
        wake.trySend(Unit)
    }

    /** SOS: envía al momento posición y «necesito ayuda» a todos los grupos. */
    fun sendSos(active: Boolean) {
        _sos.value = active
        lastSentAt = 0L
        requestSoon()
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
        val interval = when {
            sos -> 2 * 60_000L
            battery > 50 -> 5 * 60_000L
            battery >= 20 -> 15 * 60_000L
            else -> return 30 * 60_000L // Batería crítica: solo SOS.
        }

        val loc = currentLocation(allowFresh = true) ?: return interval
        _myLocation.value = loc
        val now = System.currentTimeMillis()
        val moved = lastSent?.distanceTo(loc) ?: Float.MAX_VALUE
        if (!sos && moved < MOVE_THRESHOLD_M && now - lastSentAt < MAX_SILENCE_MS) return interval

        val pos = FaroProtocol.Position(
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toInt() else 0,
            batteryPct = battery,
            status = if (sos) FaroProtocol.Position.Status.NEED_HELP else FaroProtocol.Position.Status.OK,
        )
        targets.forEach { FaroGroups.sendPosition(it.id, pos) }
        lastSent = loc
        lastSentAt = now
        return interval
    }

    private suspend fun currentLocation(allowFresh: Boolean): Location? {
        val p = provider ?: return null
        val ctx = appContext ?: return null
        if (!hasPermission(ctx)) return null
        val last = suspendCancellableCoroutine<Location?> { c -> p.getLastKnownLocation { if (c.isActive) c.resume(it) } }
        val age = last?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        if (last != null && (age < FRESH_ENOUGH_MS || !allowFresh)) return last
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
