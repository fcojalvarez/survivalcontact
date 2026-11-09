package com.bitchat.android.faro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class FaroJoinTest {

    private class Identity {
        val priv: Ed25519PrivateKeyParameters
        val pub: ByteArray

        init {
            val kp = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
            priv = kp.private as Ed25519PrivateKeyParameters
            pub = (kp.public as Ed25519PublicKeyParameters).encoded
        }

        fun sign(data: ByteArray): ByteArray = Ed25519Signer().run { init(true, priv); update(data, 0, data.size); generateSignature() }
    }

    private fun verify(sig: ByteArray, data: ByteArray, pub: ByteArray): Boolean = runCatching {
        Ed25519Signer().run { init(false, Ed25519PublicKeyParameters(pub, 0)); update(data, 0, data.size); verifySignature(sig) }
    }.getOrDefault(false)

    @Test
    fun `el enlace no lleva la clave y da acceso una sola vez`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("faro_join_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        FaroJoin.attach(prefs)

        val mama = Identity()
        val pablo = Identity()
        val groupId = FaroProtocol.newGroupId()
        val groupKey = FaroProtocol.newGroupKey()

        val link = FaroJoin.createLink(groupId.toHex(), "Familia", "Mamá", listOf("Mamá", "Lucía"), mama.pub, mama::sign)!!
        assertTrue(link.startsWith(FaroJoin.LINK_BASE))
        assertTrue("la clave no puede ir en el enlace", !link.contains(android.util.Base64.encodeToString(groupKey, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)))

        val parsed = FaroJoin.parse(link, ::verify)
        assertTrue(parsed is FaroJoin.Parse.Ok)
        val invite = (parsed as FaroJoin.Parse.Ok).invite
        assertEquals("Familia", invite.groupName)
        assertEquals("Mamá", invite.inviterName)
        assertEquals(listOf("Mamá", "Lucía"), invite.members)

        // El mismo código, abierto desde la web en la app (faro://unirse?c=…).
        val fromWeb = FaroJoin.parse("faro://unirse?c=" + link.substringAfter('#'), ::verify)
        assertTrue(fromWeb is FaroJoin.Parse.Ok)

        val (request, _) = FaroJoin.buildRequest(invite, pablo.pub, "Pablo")
        val groupFor: (String) -> Triple<ByteArray, ByteArray, String>? = { id ->
            if (id == groupId.toHex()) Triple(groupId, groupKey, "Familia") else null
        }
        val incoming = FaroJoin.handle(request, groupFor)
        assertTrue(incoming is FaroJoin.Incoming.Request)
        incoming as FaroJoin.Incoming.Request
        assertEquals("Pablo", incoming.memberName)
        assertArrayEquals(pablo.pub, incoming.memberKey)

        val granted = FaroJoin.handle(incoming.reply, groupFor)
        assertTrue(granted is FaroJoin.Incoming.Granted)
        granted as FaroJoin.Incoming.Granted
        assertArrayEquals(groupKey, granted.groupKey)
        assertArrayEquals(groupId, granted.groupId)

        // Un solo uso: la misma petición ya no vale.
        assertNull(FaroJoin.handle(request, groupFor))
    }

    @Test
    fun `un enlace manipulado se rechaza`() {
        val mama = Identity()
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("faro_join_test2", Context.MODE_PRIVATE)
        FaroJoin.attach(prefs)
        val link = FaroJoin.createLink("00", "Familia", "Mamá", emptyList(), mama.pub, mama::sign)!!
        val tampered = link.replace("AQdGYW1pbGlh", "AQdGYW1pbGlo") // cambia una letra de «Familia»
        assertTrue(tampered != link)
        assertTrue(FaroJoin.parse(tampered, ::verify) is FaroJoin.Parse.Invalid)
    }
}
