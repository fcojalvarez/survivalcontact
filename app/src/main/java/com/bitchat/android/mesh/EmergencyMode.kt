package com.bitchat.android.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Faro: modo emergencia. Está activo mientras yo pido ayuda o alguien de mis grupos la ha pedido
 * hace poco. La malla lo usa para buscar móviles más a menudo (ver PowerProfileResolver).
 */
object EmergencyMode {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun set(on: Boolean) {
        _active.value = on
    }

    /** Un rato buscando más a menudo (por ejemplo, justo al perder internet). */
    private val _boosting = MutableStateFlow(false)
    val boosting: StateFlow<Boolean> = _boosting.asStateFlow()
    @Volatile private var boostUntil = 0L

    fun boost(ms: Long) {
        boostUntil = System.currentTimeMillis() + ms
        _boosting.value = true
        Thread {
            Thread.sleep(ms)
            if (System.currentTimeMillis() >= boostUntil) _boosting.value = false
        }.apply { isDaemon = true }.start()
    }
}
