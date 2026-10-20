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

                [cast]
                discovery = false
                devices = ["Bose=10.0.0.109", "10.0.0.101"]

                [mpd]
                devices = ["Woonkamer=192.168.0.50", "192.168.0.51:6601"]

                [zones]
                files = ["http://192.168.0.50:8766/ytmp-zones.json"]
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val config = Config.load(file, env = mapOf("YTMP_MODULE_KEY" to "from-env"))

        assertEquals(9000, config.server.port)
        assertEquals("http://module:8401", config.ytm.url)
        assertEquals("from-env", config.ytm.key)
        assertEquals(6, config.rooms.codeLength)
        assertEquals(false, config.cast.discovery)
        assertEquals(listOf("Bose=10.0.0.109", "10.0.0.101"), config.cast.devices)
        assertEquals(true, config.mpd.discovery)
        assertEquals(
            listOf(MpdEntry("Woonkamer", "192.168.0.50", 6600), MpdEntry(null, "192.168.0.51", 6601)),
            config.mpd.devices.map(MpdEntry::parse),
        )
        assertEquals("mpd:192.168.0.51:6601", MpdEntry.parse("192.168.0.51:6601").id)
        assertEquals(ZonesConfig(discovery = true, files = listOf("http://192.168.0.50:8766/ytmp-zones.json")), config.zones)
    }

    @Test
    fun `missing file gives defaults`() {
        assertEquals(Config(), Config.load(File("/does/not/exist.toml"), env = emptyMap()))
    }

    @Test
    fun `the song cache's size and folder`() {
        assertEquals(5L shl 30, CacheConfig().bytes())
        assertEquals(500L shl 20, CacheConfig(size = "500 MB").bytes())
        assertEquals(3L shl 29, CacheConfig(size = "1.5g").bytes())
        assertEquals(0, CacheConfig(size = "0").bytes())
        assertEquals(File("/var/lib/ytmp/cache"), CacheConfig().folder(DatabaseConfig(path = "/var/lib/ytmp/ytmp.db")))
        assertEquals(File("/music/cache"), CacheConfig(dir = "/music/cache").folder(DatabaseConfig()))
        val config = Config.load(null, env = mapOf("YTMP_CACHE_SIZE" to "2GB", "YTMP_CACHE_DIR" to "/c"))
        assertEquals(2L shl 30, config.cache.bytes())
        assertEquals(File("/c"), config.cache.folder(config.database))
    }
}
