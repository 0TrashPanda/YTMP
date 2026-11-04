package dev.trashpanda.ytmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeakerGroupTest {
    private fun near(a: Map<String, Double>, b: Map<String, Double>) =
        assertEquals(a.mapValues { Math.round(it.value * 1000) }, b.mapValues { Math.round(it.value * 1000) })

    @Test
    fun `the level is the loudest speaker`() {
        val group = SpeakerGroup()
        assertEquals(0.8, group.level(listOf(Speaker("a", 0.8, false), Speaker("b", 0.4, true))))
        assertNull(group.level(listOf(Speaker("a", null, false))))
    }

    @Test
    fun `changing the level keeps the balance between speakers`() {
        val group = SpeakerGroup()
        val speakers = listOf(Speaker("a", 0.8, false), Speaker("b", 0.4, true))
        near(mapOf("a" to 0.6, "b" to 0.3), group.setTo(speakers, 0.6))
        near(mapOf("a" to 1.0, "b" to 0.5), group.setTo(speakers, 1.2))
    }

    @Test
    fun `the balance comes back after all were at 0`() {
        val group = SpeakerGroup()
        group.setTo(listOf(Speaker("a", 0.8, false), Speaker("b", 0.4, false)), 0.0)
        near(mapOf("a" to 0.1, "b" to 0.05), group.setTo(listOf(Speaker("a", 0.0, false), Speaker("b", 0.0, false)), 0.1))
    }

    @Test
    fun `a speaker without a volume moves with the loudest`() {
        near(mapOf("a" to 0.5, "b" to 0.5), SpeakerGroup().setTo(listOf(Speaker("a", 1.0, false), Speaker("b", null, false)), 0.5))
    }
}
