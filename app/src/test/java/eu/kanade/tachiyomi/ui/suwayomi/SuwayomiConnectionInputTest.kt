// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SuwayomiConnectionInputTest {
    @Test
    fun `hostname paste defaults to HTTPS and retains a reverse proxy prefix`() {
        assertEquals("https://reader.example/suwayomi/", connectionUrl("  reader.example/suwayomi  "))
    }

    @Test
    fun `explicit HTTP and a local port remain unchanged`() {
        assertEquals("http://192.168.1.20:4567/", connectionUrl("http://192.168.1.20:4567"))
    }

    @Test
    fun `credentials and query parameters cannot become a saved server address`() {
        listOf("https://user:secret@reader.example", "https://reader.example/?token=value", "https://reader.example/#library").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { connectionUrl(address) }
        }
    }

    @Test
    fun `empty malformed and non HTTP addresses fail validation`() {
        listOf("", "   ", "not a server address", "https://", "ftp://reader.example").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { connectionUrl(address) }
        }
    }
}
// KMK <--
