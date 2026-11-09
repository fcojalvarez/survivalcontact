package com.bitchat.android.faro.ui

import com.bitchat.android.faro.FaroGroup
import com.bitchat.android.faro.FaroProtocol
import com.bitchat.android.faro.MemberPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lógica de Inicio: juntar a las personas de varios grupos sin repetirlas. Datos sintéticos. */
class FaroPeopleTest {
    private val now = System.currentTimeMillis()

    private fun pos(status: FaroProtocol.Position.Status = FaroProtocol.Position.Status.OK) =
        FaroProtocol.Position(lat = 10.0, lon = 20.0, accuracyM = 5, batteryPct = 50, status = status)

    @Test
    fun `una persona en dos grupos aparece una vez con su posicion mas reciente`() {
        val a = FaroGroup("a", "Familia", "00", createdAtMs = 1, members = mapOf("p1" to "Ana", "yo" to "Yo"))
        val b = FaroGroup("b", "Primos", "00", createdAtMs = 2, members = mapOf("p1" to "Ana", "p2" to "Luis"))
        val positions = mapOf(
            "a" to mapOf("p1" to MemberPosition("p1", "Ana", pos(), now - 10 * HOUR)),
            "b" to mapOf("p1" to MemberPosition("p1", "Ana", pos(), now - 60_000)),
        )
        val ppl = people(listOf(a, b), positions, myId = "yo")
        assertEquals(listOf("p1", "p2"), ppl.map { it.id })
        val ana = ppl.first()
        assertEquals(listOf("a", "b"), ana.groupIds)
        assertEquals(now - 60_000, ana.pos!!.timestampMs)
        assertFalse(ana.silent)
        assertNull(ppl[1].pos)
    }

    @Test
    fun `sin senales a partir de 3 horas y ayuda solo si es reciente`() {
        val g = FaroGroup("a", "Familia", "00", createdAtMs = 1)
        val positions = mapOf(
            "a" to mapOf(
                "p1" to MemberPosition("p1", "Ana", pos(), now - 4 * HOUR),
                "p2" to MemberPosition("p2", "Luis", pos(FaroProtocol.Position.Status.NEED_HELP), now - HOUR),
                "p3" to MemberPosition("p3", "Eva", pos(FaroProtocol.Position.Status.NEED_HELP), now - 13 * HOUR),
            )
        )
        val byId = people(listOf(g), positions, myId = null).associateBy { it.id }
        assertTrue(byId.getValue("p1").silent)
        assertTrue(byId.getValue("p2").needsHelp)
        assertFalse(byId.getValue("p3").needsHelp)
    }

    @Test
    fun `rumbo en palabras`() {
        assertEquals("norte", bearingWord(0f))
        assertEquals("noreste", bearingWord(40f))
        assertEquals("oeste", bearingWord(-90f))
        assertEquals("sur", bearingWord(180f))
    }
}
