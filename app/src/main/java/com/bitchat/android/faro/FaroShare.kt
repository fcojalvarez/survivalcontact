package com.bitchat.android.faro

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bitchat.android.hotspot.HotspotActivity
import java.io.File

/**
 * Compartir Faro sin internet: se comparte la propia app instalada en el móvil.
 */
object FaroShare {
    private fun copyInstalledApk(context: Context): File? = runCatching {
        val source = File(context.applicationInfo.sourceDir)
        val dir = File(context.cacheDir, "apk_share").apply { mkdirs() }
        val target = File(dir, "Faro.apk")
        if (!target.exists() || target.length() != source.length()) source.copyTo(target, overwrite = true)
        target
    }.getOrNull()

    /** Abre el menú de compartir de Android (Quick Share, Bluetooth, WhatsApp…). */
    fun shareWithApps(context: Context): Boolean {
        val apk = copyInstalledApk(context) ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartir Faro").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }

    /** Crea una red Wi‑Fi y muestra un QR para descargar Faro desde este móvil. */
    fun shareByWifi(context: Context): Boolean {
        val apk = copyInstalledApk(context) ?: return false
        context.startActivity(Intent(context, HotspotActivity::class.java).putExtra(HotspotActivity.EXTRA_APK_PATH, apk.absolutePath))
        return true
    }
}
