package com.bitchat.android.faro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bitchat.android.R
import com.bitchat.android.mesh.EmergencyMode
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServiceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Cuándo funciona la red cercana (Bluetooth) con la app cerrada.
 *
 * En modo automático, con internet no hace falta: los mensajes y ubicaciones de los grupos van
 * por internet y el móvil no gasta batería ni muestra la notificación fija. Si se pierde internet
 * durante 1 minuto, se enciende la red cercana y durante 3 minutos busca más a menudo; al volver
 * internet se apaga. Pedir ayuda (o que alguien la pida) la enciende siempre.
 * Con la app abierta la red cercana funciona siempre.
 */
object FaroMeshPolicy : DefaultLifecycleObserver {
    private const val TAG = "FaroMeshPolicy"
    private const val OFFLINE_GRACE_MS = 60_000L
    private const val BOOST_MS = 3 * 60_000L
    private const val CHANNEL = "faro_red"
    private const val NOTIF_ID = 20201

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    @Volatile private var offlineSince: Long? = null
    @Volatile private var foreground = false
    private var graceJob: Job? = null

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        offlineSince = if (isOnline(ctx)) null else System.currentTimeMillis() - OFFLINE_GRACE_MS
        runCatching {
            ctx.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    scope.launch { if (ok) onOnline() else onOffline() }
                }
                override fun onLost(network: Network) { scope.launch { if (!isOnline(ctx)) onOffline() } }
            })
        }
        Handler(Looper.getMainLooper()).post {
            runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
        }
        scope.launch { EmergencyMode.active.collect { evaluate() } }
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "faro_red_cercana", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FaroMeshCheckWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }

    /** ¿Debe funcionar la red cercana con la app cerrada ahora mismo? */
    fun backgroundWanted(): Boolean {
        val emergency = EmergencyMode.active.value
        return when (FaroSettings.meshMode) {
            FaroSettings.MeshMode.ALWAYS -> true
            FaroSettings.MeshMode.APP_OPEN -> emergency
            FaroSettings.MeshMode.AUTO -> emergency ||
                (offlineSince?.let { System.currentTimeMillis() - it >= OFFLINE_GRACE_MS } ?: false)
        }
    }

    private fun onOnline() {
        graceJob?.cancel()
        offlineSince = null
        cancelOfferNotification()
        evaluate()
    }

    private fun onOffline() {
        if (offlineSince != null) return
        offlineSince = System.currentTimeMillis()
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(OFFLINE_GRACE_MS)
            if (offlineSince != null) {
                Log.i(TAG, "Sin internet desde hace 1 min: se enciende la red cercana")
                EmergencyMode.boost(BOOST_MS)
                evaluate()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        resumeMesh()
        evaluate()
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        evaluate()
    }

    /** Ajustes cambiados u otros eventos: aplica la política. */
    fun evaluate() {
        val ctx = appContext ?: return
        if (backgroundWanted()) {
            resumeMesh()
            val started = runCatching { MeshForegroundService.start(ctx) }.isSuccess
            if (!started && !foreground) offerNotification(ctx)
        } else {
            runCatching { MeshForegroundService.pause(ctx) }
            if (!foreground) pauseMesh()
        }
    }

    private fun resumeMesh() {
        runCatching { MeshServiceHolder.meshService?.setBleTransportEnabled(true) }
    }

    private fun pauseMesh() {
        runCatching { MeshServiceHolder.meshService?.setBleTransportEnabled(false) }
    }

    /** Comprobación periódica cuando la app está cerrada del todo (sin proceso vivo). */
    internal fun periodicCheck(ctx: Context) {
        init(ctx)
        if (!isOnline(ctx) && offlineSince == null) offlineSince = System.currentTimeMillis() - OFFLINE_GRACE_MS
        if (backgroundWanted()) {
            EmergencyMode.boost(BOOST_MS)
            val ok = runCatching { MeshForegroundService.start(ctx) }.isSuccess
            if (!ok) offerNotification(ctx)
        }
    }

    /**
     * Plan B: si Android no deja encender la red desde segundo plano, se avisa para encenderla
     * con un toque (un toque del usuario sí lo permite).
     */
    private fun offerNotification(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Red cercana", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(ctx, com.bitchat.android.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(
                NOTIF_ID,
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Sin internet")
                    .setContentText("Toca para activar la red cercana y seguir en contacto con tu familia.")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun cancelOfferNotification() {
        appContext?.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    private fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** Cada 15 min (lo mínimo que permite Android) comprueba si hay que encender la red cercana. */
class FaroMeshCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        kotlinx.coroutines.withContext(Dispatchers.Main) { FaroMeshPolicy.periodicCheck(applicationContext) }
        return Result.success()
    }
}
