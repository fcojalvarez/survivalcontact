package com.bitchat.android.faro

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.NicknameProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class FaroGroup(
    val id: String,
    val name: String,
    val keyHex: String,
    val createdAtMs: Long,
    val parentId: String? = null,
    /** Consentimiento: si comparto mi ubicación con este grupo. Desactivado por defecto. */
    val shareLocation: Boolean = false,
    /** Miembros conocidos: clave pública (hex) → nombre. */
    val members: Map<String, String> = emptyMap(),
) {
    val key: ByteArray get() = keyHex.hexToBytes() ?: ByteArray(0)
}

data class FaroMessage(
    val groupId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val timestampMs: Long,
    val mine: Boolean,
) {
    val id: String get() = "$authorId:$timestampMs"
}

data class MemberPosition(
    val authorId: String,
    val name: String,
    val position: FaroProtocol.Position,
    val timestampMs: Long,
)

/**
 * Centro de los grupos privados de Faro: los guarda cifrados en el móvil, envía y recibe sus
 * mensajes y posiciones por la malla y expone el estado para la interfaz.
 */
object FaroGroups {
    private const val TAG = "FaroGroups"
    private const val PREFS = "faro_groups_secure"
    private const val KEY_GROUPS = "groups"
    private const val MAX_MESSAGES = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _groups = MutableStateFlow<List<FaroGroup>>(emptyList())
    val groups: StateFlow<List<FaroGroup>> = _groups.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<FaroMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<FaroMessage>>> = _messages.asStateFlow()

