package dev.trashpanda.ytmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdatesTest {
    @Test
    fun `version codes count like the build's`() {
        assertEquals(502, Updates.versionCode("0.5.2"))
        assertEquals(502, Updates.versionCode("v0.5.2"))
        assertEquals(10000, Updates.versionCode("1.0"))
        assertTrue(Updates.versionCode("0.10.0") > Updates.versionCode("0.9.9"))
        // Test builds of the workflow never count as newer.
        assertEquals(0, Updates.versionCode("0.0.0-test.12"))
    }

    @Test
    fun `release notes become plain text without the install part`() {
        val notes = """
            ## What's fixed

            - **Music** stopped after one song
            * Paused music stays

            ## Install

            Download ytmp-0.5.2.apk below.

            **Full Changelog**: https://github.com/0TrashPanda/YTMP/compare/v0.5.1...v0.5.2
        """.trimIndent()
        assertEquals("What's fixed\n\n• Music stopped after one song\n• Paused music stays", Updates.plainNotes(notes))
        assertEquals("Fixes", Updates.plainNotes("Fixes\n\n**Full Changelog**: https://x"))
    }
}
