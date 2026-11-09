package com.bitchat.android.net

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress

/**
 * Faro: Tor se ha quitado de la app (gastaba batería y añadía unos 21 MB de librerías
 * nativas). El contenido de los mensajes ya va cifrado de extremo a extremo.
 *
 * Esta clase se mantiene con la misma interfaz que la original de bitchat para no tocar
 * el resto del código: siempre informa de Tor apagado y no disponible, y todas las
 * conexiones van directas.
 */
class ArtiTorManager private constructor() {
    enum class TorState {
        OFF,
        STARTING,
        BOOTSTRAPPING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF
    )

    companion object {
        @Volatile
        private var INSTANCE: ArtiTorManager? = null

        fun getInstance(): ArtiTorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtiTorManager().also { INSTANCE = it }
            }
        }
    }

    private val _statusFlow = MutableStateFlow(TorStatus())
    val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    fun isProxyEnabled(): Boolean = false

    fun init(application: Application) = Unit

    fun currentSocksAddress(): InetSocketAddress? = null

    /** Sin Tor, la ruta directa siempre está lista. */
    suspend fun awaitSelectedRoute(timeoutMs: Long): Boolean = true

    suspend fun applyMode(application: Application, mode: TorMode) = Unit

    fun isTorAvailable(): Boolean = false
}
