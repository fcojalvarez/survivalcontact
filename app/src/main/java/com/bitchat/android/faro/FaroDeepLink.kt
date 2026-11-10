package com.bitchat.android.faro

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Enlace de invitación abierto desde fuera (WhatsApp, navegador…) a la espera de mostrarse. */
object FaroDeepLink {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    const val EXTRA_ASK_HELP = "faro_pedir_ayuda"

    /** «Pedir ayuda» pulsado en el widget: la app muestra la confirmación al abrirse. */
    private val _askHelp = MutableStateFlow(false)
    val askHelp: StateFlow<Boolean> = _askHelp.asStateFlow()

    fun consumeAskHelp() {
        _askHelp.value = false
    }

    fun capture(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ASK_HELP, false) == true) {
            _askHelp.value = true
            intent.removeExtra(EXTRA_ASK_HELP)
        }
        val data = intent?.dataString ?: return
        if (FaroJoin.isLink(data)) _pending.value = data
    }

    fun consume() {
        _pending.value = null
    }
}
