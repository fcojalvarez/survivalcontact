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
        SEEN(5),
        /** «Estoy bien», «¿Estás bien?» y sus respuestas. */
        CHECK(6),
        /** Un lugar del grupo (Casa, Cole…): crear, cambiar o borrar. */
        PLACE(7),
        /** Alguien ha llegado a un lugar o ha salido de él. */
        ARRIVE(8),
        /** «Voy para allá»: destino y hora de llegada, o fin del viaje. */
        TRIP(9);

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
            OK(0), NEED_HELP(1), ON_THE_WAY(2),
            /** Última ubicación antes de quedarse sin batería (al bajar del 5 %). */
            BATTERY_DYING(3);

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
    fun tagFor(groupKey: ByteArray, day: Long = currentDay()): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("faro-group-tag-v2".toByteArray() + groupKey + ByteBuffer.allocate(8).putLong(day).array())
            .copyOf(TAG_SIZE)

    /** Día actual (UTC). La etiqueta cambia cada día para que nadie pueda seguir a un grupo. */
    fun currentDay(nowMs: Long = System.currentTimeMillis()): Long = Math.floorDiv(nowMs, 86_400_000L)

    /** Etiquetas válidas hoy: ayer, hoy y mañana, por si los relojes no coinciden. */
    fun tagsAround(groupKey: ByteArray, day: Long = currentDay()): List<ByteArray> =
        listOf(day - 1, day, day + 1).map { tagFor(groupKey, it) }

    fun matchesGroup(groupKey: ByteArray, tag: ByteArray): Boolean =
        tagsAround(groupKey).any { it.contentEquals(tag) }

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
        if (!matchesGroup(groupKey, tag)) return null
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

    // ---------- Familia: CHECK, PLACE, ARRIVE, TRIP ----------

    enum class Check(val code: Byte) {
        IM_OK(0), ASK(1), ANSWER_OK(2), ANSWER_HELP(3);
        companion object { fun from(code: Byte) = entries.firstOrNull { it.code == code } }
    }

    /** CHECK: tipo (1 byte) + clave de la persona a la que va (32 bytes, ceros si es para todos). */
    fun encodeCheck(type: Check, target: ByteArray? = null): ByteArray =
        byteArrayOf(type.code) + (target?.takeIf { it.size == PUB_SIZE } ?: ByteArray(PUB_SIZE))

    fun decodeCheck(body: ByteArray): Pair<Check, ByteArray?>? {
        if (body.size != 1 + PUB_SIZE) return null
        val type = Check.from(body[0]) ?: return null
        val t = body.copyOfRange(1, body.size)
        return type to (if (t.all { it == 0.toByte() }) null else t)
    }

    data class Place(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Int)

    private fun nameBytes(s: String, max: Int) = s.trim().toByteArray(Charsets.UTF_8).let { if (it.size > max) it.copyOf(max) else it }

    /** PLACE: borrar (1 byte) + id (8) + lat, lon (8+8) + radio (2) + nombre. */
    fun encodePlace(p: Place, delete: Boolean = false): ByteArray {
        val id = p.id.hexToBytes()?.takeIf { it.size == 8 } ?: ByteArray(8)
        val n = nameBytes(p.name, 40)
        return ByteBuffer.allocate(1 + 8 + 16 + 2 + 1 + n.size).order(ByteOrder.BIG_ENDIAN)
            .put(if (delete) 1 else 0).put(id).putDouble(p.lat).putDouble(p.lon)
            .putShort(p.radiusM.coerceIn(50, 2000).toShort()).put(n.size.toByte()).put(n).array()
    }

    fun decodePlace(body: ByteArray): Pair<Place, Boolean>? = runCatching {
        val bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val del = bb.get() == 1.toByte()
        val id = ByteArray(8).also { bb.get(it) }.toHex()
        val lat = bb.double; val lon = bb.double
        val radius = bb.short.toInt() and 0xFFFF
        val n = bb.get().toInt() and 0xFF
        if (n > 40 || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val name = ByteArray(n).also { bb.get(it) }.toString(Charsets.UTF_8)
        Place(id, name, lat, lon, radius.coerceIn(50, 2000)) to del
    }.getOrNull()

    fun newPlaceId(): String = ByteArray(8).also(random::nextBytes).toHex()

    /** ARRIVE: id del lugar (8) + 1 si llega, 0 si sale. */
    fun encodeArrive(placeId: String, enter: Boolean): ByteArray =
        (placeId.hexToBytes()?.takeIf { it.size == 8 } ?: ByteArray(8)) + byteArrayOf(if (enter) 1 else 0)

    fun decodeArrive(body: ByteArray): Pair<String, Boolean>? =
        if (body.size != 9) null else body.copyOfRange(0, 8).toHex() to (body[8] == 1.toByte())

    enum class TravelMode(val code: Byte, val kmh: Double) {
        WALK(0, 4.5), CAR(1, 30.0), BUS(2, 18.0);
        companion object { fun from(code: Byte) = entries.firstOrNull { it.code == code } ?: WALK }
    }

    data class Trip(val active: Boolean, val destName: String, val lat: Double, val lon: Double, val etaMs: Long, val mode: TravelMode)

    /** TRIP: activo (1) + modo (1) + lat, lon (16) + llegada en ms (8) + nombre del destino. */
    fun encodeTrip(t: Trip): ByteArray {
        val n = nameBytes(t.destName, 40)
        return ByteBuffer.allocate(2 + 16 + 8 + 1 + n.size).order(ByteOrder.BIG_ENDIAN)
            .put(if (t.active) 1 else 0).put(t.mode.code).putDouble(t.lat).putDouble(t.lon)
            .putLong(t.etaMs).put(n.size.toByte()).put(n).array()
    }

    fun decodeTrip(body: ByteArray): Trip? = runCatching {
        val bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val active = bb.get() == 1.toByte()
        val mode = TravelMode.from(bb.get())
        val lat = bb.double; val lon = bb.double; val eta = bb.long
        val n = bb.get().toInt() and 0xFF
        if (n > 40) return null
        Trip(active, ByteArray(n).also { bb.get(it) }.toString(Charsets.UTF_8), lat, lon, eta, mode)
    }.getOrNull()

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

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
