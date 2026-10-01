package dev.trashpanda.ytmp.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun `reads toml and lets environment variables override it`() {
        val file = File.createTempFile("ytmp", ".toml").apply {
            writeText(
                """
                [server]
                port = 9000

                [ytm]
                url = "http://module:8401"
                key = "from-file"

                [rooms]
                code_style = "digits"
                code_length = 6
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val config = Config.load(file, env = mapOf("YTMP_MODULE_KEY" to "from-env"))

        assertEquals(9000, config.server.port)
        assertEquals("http://module:8401", config.ytm.url)
        assertEquals("from-env", config.ytm.key)
        assertEquals(6, config.rooms.codeLength)
    }

    @Test
    fun `missing file gives defaults`() {
        assertEquals(Config(), Config.load(File("/does/not/exist.toml"), env = emptyMap()))
    }
}
