package com.bitchat.android.faro

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Enlace de invitación abierto desde fuera (WhatsApp, navegador…) a la espera de mostrarse. */
object FaroDeepLink {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun capture(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (FaroJoin.isLink(data)) _pending.value = data
    }

    fun consume() {
        _pending.value = null
    }
}
