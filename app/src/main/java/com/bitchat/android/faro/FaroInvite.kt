package com.bitchat.android.faro

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Invitación en persona por QR.
 *
 * Escanear el QR de alguien en persona ya es el consentimiento, así que lleva la clave del
 * grupo y la entrada es inmediata. Por eso la app solo lo muestra en pantalla (nunca lo
 * comparte como imagen o texto) y avisa de que solo se enseñe a quien se quiere invitar.
 *
 * Formato: "faro:g1:" + Base64URL( id(16) · clave(32) · lonNombre(1) · nombre ·
 *          clave pública del que invita(32) · caducidad en s(8) · firma Ed25519(64) )
 */
object FaroInvite {
    private const val PREFIX = "faro:g1:"
    private const val VALID_FOR_S = 15 * 60L

    data class Invite(
        val groupId: ByteArray,
        val groupKey: ByteArray,
        val groupName: String,
        val inviterKey: ByteArray,
        val expiresAtS: Long,
    )

    fun encode(
        groupId: ByteArray,
        groupKey: ByteArray,
        groupName: String,
        inviterKey: ByteArray,
        nowS: Long,
        sign: (ByteArray) -> ByteArray?,
    ): String? {
        val name = groupName.toByteArray(Charsets.UTF_8).let { if (it.size > 48) it.copyOf(48) else it }
        val unsigned = ByteBuffer.allocate(16 + 32 + 1 + name.size + 32 + 8).order(ByteOrder.BIG_ENDIAN)
            .put(groupId).put(groupKey).put(name.size.toByte()).put(name).put(inviterKey)
            .putLong(nowS + VALID_FOR_S)
            .array()
        val sig = sign(unsigned) ?: return null
        return PREFIX + Base64.encodeToString(unsigned + sig, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    sealed class Result {
        data class Ok(val invite: Invite) : Result()
        object NotFaro : Result()
        object Invalid : Result()
        object Expired : Result()
    }

    fun decode(
        text: String,
        nowS: Long,
        verify: (signature: ByteArray, data: ByteArray, publicKey: ByteArray) -> Boolean,
    ): Result {
        if (!text.startsWith(PREFIX)) return Result.NotFaro
        val raw = runCatching {
            Base64.decode(text.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return Result.Invalid
        return runCatching {
            if (raw.size < 16 + 32 + 1 + 32 + 8 + 64) return Result.Invalid
            val unsigned = raw.copyOfRange(0, raw.size - 64)
            val sig = raw.copyOfRange(raw.size - 64, raw.size)
            val bb = ByteBuffer.wrap(unsigned).order(ByteOrder.BIG_ENDIAN)
            val id = ByteArray(16).also { bb.get(it) }
            val key = ByteArray(32).also { bb.get(it) }
            val nameLen = bb.get().toInt() and 0xFF
            if (nameLen > 48 || bb.remaining() != nameLen + 32 + 8) return Result.Invalid
            val name = ByteArray(nameLen).also { bb.get(it) }.toString(Charsets.UTF_8)
            val inviter = ByteArray(32).also { bb.get(it) }
            val expires = bb.long
            if (!verify(sig, unsigned, inviter)) return Result.Invalid
            if (nowS > expires) return Result.Expired
            Result.Ok(Invite(id, key, name, inviter, expires))
        }.getOrElse { Result.Invalid }
    }
}
