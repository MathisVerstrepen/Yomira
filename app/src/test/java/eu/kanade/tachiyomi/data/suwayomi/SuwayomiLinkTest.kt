// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiLinkTest {
    @Test
    fun `portable manga link preserves proxy prefix and exact source identity`() {
        val link = SuwayomiLink("https://example.org:8443/proxy/", 9, Long.MAX_VALUE, "/manga/a+b?x=1&y=2")
        assertTrue(link.encode().startsWith("yomira://manga/"))
        assertEquals(link, SuwayomiLink.parse(link.encode()))
        assertFalse(link.matches(ServerManga(9, 7, "Reused ID", user = ServerMangaUser(true), url = link.mangaUrl)))
    }

    @Test
    fun `links shared before the rebrand still preserve server and source identity`() {
        val legacy = "komikku-suwayomi://manga/9?server=https%3A%2F%2Fexample.org%3A8443%2Fproxy%2F&source=9223372036854775807&url=%2Fmanga%2Fa%2Bb%3Fx%3D1%26y%3D2"
        val expected = SuwayomiLink("https://example.org:8443/proxy/", 9, Long.MAX_VALUE, "/manga/a+b?x=1&y=2")
        assertEquals(expected, SuwayomiLink.parse(legacy))
        assertEquals(expected, SuwayomiLink.parse(SuwayomiLink.parse(legacy).encode()))
    }

    @Test
    fun `rebranded links enforce the same validation as legacy links`() {
        for (scheme in listOf("yomira", "komikku-suwayomi")) {
            assertThrows(IllegalArgumentException::class.java) { SuwayomiLink.parse("$scheme://manga/9?server=https%3A%2F%2Fuser%3Asecret%40example.org&source=1&url=x") }
            assertThrows(IllegalArgumentException::class.java) { SuwayomiLink.parse("$scheme://manga/9?server=https%3A%2F%2Fexample.org&source=1&source=2&url=x") }
        }
        assertThrows(IllegalArgumentException::class.java) { SuwayomiLink.parse("unrelated://manga/9?server=https%3A%2F%2Fexample.org&source=1&url=x") }
    }

    @Test
    fun `links reject credentials duplicate fields and ambiguous identity`() {
        assertThrows(IllegalArgumentException::class.java) { SuwayomiLink.parse("komikku-suwayomi://manga/9?server=https%3A%2F%2Fuser%3Asecret%40example.org&source=1&url=x") }
        assertThrows(IllegalArgumentException::class.java) { SuwayomiLink.parse("komikku-suwayomi://manga/9?server=https%3A%2F%2Fexample.org&source=1&source=2&url=x") }
        assertThrows(IllegalArgumentException::class.java) { SuwayomiLink.parse("komikku-suwayomi://manga/-1?server=https%3A%2F%2Fexample.org&source=1&url=x") }
    }
}
// KMK <--
