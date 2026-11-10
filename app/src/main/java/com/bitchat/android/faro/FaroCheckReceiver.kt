package com.bitchat.android.faro

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Respuesta a «¿Estás bien?» pulsada en la notificación. */
class FaroCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val asker = intent.getStringExtra(EXTRA_ASKER) ?: return
        val ok = intent.getBooleanExtra(EXTRA_OK, true)
        context.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra(EXTRA_NOTIF, 0))
        FaroSettings.init(context)
        FaroGroups.init(context)
        // Si la app estaba cerrada, los grupos tardan un momento en cargarse.
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { FaroGroups.groups.first { it.isNotEmpty() } }
            FaroGroups.answerAsk(asker, ok)
            kotlinx.coroutines.delay(1_000) // deja salir el mensaje
            pending.finish()
        }
    }

    companion object {
        const val EXTRA_ASKER = "asker"
        const val EXTRA_OK = "ok"
        const val EXTRA_NOTIF = "notif"
    }
}
