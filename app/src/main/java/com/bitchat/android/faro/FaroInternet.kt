package com.bitchat.android.faro

import android.content.Context
import android.util.Base64
import android.util.Log
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrRelayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Transporte por internet de los paquetes de Faro, a través de Nostr (servidores públicos y
 * gratuitos que nadie controla).
 *
 * Se envían exactamente los mismos paquetes que por la malla: ya van cifrados con la clave del
 * grupo y firmados, así que los servidores no pueden leerlos. Cada evento se firma con una
 * identidad Nostr nueva y desechable, para que no se pueda relacionar quién envía qué.
 * Los miembros se suscriben por la etiqueta del grupo (derivada de su clave).
 */
object FaroInternet {
    private const val TAG = "FaroInternet"

    /** Tipo de evento Nostr propio de Faro (normal: los servidores lo guardan un tiempo). */
    const val KIND = 4545
    private const val TAG_NAME = "f"
    private const val SUB_ID = "faro-grupos"
    private const val LOOKBACK_MS = 12 * 3_600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            // La etiqueta de cada grupo cambia cada día: se vuelve a suscribir al cambiar de día.
            val day = kotlinx.coroutines.flow.flow {
                while (true) {
                    emit(FaroProtocol.currentDay())
                    kotlinx.coroutines.delay(30 * 60_000L)
                }
            }.distinctUntilChanged()
            combine(FaroGroups.groups, FaroJoin.listenTags, day) { groups, joinTags, d ->
                groups.flatMap { g -> FaroProtocol.tagsAround(g.key, d).map { it.toHex() } } + joinTags
            }.distinctUntilChanged().collect { tags -> resubscribe(tags) }
        }
    }

    private fun relays(): NostrRelayManager? = appContext?.let { runCatching { NostrRelayManager.getInstance(it) }.getOrNull() }

    private fun resubscribe(tags: List<String>) {
        val mgr = relays() ?: return
        runCatching { mgr.unsubscribe(SUB_ID) }
        if (tags.isEmpty()) return
        runCatching { mgr.connect() }
        val filter = NostrFilter.Builder()
            .kinds(KIND)
            .tag(TAG_NAME, *tags.toTypedArray())
            .since(System.currentTimeMillis() - LOOKBACK_MS)
            .build()
        mgr.subscribe(filter = filter, id = SUB_ID, handler = ::onEvent)
    }

    private fun onEvent(event: NostrEvent) {
        if (event.kind != KIND) return
        val payload = runCatching { Base64.decode(event.content, Base64.NO_WRAP) }.getOrNull() ?: return
        FaroGroups.onMeshPayload(payload)
    }

    /** Publica un paquete ya cifrado con su etiqueta (hex). Si no hay internet, no hace nada. */
    fun publish(payload: ByteArray, tagHex: String) {
        val mgr = relays() ?: return
        scope.launch {
            runCatching {
                val throwaway = NostrIdentity.generate()
                val event = NostrEvent(
                    pubkey = throwaway.publicKeyHex,
                    createdAt = (System.currentTimeMillis() / 1000).toInt(),
                    kind = KIND,
                    tags = listOf(listOf(TAG_NAME, tagHex)),
                    content = Base64.encodeToString(payload, Base64.NO_WRAP),
                )
                mgr.sendEvent(throwaway.signEvent(event))
            }.onFailure { Log.w(TAG, "No se pudo publicar por internet: ${it.message}") }
        }
    }
}
