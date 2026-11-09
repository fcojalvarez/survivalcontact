package com.bitchat.android.faro

import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Invitación a distancia por enlace (por ejemplo, enviada por WhatsApp).
 *
 * El enlace NO lleva la clave del grupo: lleva el nombre del grupo, quién invita, quiénes están,
 * un código de un solo uso, una clave pública X25519 temporal y la firma de quien invita.
 *
 * 1. Quien lo abre ve una ventana con esos datos y decide «Unirme».
 * 2. Su app envía una petición cifrada para quien invita (X25519 + XChaCha20-Poly1305) por la
 *    malla y por internet.
 * 3. La app de quien invita comprueba el código (que no esté usado ni caducado) y responde con
 *    la clave del grupo cifrada solo para esa persona.
 *
 * Reenviar el enlace no da acceso por sí solo: el código sirve una vez y caduca a los 7 días.
 */
object FaroJoin {
    const val LINK_BASE = "https://fcojalvarez.github.io/survivalcontact/unirse/#"
    const val APP_LINK_BASE = "faro://unirse"
    private const val VALID_FOR_S = 7 * 24 * 3600L

    private val MAGIC_REQUEST = byteArrayOf('F'.code.toByte(), 'J'.code.toByte())
    private val MAGIC_REPLY = byteArrayOf('F'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private val random = SecureRandom()

    data class LinkInvite(
        val groupName: String,
        val inviterName: String,
        val members: List<String>,
        val inviterSigKey: ByteArray,
        val token: ByteArray,
        val invitePub: ByteArray,
        val expiresAtS: Long,
        val raw: String,
    )

    private var prefs: SharedPreferences? = null

    /** Etiquetas que esta app debe escuchar (peticiones a mis enlaces y respuestas que espero). */
    private val _listenTags = MutableStateFlow<List<String>>(emptyList())
    val listenTags: StateFlow<List<String>> = _listenTags.asStateFlow()

    /** Solicitudes enviadas a la espera de respuesta: nombre del grupo. */
    private val _waiting = MutableStateFlow<List<String>>(emptyList())
    val waiting: StateFlow<List<String>> = _waiting.asStateFlow()

    internal fun attach(p: SharedPreferences) {
        prefs = p
        refreshTags()
    }

    // ------------------------------------------------------------ Crear enlace

    fun createLink(
        groupId: String,
        groupName: String,
        inviterName: String,
        members: List<String>,
        inviterSigKey: ByteArray,
        sign: (ByteArray) -> ByteArray?,
    ): String? {
        val token = ByteArray(16).also(random::nextBytes)
        val priv = X25519PrivateKeyParameters(random)
        val pub = priv.generatePublicKey().encoded
        val expires = System.currentTimeMillis() / 1000 + VALID_FOR_S

        val unsigned = buildPayload(groupName, inviterName, members, inviterSigKey, token, pub, expires)
        val sig = sign(unsigned) ?: return null
        val code = b64(unsigned + sig)

        // Guardo el código pendiente para reconocer la petición.
        val pending = JSONObject()
            .put("group", groupId)
            .put("priv", priv.encoded.toHex())
            .put("exp", expires)
        prefs?.edit()?.putString("inv_" + token.toHex(), pending.toString())?.apply()
        refreshTags()
        return LINK_BASE + code
    }

    private fun buildPayload(
        groupName: String, inviterName: String, members: List<String>,
        inviterSigKey: ByteArray, token: ByteArray, pub: ByteArray, expires: Long,
    ): ByteArray {
        fun str(s: String, max: Int) = s.toByteArray(Charsets.UTF_8).let { if (it.size > max) it.copyOf(max) else it }
        val g = str(groupName, 48)
        val i = str(inviterName, 32)
        val ms = members.take(12).map { str(it, 24) }
        val size = 1 + 1 + g.size + 1 + i.size + 1 + ms.sumOf { 1 + it.size } + 32 + 16 + 32 + 8
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(VERSION)
            put(g.size.toByte()); put(g)
            put(i.size.toByte()); put(i)
            put(ms.size.toByte()); ms.forEach { put(it.size.toByte()); put(it) }
            put(inviterSigKey); put(token); put(pub); putLong(expires)
        }.array()
    }

    // ------------------------------------------------------------ Leer enlace

    sealed class Parse {
        data class Ok(val invite: LinkInvite) : Parse()
        object NotALink : Parse()
        object Invalid : Parse()
        object Expired : Parse()
    }

    fun isLink(text: String): Boolean = text.contains("/unirse/#") || text.trim().startsWith(APP_LINK_BASE)