    /** Última posición de cada miembro, por grupo. */
    private val _positions = MutableStateFlow<Map<String, Map<String, MemberPosition>>>(emptyMap())
    val positions: StateFlow<Map<String, Map<String, MemberPosition>>> = _positions.asStateFlow()

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var crypto: EncryptionService? = null
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            runCatching {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                load()
                prefs?.let { FaroJoin.attach(it) }
                // Reenvía las solicitudes de unión que aún esperan respuesta.
                FaroJoin.pendingRequests().forEach { broadcastRaw(it, FaroJoin.tagOf(it)) }
            }.onFailure { Log.e(TAG, "No se pudieron cargar los grupos: ${it.message}") }
        }
    }

    private fun crypto(): EncryptionService? {
        crypto?.let { return it }
        val ctx = appContext ?: return null
        return runCatching { EncryptionService(ctx) }.getOrNull()?.also { crypto = it }
    }

    fun myId(): String? = crypto()?.getSigningPublicKey()?.toHex()

    fun myName(): String {
        val ctx = appContext ?: return "Yo"
        val peerId = MeshServiceHolder.meshService?.myPeerID ?: return "Yo"
        return NicknameProvider.getNickname(ctx, peerId)
    }

    private fun sign(data: ByteArray): ByteArray? = crypto()?.signData(data)
    private fun verify(sig: ByteArray, data: ByteArray, pub: ByteArray): Boolean =
        crypto()?.verifyEd25519Signature(sig, data, pub) ?: false

    // ---------- Grupos ----------

    fun createGroup(name: String, parentId: String? = null): FaroGroup {
        val group = FaroGroup(
            id = FaroProtocol.newGroupId().toHex(),
            name = name.trim().take(40).ifBlank { "Grupo" },
            keyHex = FaroProtocol.newGroupKey().toHex(),
            createdAtMs = System.currentTimeMillis(),
            parentId = parentId,
        )
        _groups.update { it + group }
        save()
        return group
    }

    fun leaveGroup(groupId: String) {
        _groups.update { list -> list.filterNot { it.id == groupId || it.parentId == groupId } }
        _messages.update { it - groupId }
        _positions.update { it - groupId }
        prefs?.edit()?.remove(msgKey(groupId))?.apply()
        save()
    }

    fun setShareLocation(groupId: String, share: Boolean) {
        _groups.update { list -> list.map { if (it.id == groupId) it.copy(shareLocation = share) else it } }
        save()
        if (share) FaroLocation.requestSoon()
    }

    fun group(groupId: String): FaroGroup? = _groups.value.firstOrNull { it.id == groupId }

    // ---------- Invitaciones ----------

    fun inviteCode(groupId: String): String? {
        val g = group(groupId) ?: return null
        val me = crypto()?.getSigningPublicKey() ?: return null
        return FaroInvite.encode(
            groupId = g.id.hexToBytes() ?: return null,
            groupKey = g.key,
            groupName = g.name,
            inviterKey = me,
            nowS = System.currentTimeMillis() / 1000,
            sign = ::sign,
        )
    }

    sealed class JoinResult {
        data class Joined(val group: FaroGroup) : JoinResult()
        data class AlreadyMember(val group: FaroGroup) : JoinResult()
        object NotAnInvite : JoinResult()
        object Invalid : JoinResult()
        object Expired : JoinResult()
    }

    /** Crea un enlace de invitación (sin la clave del grupo) para enviarlo por cualquier app. */
    fun inviteLink(groupId: String): String? {
        val g = group(groupId) ?: return null
        val me = crypto()?.getSigningPublicKey() ?: return null
        return FaroJoin.createLink(
            groupId = g.id,
            groupName = g.name,
            inviterName = myName(),
            members = listOf(myName()) + g.members.values,
            inviterSigKey = me,
            sign = ::sign,
        )
    }

    fun previewLink(text: String): FaroJoin.Parse = FaroJoin.parse(text, ::verify)

    /** Acepta un enlace: envía la solicitud cifrada a quien invita, por la malla y por internet. */
    fun acceptLink(invite: FaroJoin.LinkInvite) {
        val me = crypto()?.getSigningPublicKey() ?: return
        val (payload, tag) = FaroJoin.buildRequest(invite, me, myName())
        broadcastRaw(payload, tag)
    }

    /** Lee una invitación sin unirse todavía (para mostrar la ventana de confirmación). */
    fun previewCode(code: String): FaroInvite.Result =
        FaroInvite.decode(code.trim(), System.currentTimeMillis() / 1000, ::verify)

    /**
     * Se une al grupo de la invitación. Aceptar implica consentir compartir la ubicación con el
     * grupo para emergencias (la ventana lo explica); se puede desactivar después.
     */
    fun joinInvite(invite: FaroInvite.Invite, shareLocation: Boolean): JoinResult {
        val id = invite.groupId.toHex()
        group(id)?.let { return JoinResult.AlreadyMember(it) }
        val g = FaroGroup(
            id = id,
            name = invite.groupName,
            keyHex = invite.groupKey.toHex(),
            createdAtMs = System.currentTimeMillis(),
            shareLocation = shareLocation,
        )
        _groups.update { it + g }
        save()
        sendHello(g)
        if (shareLocation) FaroLocation.requestSoon()
        return JoinResult.Joined(g)
    }

    // ---------- Envío ----------

    fun sendText(groupId: String, text: String) {
        val g = group(groupId) ?: return
        val clean = text.trim().take(2000)
        if (clean.isEmpty()) return
        val ts = System.currentTimeMillis()
        val me = myId() ?: return
        addMessage(FaroMessage(g.id, me, myName(), clean, ts, mine = true))
        send(g, FaroProtocol.Kind.TEXT, clean.toByteArray(Charsets.UTF_8), ts)
    }

    fun sendPosition(groupId: String, position: FaroProtocol.Position) {
        val g = group(groupId) ?: return
        if (!g.shareLocation && position.status != FaroProtocol.Position.Status.NEED_HELP) return
        send(g, FaroProtocol.Kind.LOCATION, position.encode(), System.currentTimeMillis())
    }

    private fun sendHello(g: FaroGroup) = send(g, FaroProtocol.Kind.HELLO, ByteArray(0), System.currentTimeMillis())

    private fun send(g: FaroGroup, kind: FaroProtocol.Kind, body: ByteArray, ts: Long) {
        scope.launch {
            val key = crypto()?.getSigningPublicKey() ?: return@launch
            val payload = FaroProtocol.seal(
                g.key,
                FaroProtocol.Inner(kind, ts, key, myName(), body),
                ::sign
            ) ?: return@launch
            broadcastRaw(payload, FaroProtocol.tagFor(g.key).toHex())
        }
    }

    /** Envía un paquete ya preparado por la malla (si está activa) y por internet (si hay). */
    private fun broadcastRaw(payload: ByteArray, tagHex: String) {
        MeshServiceHolder.unifiedMeshService?.sendFaroBroadcast(payload)
            ?: Log.w(TAG, "Malla no disponible; solo se envía por internet")
        FaroInternet.publish(payload, tagHex)
    }

    // ---------- Recepción ----------

    /** Llamado por la malla con la carga útil de un paquete de tipo [FaroProtocol.PACKET_TYPE]. */
    fun onMeshPayload(payload: ByteArray) {
        if (FaroJoin.isJoinPacket(payload)) {
            onJoinPacket(payload)
            return
        }
        val tag = FaroProtocol.peekTag(payload) ?: return
        val g = _groups.value.firstOrNull { FaroProtocol.tagFor(it.key).contentEquals(tag) } ?: return
        val inner = FaroProtocol.open(g.key, payload, ::verify) ?: return
        val me = myId()
        if (inner.authorId == me) return
        val dedupKey = "${g.id}:${inner.authorId}:${inner.timestampMs}:${inner.kind}"
        synchronized(seen) {
            if (!seen.add(dedupKey)) return
            if (seen.size > 2000) seen.remove(seen.first())
        }
        // Rechaza mensajes de más de 12 h o del futuro lejano (relojes muy desajustados).
        val now = System.currentTimeMillis()
        if (inner.timestampMs > now + 10 * 60_000 || inner.timestampMs < now - 12 * 3_600_000) return

        rememberMember(g.id, inner.authorId, inner.authorName)
        when (inner.kind) {
            FaroProtocol.Kind.TEXT -> {
                val text = runCatching { inner.body.toString(Charsets.UTF_8) }.getOrNull() ?: return
                addMessage(FaroMessage(g.id, inner.authorId, inner.authorName, text, inner.timestampMs, mine = false))
                appContext?.let { FaroNotifications.message(it, g.id, g.name, inner.authorName, text) }
            }
            FaroProtocol.Kind.LOCATION -> {
                val pos = FaroProtocol.Position.decode(inner.body) ?: return
                val wasHelp = _positions.value[g.id]?.get(inner.authorId)?.position?.status == FaroProtocol.Position.Status.NEED_HELP
                if (pos.status == FaroProtocol.Position.Status.NEED_HELP && !wasHelp) {
                    val me = FaroLocation.myLocation.value
                    val distance = me?.let {
                        val target = android.location.Location("faro").apply { latitude = pos.lat; longitude = pos.lon }
                        val m = it.distanceTo(target)
                        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)
                    }
                    appContext?.let { FaroNotifications.help(it, g.id, g.name, inner.authorName, distance) }
                }
                _positions.update { all ->
                    val current = all[g.id].orEmpty()
                    val old = current[inner.authorId]
                    if (old != null && old.timestampMs >= inner.timestampMs) return@update all
                    all + (g.id to (current + (inner.authorId to MemberPosition(inner.authorId, inner.authorName, pos, inner.timestampMs))))
                }
            }
            FaroProtocol.Kind.HELLO -> Unit
        }
    }

    private fun onJoinPacket(payload: ByteArray) {
        val incoming = FaroJoin.handle(payload) { id ->
            group(id)?.let { g -> Triple(g.id.hexToBytes() ?: return@let null, g.key, g.name) }
        } ?: return
        when (incoming) {
            is FaroJoin.Incoming.Request -> {
                rememberMember(incoming.groupId, incoming.memberKey.toHex(), incoming.memberName)
                broadcastRaw(incoming.reply, incoming.replyTagHex)
            }
            is FaroJoin.Incoming.Granted -> {
                val id = incoming.groupId.toHex()
                if (group(id) == null) {
                    val g = FaroGroup(
                        id = id,
                        name = incoming.groupName,
                        keyHex = incoming.groupKey.toHex(),
                        createdAtMs = System.currentTimeMillis(),
                        shareLocation = true, // Aceptado en la ventana que lo explica.
                    )
                    _groups.update { it + g }
                    save()
                    sendHello(g)
                    FaroLocation.requestSoon()
                }
            }
        }
    }

    private fun rememberMember(groupId: String, authorId: String, name: String) {
        val g = group(groupId) ?: return
        if (g.members[authorId] == name) return
        _groups.update { list -> list.map { if (it.id == groupId) it.copy(members = it.members + (authorId to name)) else it } }
        save()
    }

    private fun addMessage(m: FaroMessage) {
        _messages.update { all ->
            val list = (all[m.groupId].orEmpty() + m).distinctBy { it.id }.sortedBy { it.timestampMs }.takeLast(MAX_MESSAGES)
            all + (m.groupId to list)
        }
        saveMessages(m.groupId)
    }

    // ---------- Persistencia ----------

    private fun msgKey(groupId: String) = "msgs_$groupId"

    private fun save() {
        val p = prefs ?: return
        val arr = JSONArray()
        _groups.value.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id); put("name", g.name); put("key", g.keyHex); put("created", g.createdAtMs)
                g.parentId?.let { put("parent", it) }
                put("share", g.shareLocation)
                put("members", JSONObject(g.members))
            })
        }
        p.edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    private fun saveMessages(groupId: String) {
        val p = prefs ?: return
        val arr = JSONArray()
        _messages.value[groupId].orEmpty().forEach { m ->
            arr.put(JSONObject().apply {
                put("a", m.authorId); put("n", m.authorName); put("t", m.text); put("ts", m.timestampMs); put("me", m.mine)
            })
        }
        p.edit().putString(msgKey(groupId), arr.toString()).apply()
    }

    private fun load() {
        val p = prefs ?: return
        val arr = JSONArray(p.getString(KEY_GROUPS, "[]"))
        val list = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val membersObj = o.optJSONObject("members") ?: JSONObject()
                FaroGroup(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    keyHex = o.getString("key"),
                    createdAtMs = o.optLong("created"),
                    parentId = o.optString("parent").ifBlank { null },
                    shareLocation = o.optBoolean("share", false),
                    members = membersObj.keys().asSequence().associateWith { membersObj.getString(it) },
                )
            }.getOrNull()
        }
        _groups.value = list
        _messages.value = list.associate { g ->
            val ma = JSONArray(p.getString(msgKey(g.id), "[]"))
            g.id to (0 until ma.length()).mapNotNull { i ->
                runCatching {
                    val o = ma.getJSONObject(i)
                    FaroMessage(g.id, o.getString("a"), o.getString("n"), o.getString("t"), o.getLong("ts"), o.optBoolean("me"))
                }.getOrNull()
            }
        }
    }
}
