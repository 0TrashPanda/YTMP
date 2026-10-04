package dev.trashpanda.ytmp.server

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class YoutubeLinksTest {
    private val cookies = "SID=abc; HSID=def; __Secure-3PAPISID=ghi/jkl; LOGIN_INFO=x"

    @Test
    fun `finds the cookies in whatever was pasted`() {
        // Just the cookies, or the Cookie header in one line.
        assertEquals(cookies, YoutubeLinks.extractCookie("  $cookies;  "))
        assertEquals(cookies, YoutubeLinks.extractCookie("cookie: $cookies"))
        // Request headers copied from developer tools.
        assertEquals(cookies, YoutubeLinks.extractCookie("POST /youtubei/v1/browse HTTP/2\nHost: music.youtube.com\nCookie: $cookies\nUser-Agent: Firefox"))
        // Chrome's header list: the name on one line, the value on the next.
        assertEquals(cookies, YoutubeLinks.extractCookie(":authority\nmusic.youtube.com\ncookie\n$cookies\norigin\nhttps://music.youtube.com"))
        // Copy as cURL, from Chrome (-b) and Firefox (-H).
        assertEquals(cookies, YoutubeLinks.extractCookie("curl 'https://music.youtube.com/youtubei/v1/browse' \\\n  -H 'accept: */*' \\\n  -b '$cookies' \\\n  --data-raw '{}'"))
        assertEquals(cookies, YoutubeLinks.extractCookie("curl 'https://music.youtube.com/' -H 'User-Agent: x' -H 'Cookie: $cookies'"))
    }

    @Test
    fun `no sign-in cookies, nothing found`() {
        assertNull(YoutubeLinks.extractCookie("SID=abc; HSID=def"))
        assertNull(YoutubeLinks.extractCookie(""))
    }

    @Test
    fun `the stored sign-in only opens with the same key`() {
        val accounts = Accounts(Database.open(DatabaseConfig(path = Files.createTempDirectory("ytmp").resolve("ytmp.db").toString())))
        val module = RemoteSourceModule(HttpClient(), "http://unused", "")
        fun links(secret: String) = YoutubeLinks(accounts, secret, module, CoroutineScope(Dispatchers.Default))

        val stored = links("module-key").encrypt(cookies)
        assertNotEquals(cookies, stored)
        assertEquals(cookies, links("module-key").decrypt(stored))
        assertNull(links("another-key").decrypt(stored))
    }
}