    /** Extrae el código: de la web va tras «#»; al abrir la app desde la web, en «?c=». */
    private fun codeOf(text: String): String {
        val t = text.trim()
        return if (t.startsWith(APP_LINK_BASE)) {
            t.substringAfter("c=", "").substringBefore('&').substringBefore('#')
        } else {
            t.substringAfter('#', "")
        }
    }

    fun parse(text: String, verify: (ByteArray, ByteArray, ByteArray) -> Boolean): Parse {
        val code = codeOf(text)
        if (!isLink(text) || code.isEmpty()) return Parse.NotALink
        val raw = runCatching { Base64.decode(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()
            ?: return Parse.Invalid
        return runCatching {
            if (raw.size < 64 + 1 + 3 + 88) return Parse.Invalid
            val unsigned = raw.copyOfRange(0, raw.size - 64)
            val sig = raw.copyOfRange(raw.size - 64, raw.size)
            val bb = ByteBuffer.wrap(unsigned).order(ByteOrder.BIG_ENDIAN)
            if (bb.get() != VERSION) return Parse.Invalid
            fun str(): String { val n = bb.get().toInt() and 0xFF; return ByteArray(n).also { bb.get(it) }.toString(Charsets.UTF_8) }
            val group = str()
            val inviter = str()
            val count = bb.get().toInt() and 0xFF
            val members = (0 until count.coerceAtMost(12)).map { str() }
            val sigKey = ByteArray(32).also { bb.get(it) }
            val token = ByteArray(16).also { bb.get(it) }
            val pub = ByteArray(32).also { bb.get(it) }
            val exp = bb.long
            if (bb.hasRemaining()) return Parse.Invalid
            if (!verify(sig, unsigned, sigKey)) return Parse.Invalid
            if (System.currentTimeMillis() / 1000 > exp) return Parse.Expired
            Parse.Ok(LinkInvite(group, inviter, members, sigKey, token, pub, exp, code))
        }.getOrElse { Parse.Invalid }
    }

    // ------------------------------------------------------------ Aceptar (quien recibe el enlace)

    /** Construye la petición cifrada para quien invita y la deja pendiente de respuesta. */
    fun buildRequest(invite: LinkInvite, mySigKey: ByteArray, myName: String): Pair<ByteArray, String> {
        val ePriv = X25519PrivateKeyParameters(random)
        val ePub = ePriv.generatePublicKey().encoded
        val shared = agree(ePriv, invite.invitePub)
        val name = myName.toByteArray(Charsets.UTF_8).let { if (it.size > 32) it.copyOf(32) else it }
        val plain = mySigKey + byteArrayOf(name.size.toByte()) + name
        val tag = requestTag(invite.token)
        val header = MAGIC_REQUEST + byteArrayOf(VERSION) + tag
        val sealed = XChaCha20Poly1305(kdf("faro-join-req-v1", shared, invite.token)).encrypt(plain, header)
        val payload = header + ePub + sealed

        // Recuerdo cómo descifrar la respuesta.
        val pending = JSONObject()
            .put("token", invite.token.toHex())
            .put("shared", shared.toHex())
            .put("name", invite.groupName)
            .put("payload", b64(payload))
        prefs?.edit()?.putString("req_" + replyTag(invite.token).toHex(), pending.toString())?.apply()
        refreshTags()
        return payload to tag.toHex()
    }

    // ------------------------------------------------------------ Recepción

    sealed class Incoming {
        /** Alguien ha aceptado mi enlace: hay que mandarle la clave. */
        data class Request(val groupId: String, val memberKey: ByteArray, val memberName: String, val reply: ByteArray, val replyTagHex: String) : Incoming()
        /** Me han mandado la clave del grupo que acepté. */
        data class Granted(val groupId: ByteArray, val groupKey: ByteArray, val groupName: String) : Incoming()
    }

    fun isJoinPacket(payload: ByteArray): Boolean =
        payload.size > 3 && payload[0] == 'F'.code.toByte() && (payload[1] == 'J'.code.toByte() || payload[1] == 'K'.code.toByte())

    /**
     * Procesa una petición o respuesta. [groupFor] devuelve (id, clave, nombre) de un grupo mío.
     */
    fun handle(payload: ByteArray, groupFor: (String) -> Triple<ByteArray, ByteArray, String>?): Incoming? = runCatching {
        if (payload.size < 3 + 8 || payload[2] != VERSION) return null
        val tag = payload.copyOfRange(3, 11)
        val header = payload.copyOfRange(0, 11)
        val p = prefs ?: return null
        if (payload[1] == 'J'.code.toByte()) {
            // Petición a uno de mis enlaces.
            val entry = p.all.entries.firstOrNull { (k, _) ->
                k.startsWith("inv_") && requestTag(k.removePrefix("inv_").hexToBytes() ?: return@firstOrNull false).contentEquals(tag)
            } ?: return null
            val token = entry.key.removePrefix("inv_").hexToBytes() ?: return null
            val info = JSONObject(entry.value as String)
            if (System.currentTimeMillis() / 1000 > info.getLong("exp")) {
                p.edit().remove(entry.key).apply(); refreshTags(); return null
            }
            val priv = X25519PrivateKeyParameters(info.getString("priv").hexToBytes()!!, 0)
            val ePub = payload.copyOfRange(11, 43)
            val shared = agree(priv, ePub)
            val plain = XChaCha20Poly1305(kdf("faro-join-req-v1", shared, token)).decrypt(payload.copyOfRange(43, payload.size), header)
            val memberKey = plain.copyOfRange(0, 32)
            val n = plain[32].toInt() and 0xFF
            val memberName = plain.copyOfRange(33, 33 + n).toString(Charsets.UTF_8)
            val groupId = info.getString("group")
            val (gid, gkey, gname) = groupFor(groupId) ?: return null
            // Un solo uso: borro el código.
            p.edit().remove(entry.key).apply()
            refreshTags()
            val gn = gname.toByteArray(Charsets.UTF_8).let { if (it.size > 48) it.copyOf(48) else it }
            val replyPlain = gid + gkey + byteArrayOf(gn.size.toByte()) + gn
            val rTag = replyTag(token)
            val rHeader = MAGIC_REPLY + byteArrayOf(VERSION) + rTag
            val reply = rHeader + XChaCha20Poly1305(kdf("faro-join-reply-v1", shared, token)).encrypt(replyPlain, rHeader)
            Incoming.Request(groupId, memberKey, memberName, reply, rTag.toHex())
        } else {
            // Respuesta a una petición mía.
            val key = "req_" + tag.toHex()
            val info = p.getString(key, null)?.let(::JSONObject) ?: return null
            val token = info.getString("token").hexToBytes()!!
            val shared = info.getString("shared").hexToBytes()!!
            val plain = XChaCha20Poly1305(kdf("faro-join-reply-v1", shared, token)).decrypt(payload.copyOfRange(11, payload.size), header)
            val gid = plain.copyOfRange(0, 16)
            val gkey = plain.copyOfRange(16, 48)
            val n = plain[48].toInt() and 0xFF
            val name = plain.copyOfRange(49, 49 + n).toString(Charsets.UTF_8)
            p.edit().remove(key).apply()
            refreshTags()
            Incoming.Granted(gid, gkey, name)
        }
    }.getOrNull()

    private fun refreshTags() {
        val p = prefs ?: return
        val now = System.currentTimeMillis() / 1000
        val tags = mutableListOf<String>()
        val waiting = mutableListOf<String>()
        p.all.forEach { (k, v) ->
            when {
                k.startsWith("inv_") -> {
                    val exp = runCatching { JSONObject(v as String).getLong("exp") }.getOrDefault(0L)
                    if (exp > now) k.removePrefix("inv_").hexToBytes()?.let { tags += requestTag(it).toHex() }
                }
                k.startsWith("req_") -> {
                    tags += k.removePrefix("req_")
                    runCatching { JSONObject(v as String).getString("name") }.getOrNull()?.let { waiting += it }
                }
            }
        }
        _listenTags.value = tags.sorted()
        _waiting.value = waiting
    }

    private fun requestTag(token: ByteArray) = sha256("faro-join-req-tag".toByteArray() + token).copyOf(8)
    private fun replyTag(token: ByteArray) = sha256("faro-join-reply-tag".toByteArray() + token).copyOf(8)

    private fun agree(priv: X25519PrivateKeyParameters, pub: ByteArray): ByteArray {
        val out = ByteArray(32)
        X25519Agreement().apply { init(priv) }.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
        return out
    }

    private fun kdf(label: String, shared: ByteArray, token: ByteArray) = sha256(label.toByteArray() + shared + token)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Peticiones enviadas que aún esperan respuesta (para reenviarlas). */
    fun pendingRequests(): List<ByteArray> = prefs?.all.orEmpty().filterKeys { it.startsWith("req_") }.values.mapNotNull { v ->
        runCatching {
            Base64.decode(JSONObject(v as String).getString("payload"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()
    }

    /** Etiqueta (hex) con la que publicar un paquete de unión por internet. */
    fun tagOf(payload: ByteArray): String = payload.copyOfRange(3, 11).toHex()
}
