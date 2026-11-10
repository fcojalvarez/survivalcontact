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
    /** Lo creé yo: decido quién entra. */
    val admin: Boolean = false,
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

/** Alguien ha aceptado un enlace mío y espera a que le deje entrar. */
data class JoinRequest(
    val groupId: String,
    val memberKeyHex: String,
    val memberName: String,
    val reply: ByteArray,
    val replyTagHex: String,
    val timestampMs: Long,
) {
    val id: String get() = "$groupId:$memberKeyHex"
}

/** Alguien ha mirado dónde estoy. */
data class SeenEvent(
    val groupId: String,
    val viewerId: String,
    val viewerName: String,
    val how: FaroProtocol.SeenHow,
    val timestampMs: Long,
)

/** Algo que ha pasado en la familia para mostrar en Novedades (llegadas, «estoy bien»…). */
data class FamilyEvent(
    val groupId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val timestampMs: Long,
)

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
    private const val KEY_REQUESTS = "join_requests"
    private const val KEY_READ = "last_read"
    private const val KEY_PLACES = "places"
    /** Una solicitud sin respuesta caduca a los 7 días, como el enlace. */
    private const val REQUEST_TTL_MS = 7L * 24 * 3_600_000
    private const val MAX_MESSAGES = 300
    private const val MAX_PENDING_REQUESTS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _groups = MutableStateFlow<List<FaroGroup>>(emptyList())
    val groups: StateFlow<List<FaroGroup>> = _groups.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<FaroMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<FaroMessage>>> = _messages.asStateFlow()

    /** Última posición de cada miembro, por grupo. */
    private val _positions = MutableStateFlow<Map<String, Map<String, MemberPosition>>>(emptyMap())
    val positions: StateFlow<Map<String, Map<String, MemberPosition>>> = _positions.asStateFlow()

    /** Solicitudes de entrada pendientes de que el administrador las acepte. */
    private val _joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val joinRequests: StateFlow<List<JoinRequest>> = _joinRequests.asStateFlow()

    /** Hasta cuándo he leído cada grupo (para marcar lo nuevo en Inicio y en Chats). */
    private val _lastRead = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastRead: StateFlow<Map<String, Long>> = _lastRead.asStateFlow()

    fun markRead(groupId: String) {
        val last = _messages.value[groupId]?.lastOrNull()?.timestampMs ?: System.currentTimeMillis()
        if ((_lastRead.value[groupId] ?: 0L) >= last) return
        _lastRead.update { it + (groupId to last) }
        prefs?.edit()?.putString(KEY_READ, JSONObject(_lastRead.value as Map<*, *>).toString())?.apply()
    }

    /** Lugares de cada grupo (Casa, Cole…). */
    private val _places = MutableStateFlow<Map<String, List<FaroProtocol.Place>>>(emptyMap())
    val places: StateFlow<Map<String, List<FaroProtocol.Place>>> = _places.asStateFlow()

    /** Viajes «Voy para allá» en curso de cada persona: id → (nombre, viaje). */
    private val _trips = MutableStateFlow<Map<String, Pair<String, FaroProtocol.Trip>>>(emptyMap())
    val trips: StateFlow<Map<String, Pair<String, FaroProtocol.Trip>>> = _trips.asStateFlow()

    /** A quién he preguntado si está bien y cuándo (se borra al contestar). */
    private val _asks = MutableStateFlow<Map<String, Long>>(emptyMap())
    val asks: StateFlow<Map<String, Long>> = _asks.asStateFlow()

    /** Llegadas, salidas, «estoy bien» y respuestas de las últimas 24 h (solo en memoria). */
    private val _events = MutableStateFlow<List<FamilyEvent>>(emptyList())
    val events: StateFlow<List<FamilyEvent>> = _events.asStateFlow()

    private fun addEvent(e: FamilyEvent) {
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        _events.update { list -> (list.filter { it.timestampMs > since } + e).sortedBy { it.timestampMs }.takeLast(100) }
    }

    /** Quién ha mirado dónde estoy en las últimas 24 h (solo en memoria). */
    private val _seen = MutableStateFlow<List<SeenEvent>>(emptyList())
    val seenBy: StateFlow<List<SeenEvent>> = _seen.asStateFlow()

    /** Última vez que avisé a cada persona de que la he mirado (para no repetir en 15 min). */
    private val seenSentAt = HashMap<String, Long>()

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

    fun createGroup(name: String): FaroGroup {
        val group = FaroGroup(
            id = FaroProtocol.newGroupId().toHex(),
            name = name.trim().take(40).ifBlank { "Grupo" },
            keyHex = FaroProtocol.newGroupKey().toHex(),
            createdAtMs = System.currentTimeMillis(),
            admin = true,
        )
        _groups.update { it + group }
        save()
        return group
    }

    fun leaveGroup(groupId: String) {
        _groups.update { list -> list.filterNot { it.id == groupId } }
        _joinRequests.update { list -> list.filterNot { it.groupId == groupId } }
        saveRequests()
        _places.update { it - groupId }
        savePlaces()
        synchronized(pendingSaves) { pendingSaves.remove(groupId)?.cancel() }
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

    // ---------- Familia ----------

    private fun hhmm(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    /** «Estoy bien» a todos mis grupos. */
    fun sendImOk() {
        val body = FaroProtocol.encodeCheck(FaroProtocol.Check.IM_OK)
        _groups.value.forEach { send(it, FaroProtocol.Kind.CHECK, body, System.currentTimeMillis()) }
    }

    private fun groupWith(personId: String): FaroGroup? =
        _groups.value.firstOrNull { personId in it.members.keys || _positions.value[it.id]?.containsKey(personId) == true }

    /** Pregunta a una persona si está bien. */
    fun askIfOk(personId: String) {
        val g = groupWith(personId) ?: return
        val target = personId.hexToBytes() ?: return
        _asks.update { it + (personId to System.currentTimeMillis()) }
        send(g, FaroProtocol.Kind.CHECK, FaroProtocol.encodeCheck(FaroProtocol.Check.ASK, target), System.currentTimeMillis())
    }

    /** Contesto a quien me preguntó (desde la notificación o la app). */
    fun answerAsk(askerId: String, ok: Boolean) {
        val g = groupWith(askerId) ?: return
        val target = askerId.hexToBytes() ?: return
        val type = if (ok) FaroProtocol.Check.ANSWER_OK else FaroProtocol.Check.ANSWER_HELP
        send(g, FaroProtocol.Kind.CHECK, FaroProtocol.encodeCheck(type, target), System.currentTimeMillis())
        if (!ok) FaroLocation.sendSos(true)
    }

    fun savePlace(groupId: String, place: FaroProtocol.Place) {
        val g = group(groupId) ?: return
        applyPlace(groupId, place, delete = false)
        send(g, FaroProtocol.Kind.PLACE, FaroProtocol.encodePlace(place), System.currentTimeMillis())
    }

    fun deletePlace(groupId: String, place: FaroProtocol.Place) {
        val g = group(groupId) ?: return
        applyPlace(groupId, place, delete = true)
        send(g, FaroProtocol.Kind.PLACE, FaroProtocol.encodePlace(place, delete = true), System.currentTimeMillis())
    }

    private fun applyPlace(groupId: String, place: FaroProtocol.Place, delete: Boolean) {
        _places.update { all ->
            val list = all[groupId].orEmpty().filterNot { it.id == place.id }
            all + (groupId to (if (delete) list else (list + place).take(20)))
        }
        savePlaces()
    }

    /** Aviso de que he llegado a un lugar de un grupo o he salido de él. */
    fun sendArrival(groupId: String, placeId: String, enter: Boolean) {
        val g = group(groupId) ?: return
        send(g, FaroProtocol.Kind.ARRIVE, FaroProtocol.encodeArrive(placeId, enter), System.currentTimeMillis())
    }

    /** «Voy para allá» (o su fin) a los grupos con los que comparto ubicación. */
    fun sendTrip(trip: FaroProtocol.Trip) {
        val body = FaroProtocol.encodeTrip(trip)
        _groups.value.filter { it.shareLocation }.forEach { send(it, FaroProtocol.Kind.TRIP, body, System.currentTimeMillis()) }
    }

    private fun savePlaces() {
        val p = prefs ?: return
        val o = JSONObject()
        _places.value.forEach { (gid, list) ->
            o.put(gid, JSONArray().apply {
                list.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("la", it.lat).put("lo", it.lon).put("r", it.radiusM)) }
            })
        }
        p.edit().putString(KEY_PLACES, o.toString()).apply()
    }

    /** Alguien de mis grupos ha pedido ayuda en [windowMs] y su última posición sigue siendo de ayuda. */
    fun someoneNeedsHelp(windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return _positions.value.values.any { m ->
            m.values.any { it.position.status == FaroProtocol.Position.Status.NEED_HELP && now - it.timestampMs < windowMs }
        }
    }

    /** Pide a una persona que envíe su ubicación cada 30 s durante [minutes] minutos (0 = parar). */
    fun sendFollow(personId: String, minutes: Int) {
        val target = personId.hexToBytes() ?: return
        val g = _groups.value.firstOrNull { personId in it.members.keys || _positions.value[it.id]?.containsKey(personId) == true } ?: return
        send(g, FaroProtocol.Kind.FOLLOW, FaroProtocol.encodeFollow(target, minutes), System.currentTimeMillis())
    }

    /**
     * Avisa a las personas de que he mirado dónde están. Solo por internet y solo si lo hay:
     * sin conexión, o si alguien ha pedido ayuda, es una emergencia y no se avisa.
     * Como mucho un aviso por persona cada 15 min.
     */
    fun notifySeen(personIds: Collection<String>, how: FaroProtocol.SeenHow) {
        val ctx = appContext ?: return
        if (!isOnline(ctx) || FaroLocation.sosActive.value) return
        val now = System.currentTimeMillis()
        val helpRecent = { id: String ->
            _positions.value.values.any { m ->
                val p = m[id]
                p != null && p.position.status == FaroProtocol.Position.Status.NEED_HELP && now - p.timestampMs < 12 * 3_600_000L
            }
        }
        if (_positions.value.values.any { m -> m.values.any { helpRecent(it.authorId) } }) return
        val due = personIds.filter { (seenSentAt[it] ?: 0L) < now - 15 * 60_000L }
        if (due.isEmpty()) return
        val pending = due.toMutableSet()
        _groups.value.forEach { g ->
            val here = pending.filter { it in g.members.keys || _positions.value[g.id]?.containsKey(it) == true }
            if (here.isEmpty()) return@forEach
            val body = FaroProtocol.encodeSeen(how, here.mapNotNull { it.hexToBytes() })
            sendInternetOnly(g, FaroProtocol.Kind.SEEN, body, now)
            here.forEach { seenSentAt[it] = now; pending.remove(it) }
        }
    }

    private fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun sendInternetOnly(g: FaroGroup, kind: FaroProtocol.Kind, body: ByteArray, ts: Long) {
        scope.launch {
            val key = crypto()?.getSigningPublicKey() ?: return@launch
            val payload = FaroProtocol.seal(g.key, FaroProtocol.Inner(kind, ts, key, myName(), body), ::sign) ?: return@launch
            FaroInternet.publish(payload, FaroProtocol.tagFor(g.key).toHex())
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
        val g = _groups.value.firstOrNull { FaroProtocol.matchesGroup(it.key, tag) } ?: return
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
                FaroLocation.refreshEmergency()
            }
            FaroProtocol.Kind.HELLO -> Unit
            FaroProtocol.Kind.FOLLOW -> {
                val (target, minutes) = FaroProtocol.decodeFollow(inner.body) ?: return
                if (target.toHex() != me || !g.shareLocation) return
                FaroLocation.onFollowRequest(inner.authorId, inner.authorName, minutes)
            }
            FaroProtocol.Kind.CHECK -> {
                val (type, target) = FaroProtocol.decodeCheck(inner.body) ?: return
                val forMe = target?.toHex() == me
                when (type) {
                    FaroProtocol.Check.IM_OK -> {
                        addEvent(FamilyEvent(g.id, inner.authorId, inner.authorName, "${inner.authorName}: Estoy bien", inner.timestampMs))
                        appContext?.let { FaroNotifications.family(it, g.id, "${inner.authorName} está bien", "Lo ha avisado con «Estoy bien».") }
                    }
                    FaroProtocol.Check.ASK -> if (forMe) appContext?.let { FaroNotifications.ask(it, g.id, inner.authorId, inner.authorName) }
                    FaroProtocol.Check.ANSWER_OK, FaroProtocol.Check.ANSWER_HELP -> if (forMe) {
                        _asks.update { it - inner.authorId }
                        val ok = type == FaroProtocol.Check.ANSWER_OK
                        val text = if (ok) "${inner.authorName}: Estoy bien" else "${inner.authorName} necesita ayuda"
                        addEvent(FamilyEvent(g.id, inner.authorId, inner.authorName, text, inner.timestampMs))
                        appContext?.let { FaroNotifications.family(it, g.id, text, if (ok) "Ha contestado a tu pregunta." else "Ha contestado que necesita ayuda.") }
                    }
                }
            }
            FaroProtocol.Kind.PLACE -> {
                val (place, delete) = FaroProtocol.decodePlace(inner.body) ?: return
                applyPlace(g.id, place, delete)
            }
            FaroProtocol.Kind.ARRIVE -> {
                val (placeId, enter) = FaroProtocol.decodeArrive(inner.body) ?: return
                val place = _places.value[g.id]?.firstOrNull { it.id == placeId } ?: return
                val text = if (enter) "${inner.authorName} ha llegado a ${place.name}" else "${inner.authorName} ha salido de ${place.name}"
                addEvent(FamilyEvent(g.id, inner.authorId, inner.authorName, text, inner.timestampMs))
                if (FaroSettings.placeNotify(placeId, enter)) appContext?.let { FaroNotifications.family(it, g.id, text, g.name) }
            }
            FaroProtocol.Kind.TRIP -> {
                val trip = FaroProtocol.decodeTrip(inner.body) ?: return
                val before = _trips.value[inner.authorId]
                if (trip.active) {
                    _trips.update { it + (inner.authorId to (inner.authorName to trip)) }
                    if (before == null) appContext?.let {
                        FaroNotifications.family(it, g.id, "${inner.authorName} va para ${trip.destName}", "Llega a las ${hhmm(trip.etaMs)}")
                    }
                } else {
                    _trips.update { it - inner.authorId }
                    if (before != null) addEvent(FamilyEvent(g.id, inner.authorId, inner.authorName, "${inner.authorName} ha llegado a ${trip.destName}", inner.timestampMs))
                }
            }
            FaroProtocol.Kind.SEEN -> {
                val (how, targets) = FaroProtocol.decodeSeen(inner.body) ?: return
                if (targets.none { it.toHex() == me }) return
                // En una emergencia es normal mirar: no se avisa.
                if (FaroLocation.sosActive.value) return
                val ev = SeenEvent(g.id, inner.authorId, inner.authorName, how, inner.timestampMs)
                val since = now - 24 * 3_600_000L
                _seen.update { list -> (list.filter { it.timestampMs > since && it.viewerId != ev.viewerId } + ev).sortedBy { it.timestampMs } }
                appContext?.let { FaroNotifications.seen(it, inner.authorName) }
            }
        }
    }

    private fun onJoinPacket(payload: ByteArray) {
        val incoming = FaroJoin.handle(payload) { id ->
            group(id)?.let { g -> Triple(g.id.hexToBytes() ?: return@let null, g.key, g.name) }
        } ?: return
        when (incoming) {
            is FaroJoin.Incoming.Request -> {
                // No entra nadie sin que el administrador lo acepte.
                val req = JoinRequest(
                    groupId = incoming.groupId,
                    memberKeyHex = incoming.memberKey.toHex(),
                    memberName = incoming.memberName,
                    reply = incoming.reply,
                    replyTagHex = incoming.replyTagHex,
                    timestampMs = System.currentTimeMillis(),
                )
                // Como mucho 5 solicitudes pendientes por grupo, para que nadie pueda llenarte Inicio.
                if (_joinRequests.value.count { it.groupId == req.groupId && it.id != req.id } >= MAX_PENDING_REQUESTS) return
                _joinRequests.update { list -> list.filterNot { it.id == req.id } + req }
                saveRequests()
                val g = group(incoming.groupId)
                if (g != null) appContext?.let { FaroNotifications.joinRequest(it, g.id, g.name, incoming.memberName) }
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
                        admin = false,
                    )
                    _groups.update { it + g }
                    save()
                    sendHello(g)
                    FaroLocation.requestSoon()
                }
            }
        }
    }

    /** El administrador deja entrar a quien lo ha pedido: le envía la clave del grupo cifrada. */
    fun approve(request: JoinRequest) {
        if (group(request.groupId)?.admin != true) return
        rememberMember(request.groupId, request.memberKeyHex, request.memberName)
        broadcastRaw(request.reply, request.replyTagHex)
        _joinRequests.update { list -> list.filterNot { it.id == request.id } }
        saveRequests()
    }

    /** Rechaza la solicitud: no se le envía nada y el enlace ya no sirve. */
    fun reject(request: JoinRequest) {
        _joinRequests.update { list -> list.filterNot { it.id == request.id } }
        saveRequests()
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
                put("admin", g.admin)
                put("share", g.shareLocation)
                put("members", JSONObject(g.members))
            })
        }
        p.edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    private fun saveRequests() {
        val p = prefs ?: return
        val arr = JSONArray()
        _joinRequests.value.forEach { r ->
            arr.put(JSONObject().apply {
                put("g", r.groupId); put("k", r.memberKeyHex); put("n", r.memberName)
                put("r", android.util.Base64.encodeToString(r.reply, android.util.Base64.NO_WRAP))
                put("t", r.replyTagHex); put("ts", r.timestampMs)
            })
        }
        p.edit().putString(KEY_REQUESTS, arr.toString()).apply()
    }

    private val pendingSaves = HashMap<String, kotlinx.coroutines.Job>()

    /**
     * Guarda los mensajes de un grupo agrupando las escrituras: si llegan varios seguidos, se
     * cifra y escribe una sola vez a los 2 s en lugar de una vez por mensaje.
     */
    private fun saveMessages(groupId: String) {
        synchronized(pendingSaves) {
            pendingSaves[groupId]?.cancel()
            pendingSaves[groupId] = scope.launch {
                kotlinx.coroutines.delay(2_000)
                writeMessages(groupId)
                synchronized(pendingSaves) { pendingSaves.remove(groupId) }
            }
        }
    }

    private fun writeMessages(groupId: String) {
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
                    // Los grupos de versiones anteriores no lo guardaban: los trato como míos.
                    admin = o.optBoolean("admin", true),
                    shareLocation = o.optBoolean("share", false),
                    members = membersObj.keys().asSequence().associateWith { membersObj.getString(it) },
                )
            }.getOrNull()
        }
        _groups.value = list
        runCatching {
            val po = JSONObject(p.getString(KEY_PLACES, "{}") ?: "{}")
            _places.value = po.keys().asSequence().associateWith { gid ->
                val a = po.getJSONArray(gid)
                (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    FaroProtocol.Place(x.getString("id"), x.getString("n"), x.getDouble("la"), x.getDouble("lo"), x.getInt("r"))
                }
            }
        }
        val readObj = runCatching { JSONObject(p.getString(KEY_READ, "{}") ?: "{}") }.getOrDefault(JSONObject())
        _lastRead.value = readObj.keys().asSequence().associateWith { readObj.optLong(it) }
        val ra = JSONArray(p.getString(KEY_REQUESTS, "[]"))
        val now = System.currentTimeMillis()
        _joinRequests.value = (0 until ra.length()).mapNotNull { i ->
            runCatching {
                val o = ra.getJSONObject(i)
                JoinRequest(
                    groupId = o.getString("g"),
                    memberKeyHex = o.getString("k"),
                    memberName = o.getString("n"),
                    reply = android.util.Base64.decode(o.getString("r"), android.util.Base64.NO_WRAP),
                    replyTagHex = o.getString("t"),
                    timestampMs = o.getLong("ts"),
                )
            }.getOrNull()
        }.filter { now - it.timestampMs < REQUEST_TTL_MS && list.any { g -> g.id == it.groupId } }
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
