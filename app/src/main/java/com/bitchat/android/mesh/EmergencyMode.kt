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
}
