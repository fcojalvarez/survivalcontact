package com.bitchat.android.faro

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Formato de los paquetes de grupo de Faro.
 *
 * Viajan por la malla como paquetes de difusión con un tipo propio ([PACKET_TYPE]); los móviles
 * con bitchat o Faro que no son del grupo los reenvían sin poder leerlos (solo ven una etiqueta
 * de 8 bytes que no revela el grupo).
 *
 * Exterior (lo que ve cualquiera):
 *   "FR" (2) · versión (1) · etiqueta (8) · AEAD XChaCha20-Poly1305 (nonce 24 + cifrado + 16)
 *   El AEAD autentica también "FR" + versión + etiqueta.
 *
 * Interior (solo miembros, con la clave del grupo):
 *   tipo (1) · hora en ms (8) · clave pública Ed25519 del autor (32) · longitud del nombre (1) ·
 *   nombre UTF-8 · cuerpo · firma Ed25519 (64) sobre todo lo anterior.
 *
 * La firma impide que un miembro se haga pasar por otro. No se inventa criptografía: se usan
 * XChaCha20-Poly1305 (Google Tink) y Ed25519 (BouncyCastle), ya presentes en la app.
 */
object FaroProtocol {
    /** Tipo de paquete en la malla. Elegido fuera de los valores que usa bitchat. */
    const val PACKET_TYPE: UByte = 0xFAu

    private val MAGIC = byteArrayOf('F'.code.toByte(), 'R'.code.toByte())
    private const val VERSION: Byte = 1
    const val TAG_SIZE = 8
    const val KEY_SIZE = 32
    const val GROUP_ID_SIZE = 16
    private const val SIG_SIZE = 64
    private const val PUB_SIZE = 32
    private const val MAX_NAME_BYTES = 48
    private const val HEADER_SIZE = 2 + 1 + TAG_SIZE

    enum class Kind(val code: Byte) {
        TEXT(1),
        LOCATION(2),
        HELLO(3),
        /** Pide a una persona que envíe su ubicación cada 30 s durante unos minutos. */
        FOLLOW(4),
        /** Aviso de que alguien ha mirado dónde están unas personas (solo por internet). */
        SEEN(5);

        companion object {
            fun from(code: Byte): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    data class Inner(
        val kind: Kind,
        val timestampMs: Long,
        val authorKey: ByteArray,
        val authorName: String,
        val body: ByteArray,
    ) {
        val authorId: String get() = authorKey.toHex()
    }

    /** Posición compacta: 12 bytes. */
    data class Position(
        val lat: Double,
        val lon: Double,
        val accuracyM: Int,
        val batteryPct: Int,
        val status: Status,
    ) {
        enum class Status(val code: Byte) {
            OK(0), NEED_HELP(1), ON_THE_WAY(2);

            companion object {
                fun from(code: Byte) = entries.firstOrNull { it.code == code } ?: OK
            }
        }

        fun encode(): ByteArray = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(Math.round(lat * 1e7).toInt())
            putInt(Math.round(lon * 1e7).toInt())
            putShort(accuracyM.coerceIn(0, 65535).toShort())
            put(batteryPct.coerceIn(0, 100).toByte())
            put(status.code)
        }.array()

        companion object {
            fun decode(b: ByteArray): Position? {
                if (b.size < 12) return null
                val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
                val lat = bb.int / 1e7
                val lon = bb.int / 1e7
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                return Position(
                    lat = lat,
                    lon = lon,
                    accuracyM = bb.short.toInt() and 0xFFFF,
                    batteryPct = bb.get().toInt().coerceIn(0, 100),
                    status = Status.from(bb.get()),
                )
            }
        }
    }

    private val random = SecureRandom()

    fun newGroupId(): ByteArray = ByteArray(GROUP_ID_SIZE).also(random::nextBytes)
    fun newGroupKey(): ByteArray = ByteArray(KEY_SIZE).also(random::nextBytes)

    /**
     * Etiqueta del grupo en la malla. Deriva de la clave (no solo del id), así que quien no
     * tiene la clave no puede ni reconocer los paquetes del grupo.
     */
    fun tagFor(groupKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("faro-group-tag-v1".toByteArray() + groupKey)
            .copyOf(TAG_SIZE)

    fun seal(
        groupKey: ByteArray,
        inner: Inner,
        sign: (ByteArray) -> ByteArray?,
    ): ByteArray? {
        val nameBytes = inner.authorName.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_NAME_BYTES) it.copyOf(MAX_NAME_BYTES) else it
        }
        require(inner.authorKey.size == PUB_SIZE)
        val unsigned = ByteBuffer.allocate(1 + 8 + PUB_SIZE + 1 + nameBytes.size + inner.body.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(inner.kind.code)
            .putLong(inner.timestampMs)
            .put(inner.authorKey)
            .put(nameBytes.size.toByte())
            .put(nameBytes)
            .put(inner.body)
            .array()
        val signature = sign(unsigned) ?: return null
        if (signature.size != SIG_SIZE) return null
        val header = MAGIC + byteArrayOf(VERSION) + tagFor(groupKey)
        val sealed = XChaCha20Poly1305(groupKey).encrypt(unsigned + signature, header)
        return header + sealed
    }

