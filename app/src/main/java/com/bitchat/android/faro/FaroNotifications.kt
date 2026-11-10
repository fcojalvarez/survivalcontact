package com.bitchat.android.faro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R

/** Avisos de los grupos privados: mensajes nuevos y peticiones de ayuda. */
object FaroNotifications {
    private const val CHANNEL_MESSAGES = "faro_grupos"
    private const val CHANNEL_HELP = "faro_ayuda"

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Mensajes de grupos", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Mensajes nuevos en tus grupos privados" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HELP, "Peticiones de ayuda", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Cuando alguien de tus grupos pide ayuda" }
        )
    }

    private fun openApp(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            requestCode,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun message(ctx: Context, groupId: String, groupName: String, author: String, text: String) {
        if (!canNotify(ctx) || !FaroSettings.notifyMessages) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val id = ("msg" + groupId).hashCode()
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(groupName)
                .setContentText("$author: $text")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$author: $text"))
                .setContentIntent(openApp(ctx, id))
                .setAutoCancel(true)
                .build()
        )
    }

    fun joinRequest(ctx: Context, groupId: String, groupName: String, name: String) {
        if (!canNotify(ctx) || !FaroSettings.notifyRequests) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val id = ("join" + groupId + name).hashCode()
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(groupName)
                .setContentText("$name quiere entrar. Abre Faro para dejarle entrar o no.")
                .setContentIntent(openApp(ctx, id))
                .setAutoCancel(true)
                .build()
        )
    }

    /** Avisos de la familia: llegadas, salidas, «estoy bien», viajes. */
    fun family(ctx: Context, groupId: String, title: String, text: String) {
        if (!canNotify(ctx) || !FaroSettings.notifyMessages) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val id = ("fam" + groupId + title).hashCode()
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(ctx, id))
                .setAutoCancel(true)
                .build()
        )
    }

    /** «¿Estás bien?»: se contesta desde la propia notificación, sin abrir la app. */
    fun ask(ctx: Context, groupId: String, askerId: String, askerName: String) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val id = ("ask" + askerId).hashCode()
        fun action(ok: Boolean): PendingIntent = PendingIntent.getBroadcast(
            ctx, id + if (ok) 1 else 2,
            Intent(ctx, FaroCheckReceiver::class.java)
                .putExtra(FaroCheckReceiver.EXTRA_ASKER, askerId)
                .putExtra(FaroCheckReceiver.EXTRA_OK, ok)
                .putExtra(FaroCheckReceiver.EXTRA_NOTIF, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL_HELP)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$askerName te pregunta si estás bien")
                .setContentText("Contesta con un toque.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(ctx, id))
                .addAction(0, "Estoy bien", action(true))
                .addAction(0, "Necesito ayuda", action(false))
                .setAutoCancel(true)
                .build()
        )
    }

    fun seen(ctx: Context, name: String) {
        if (!canNotify(ctx) || !FaroSettings.notifySeen) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val id = ("seen" + name).hashCode()
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$name ha visto dónde estás")
                .setContentText("Lo ha mirado en el mapa de Faro.")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setContentIntent(openApp(ctx, id))
                .setAutoCancel(true)
                .build()
        )
    }

    fun help(ctx: Context, groupId: String, groupName: String, author: String, distance: String?) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val id = ("sos" + groupId + author).hashCode()
        val text = if (distance != null) "$author necesita ayuda · a $distance" else "$author necesita ayuda"
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL_HELP)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$groupName · Petición de ayuda")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(openApp(ctx, id))
                .setAutoCancel(true)
                .build()
        )
    }
}
