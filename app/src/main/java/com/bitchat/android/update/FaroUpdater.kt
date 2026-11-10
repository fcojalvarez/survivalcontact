package com.bitchat.android.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bitchat.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Actualizaciones de Faro por internet (OTA).
 *
 * ÚNICA excepción a la regla de «sin internet» (ver docs/PLAN.md): solo se descarga
 * información de versión y el APK desde las versiones publicadas en el repositorio
 * público del proyecto (fcojalvarez/survivalcontact). Nunca se
 * envían mensajes, ubicaciones ni datos del usuario.
 *
 * Para no gastar batería, la comprobación la hace WorkManager como mucho una vez al día,
 * solo con Wi‑Fi (red no medida) y con batería suficiente.
 *
 * Flujo: descarga version.json → si hay un versionCode mayor, descarga el APK → comprueba
 * SHA-256 y que esté firmado con la misma clave que la app instalada → notificación para
 * instalar. Android exige además esa misma firma para actualizar, así que un APK ajeno
 * nunca puede sustituir a Faro.
 */
object FaroUpdater {
    private const val TAG = "FaroUpdater"

    /** Fichero con la última versión, publicado en cada compilación (ver faro-apk.yml). */
    const val VERSION_URL =
        "https://github.com/fcojalvarez/survivalcontact/releases/latest/download/version.json"

    private const val WORK_NAME = "faro_update_check"
    private const val CHANNEL_ID = "faro_updates"
    private const val NOTIFICATION_ID = 7301
    private const val UPDATES_DIR = "updates"
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    data class VersionInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
    )

    /** Busca una versión nueva ahora mismo (desde Ajustes), con cualquier conexión. */
    fun checkNow(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<FaroUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME + "_ahora", androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    /** Programa la comprobación diaria. Llamar al arrancar la app; no duplica el trabajo. */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<FaroUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Lee version.json. Si trae APK por tipo de procesador ("apks"), elige el primero que
     * encaje con [abis] (así cada móvil descarga solo lo suyo); si no, usa el universal.
     */
    internal fun parseVersion(json: String, abis: List<String> = Build.SUPPORTED_ABIS.toList()): VersionInfo? = runCatching {
        val o = JSONObject(json)
        val perAbi = o.optJSONObject("apks")
        val chosen = abis.firstNotNullOfOrNull { abi -> perAbi?.optJSONObject(abi) }
        val info = VersionInfo(
            versionCode = o.getLong("versionCode"),
            versionName = o.optString("versionName", ""),
            apkUrl = chosen?.getString("url") ?: o.getString("apkUrl"),
            sha256 = (chosen?.getString("sha256") ?: o.getString("sha256")).lowercase(),
        )
        if (!info.apkUrl.startsWith("https://")) return null
        if (!Regex("^[0-9a-f]{64}$").matches(info.sha256)) return null
        info
    }.getOrNull()

    internal suspend fun fetchVersion(): VersionInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Faro-Android")
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseVersion(body)
        } finally {
            conn.disconnect()
        }
    }

    internal fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    /** Descarga el APK a la caché comprobando tamaño y SHA-256. Devuelve null si algo falla. */
    internal suspend fun downloadApk(context: Context, info: VersionInfo): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        // Solo guardamos una actualización: borramos las anteriores.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "faro-${info.versionCode}.apk")
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Faro-Android")
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) return@withContext null.also { target.delete() }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != info.sha256) {
                Log.w(TAG, "SHA-256 no coincide; se descarta la descarga")
                target.delete()
                return@withContext null
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al descargar la actualización: ${e.message}")
            target.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Comprueba que el APK sea Faro, más nuevo, y firmado con la misma clave que la app instalada. */
    internal fun isTrustedUpdate(context: Context, apk: File, expectedVersionCode: Long): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        @Suppress("DEPRECATION")
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode != expectedVersionCode || archiveCode <= installedVersionCode(context)) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        return signatureSet(archive) == signatureSet(installed) && signatureSet(installed).isNotEmpty()
    }

    private fun signatureSet(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { si ->
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Versión nueva ya descargada y comprobada, para ofrecerla dentro de la app. */
    data class ReadyUpdate(val versionName: String, val versionCode: Long, val apk: File)

    /** Busca una actualización lista para instalar (la deja el trabajo diario o «Buscar ahora»). */
    fun readyUpdate(context: Context): ReadyUpdate? {
        val p = context.getSharedPreferences(PREFS_READY, Context.MODE_PRIVATE)
        val path = p.getString("apk", null) ?: return null
        val code = p.getLong("code", 0L)
        val apk = File(path)
        if (!apk.exists() || code <= installedVersionCode(context)) {
            p.edit().clear().apply()
            return null
        }
        return ReadyUpdate(p.getString("name", "") ?: "", code, apk)
    }

    /** Abre el instalador del sistema con la versión descargada. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private const val PREFS_READY = "faro_update_ready"
    private const val SHOW_NOTIFICATION = false

    internal fun notifyUpdateReady(context: Context, apk: File, info: VersionInfo) {
        // Se guarda para que la app lo ofrezca con una ventana al abrirse.
        context.getSharedPreferences(PREFS_READY, Context.MODE_PRIVATE).edit()
            .putString("apk", apk.absolutePath).putLong("code", info.versionCode).putString("name", info.versionName).apply()
        // Faro: el aviso es solo la ventana dentro de la app (FaroMainScreen), sin notificación.
        if (SHOW_NOTIFICATION.not()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.faro_update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.faro_update_channel_desc) }
            )
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            install,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.faro_update_title))
            .setContentText(context.getString(R.string.faro_update_text, info.versionName))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}

/** Trabajo diario de WorkManager que busca, descarga y avisa de una versión nueva. */
class FaroUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val info = try {
            FaroUpdater.fetchVersion()
        } catch (e: Exception) {
            null
        } ?: return Result.success() // Sin datos de versión: se reintenta mañana.

        if (info.versionCode <= FaroUpdater.installedVersionCode(applicationContext)) {
            return Result.success()
        }
        val apk = FaroUpdater.downloadApk(applicationContext, info) ?: return Result.retry()
        if (!FaroUpdater.isTrustedUpdate(applicationContext, apk, info.versionCode)) {
            apk.delete()
            return Result.success()
        }
        FaroUpdater.notifyUpdateReady(applicationContext, apk, info)
        return Result.success()
    }
}
