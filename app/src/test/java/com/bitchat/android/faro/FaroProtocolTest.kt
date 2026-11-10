package com.bitchat.android.faro

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class FaroProtocolTest {

    private class Identity {
        val priv: Ed25519PrivateKeyParameters
        val pub: ByteArray

        init {
            val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
            val kp = gen.generateKeyPair()
            priv = kp.private as Ed25519PrivateKeyParameters
            pub = (kp.public as Ed25519PublicKeyParameters).encoded
        }

        fun sign(data: ByteArray): ByteArray = Ed25519Signer().run {
            init(true, priv); update(data, 0, data.size); generateSignature()
        }
    }

    private fun verify(sig: ByteArray, data: ByteArray, pub: ByteArray): Boolean = runCatching {
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(pub, 0)); update(data, 0, data.size); verifySignature(sig)
        }
    }.getOrDefault(false)

    private val alice = Identity()
    private val key = FaroProtocol.newGroupKey()

    private fun sealText(text: String, k: ByteArray = key, who: Identity = alice): ByteArray =
        FaroProtocol.seal(
            k,
            FaroProtocol.Inner(FaroProtocol.Kind.TEXT, 1_700_000_000_000, who.pub, "Mamá", text.toByteArray()),
            who::sign
        )!!

    @Test
    fun `un miembro con la clave lee el mensaje`() {
        val inner = FaroProtocol.open(key, sealText("¿Estáis bien?"), ::verify)
        assertNotNull(inner)
        assertEquals("¿Estáis bien?", inner!!.body.toString(Charsets.UTF_8))
        assertEquals("Mamá", inner.authorName)
        assertArrayEquals(alice.pub, inner.authorKey)
    }

    @Test
    fun `sin la clave del grupo no se puede leer`() {
        assertNull(FaroProtocol.open(FaroProtocol.newGroupKey(), sealText("secreto"), ::verify))
    }

    @Test
    fun `el texto no aparece en claro en el paquete`() {
        val payload = sealText("contraseña-del-wifi")
        assertTrue(!String(payload, Charsets.ISO_8859_1).contains("contraseña"))
    }

    @Test
    fun `un paquete manipulado se rechaza`() {
        val payload = sealText("hola")
        payload[payload.size - 5] = (payload[payload.size - 5].toInt() xor 1).toByte()
        assertNull(FaroProtocol.open(key, payload, ::verify))
    }

    @Test
    fun `un miembro no puede hacerse pasar por otro`() {
        val mallory = Identity()
        // Mallory firma con su clave pero pone la clave pública de Alice como autora.
        val forged = FaroProtocol.seal(
            key,
            FaroProtocol.Inner(FaroProtocol.Kind.TEXT, 1L, alice.pub, "Mamá", "ven ya".toByteArray()),
            mallory::sign
        )!!
        assertNull(FaroProtocol.open(key, forged, ::verify))
    }

    @Test
    fun `la etiqueta no revela el grupo sin la clave`() {
        val payload = sealText("x")
        assertArrayEquals(FaroProtocol.tagFor(key), FaroProtocol.peekTag(payload))
        assertTrue(!FaroProtocol.tagFor(FaroProtocol.newGroupKey()).contentEquals(FaroProtocol.peekTag(payload)))
    }

    @Test
    fun `la posicion ocupa 12 bytes y conserva la precision`() {
        val p = FaroProtocol.Position(36.7213028, -4.4216366, 12, 64, FaroProtocol.Position.Status.ON_THE_WAY)
        val enc = p.encode()
        assertEquals(12, enc.size)
        val d = FaroProtocol.Position.decode(enc)!!
        assertEquals(p.lat, d.lat, 1e-6)
        assertEquals(p.lon, d.lon, 1e-6)
        assertEquals(12, d.accuracyM)
        assertEquals(64, d.batteryPct)
        assertEquals(FaroProtocol.Position.Status.ON_THE_WAY, d.status)
    }

    @Test
    fun `seguir en directo y aviso de visto se codifican y validan`() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val (t, m) = FaroProtocol.decodeFollow(FaroProtocol.encodeFollow(a, 10))!!
        assertArrayEquals(a, t)
        assertEquals(10, m)
        // Nunca más de 10 minutos, aunque lo pidan.
        assertEquals(10, FaroProtocol.decodeFollow(FaroProtocol.encodeFollow(a, 60))!!.second)
        assertNull(FaroProtocol.decodeFollow(ByteArray(5)))

        val (how, keys) = FaroProtocol.decodeSeen(FaroProtocol.encodeSeen(FaroProtocol.SeenHow.LIVE, listOf(a, b)))!!
        assertEquals(FaroProtocol.SeenHow.LIVE, how)
        assertEquals(2, keys.size)
        assertArrayEquals(b, keys[1])
        assertNull(FaroProtocol.decodeSeen(ByteArray(10)))
    }

    @Test
    fun `la etiqueta del grupo cambia cada dia y se acepta la de ayer y la de manana`() {
        val k = FaroProtocol.newGroupKey()
        val hoy = FaroProtocol.currentDay()
        assertTrue(!FaroProtocol.tagFor(k, hoy).contentEquals(FaroProtocol.tagFor(k, hoy + 1)))
        assertTrue(FaroProtocol.matchesGroup(k, FaroProtocol.tagFor(k, hoy - 1)))
        assertTrue(FaroProtocol.matchesGroup(k, FaroProtocol.tagFor(k, hoy + 1)))
        assertTrue(!FaroProtocol.matchesGroup(k, FaroProtocol.tagFor(k, hoy - 2)))
        assertTrue(!FaroProtocol.matchesGroup(FaroProtocol.newGroupKey(), FaroProtocol.tagFor(k, hoy)))
    }

    @Test
    fun `mensajes de familia se codifican y validan`() {
        val k = ByteArray(32) { 7 }
        val (type, target) = FaroProtocol.decodeCheck(FaroProtocol.encodeCheck(FaroProtocol.Check.ASK, k))!!
        assertEquals(FaroProtocol.Check.ASK, type)
        assertArrayEquals(k, target)
        assertNull(FaroProtocol.decodeCheck(FaroProtocol.encodeCheck(FaroProtocol.Check.IM_OK))!!.second)

        // Lugar con coordenadas sintéticas.
        val place = FaroProtocol.Place(FaroProtocol.newPlaceId(), "Casa", 10.5, -20.25, 200)
        val (p2, del) = FaroProtocol.decodePlace(FaroProtocol.encodePlace(place))!!
        assertEquals(place, p2)
        assertTrue(!del)
        assertTrue(FaroProtocol.decodePlace(FaroProtocol.encodePlace(place, delete = true))!!.second)

        val (id, enter) = FaroProtocol.decodeArrive(FaroProtocol.encodeArrive(place.id, true))!!
        assertEquals(place.id, id)
        assertTrue(enter)

        val trip = FaroProtocol.Trip(true, "Casa", 10.5, -20.25, 1_700_000_000_000L, FaroProtocol.TravelMode.BUS)
        assertEquals(trip, FaroProtocol.decodeTrip(FaroProtocol.encodeTrip(trip)))
        assertNull(FaroProtocol.decodeTrip(ByteArray(3)))
    }
}
