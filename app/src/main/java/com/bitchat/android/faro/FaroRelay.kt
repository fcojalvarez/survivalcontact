package com.bitchat.android.faro

import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * «Mensajes que esperan»: cada móvil guarda unas horas los paquetes de Faro que ha visto (suyos o
 * de otros, cifrados; sin la clave no puede leerlos) y los vuelve a enviar cuando aparece por
 * Bluetooth un móvil nuevo. Así un mensaje llega aunque la persona no estuviera al alcance al
 * enviarlo, y también se lleva a la malla lo que llegó por internet.
 *
 * Batería: solo se reenvía al aparecer alguien nuevo, como mucho una vez cada 2 min y los 40
 * paquetes más recientes. Solo en memoria: al cerrar la app se olvida.
 */
object FaroRelay {
    private const val TTL_MS = 6 * 3_600_000L
    private const val MAX_STORED = 300
    private const val MAX_RESEND = 40
    private const val MIN_GAP_MS = 2 * 60_000L

    private data class Stored(val hash: String, val payload: ByteArray, val atMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stored = LinkedHashMap<String, Stored>()
    private var started = false
    private var lastResend = 0L

    fun start() {
        synchronized(this) { if (started) return; started = true }
        scope.launch {
            var known = emptySet<String>()
            AppStateStore.directPeers.collect { peers ->
                val newcomers = peers - known
                known = peers
                if (newcomers.isNotEmpty()) {
                    delay(5_000) // deja que se termine de conectar
                    resend()
                }
            }
        }
    }

    /** Guarda un paquete visto o enviado. */
    fun remember(payload: ByteArray) {
        if (payload.size < 3 || payload.size > 4_000) return
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        synchronized(stored) {
            if (stored.containsKey(hash)) return
            stored[hash] = Stored(hash, payload, now)
            prune(now)
        }
    }

    private fun prune(now: Long) {
        val it = stored.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.atMs > TTL_MS || stored.size > MAX_STORED) it.remove() else break
        }
    }

    private suspend fun resend() {
        val now = System.currentTimeMillis()
        if (now - lastResend < MIN_GAP_MS) return
        lastResend = now
        val batch = synchronized(stored) { prune(now); stored.values.toList().takeLast(MAX_RESEND) }
        val mesh = MeshServiceHolder.unifiedMeshService ?: return
        batch.forEach {
            runCatching { mesh.sendFaroBroadcast(it.payload) }
            delay(150)
        }
    }

    internal fun sizeForTests(): Int = synchronized(stored) { stored.size }
}