    /** Devuelve la etiqueta del paquete, o null si no es un paquete de Faro. */
    fun peekTag(payload: ByteArray): ByteArray? {
        if (payload.size < HEADER_SIZE) return null
        if (payload[0] != MAGIC[0] || payload[1] != MAGIC[1] || payload[2] != VERSION) return null
        return payload.copyOfRange(3, HEADER_SIZE)
    }

    /**
     * Descifra y verifica. Devuelve null si la clave no es la del grupo, el paquete está
     * manipulado o la firma no es válida.
     */
    fun open(
        groupKey: ByteArray,
        payload: ByteArray,
        verify: (signature: ByteArray, data: ByteArray, publicKey: ByteArray) -> Boolean,
    ): Inner? = runCatching {
        val tag = peekTag(payload) ?: return null
        if (!tag.contentEquals(tagFor(groupKey))) return null
        val header = payload.copyOfRange(0, HEADER_SIZE)
        val plain = XChaCha20Poly1305(groupKey).decrypt(payload.copyOfRange(HEADER_SIZE, payload.size), header)
        if (plain.size < 1 + 8 + PUB_SIZE + 1 + SIG_SIZE) return null
        val unsigned = plain.copyOfRange(0, plain.size - SIG_SIZE)
        val signature = plain.copyOfRange(plain.size - SIG_SIZE, plain.size)
        val bb = ByteBuffer.wrap(unsigned).order(ByteOrder.BIG_ENDIAN)
        val kind = Kind.from(bb.get()) ?: return null
        val ts = bb.long
        val author = ByteArray(PUB_SIZE).also { bb.get(it) }
        val nameLen = bb.get().toInt() and 0xFF
        if (nameLen > MAX_NAME_BYTES || bb.remaining() < nameLen) return null
        val name = ByteArray(nameLen).also { bb.get(it) }.toString(Charsets.UTF_8)
        val body = ByteArray(bb.remaining()).also { bb.get(it) }
        if (!verify(signature, unsigned, author)) return null
        Inner(kind, ts, author, name, body)
    }.getOrNull()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()

    // ---------- Cuerpos de FOLLOW y SEEN ----------

    /** FOLLOW: clave de la persona (32 bytes) + minutos (0 = parar). */
    fun encodeFollow(target: ByteArray, minutes: Int): ByteArray {
        require(target.size == PUB_SIZE)
        return target + byteArrayOf(minutes.coerceIn(0, 10).toByte())
    }

    fun decodeFollow(body: ByteArray): Pair<ByteArray, Int>? {
        if (body.size != PUB_SIZE + 1) return null
        return body.copyOfRange(0, PUB_SIZE) to (body[PUB_SIZE].toInt() and 0xFF).coerceIn(0, 10)
    }

    /** Cómo se ha mirado: en el mapa, en la ficha, en directo o con la brújula. */
    enum class SeenHow(val code: Byte) {
        MAP(0), CARD(1), LIVE(2), GUIDE(3);
        companion object { fun from(code: Byte) = entries.firstOrNull { it.code == code } ?: MAP }
    }

    /** SEEN: cómo (1 byte) + claves de las personas vistas (32 bytes cada una, máximo 50). */
    fun encodeSeen(how: SeenHow, targets: List<ByteArray>): ByteArray {
        val list = targets.filter { it.size == PUB_SIZE }.take(50)
        return byteArrayOf(how.code) + list.fold(ByteArray(0)) { acc, k -> acc + k }
    }

    fun decodeSeen(body: ByteArray): Pair<SeenHow, List<ByteArray>>? {
        if (body.isEmpty() || (body.size - 1) % PUB_SIZE != 0 || (body.size - 1) / PUB_SIZE > 50) return null
        val keys = (0 until (body.size - 1) / PUB_SIZE).map { i -> body.copyOfRange(1 + i * PUB_SIZE, 1 + (i + 1) * PUB_SIZE) }
        return SeenHow.from(body[0]) to keys
    }
}
