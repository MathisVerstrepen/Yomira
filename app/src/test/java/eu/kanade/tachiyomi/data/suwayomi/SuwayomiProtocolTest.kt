// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiProtocolTest {
    @Test
    fun `reverse proxy prefix and chapter index URLs are preserved`() {
        val url = SuwayomiUrl("https://example.org:8443/suwayomi")
        assertEquals("https://example.org:8443/suwayomi/api/graphql", url.graphql.toString())
        assertEquals("https://example.org:8443/suwayomi/api/v1/manga/9/chapter/0/page/3", url.asset("/api/v1/manga/9/chapter/0/page/3").toString())
        assertEquals(url.graphql, url.asset("/suwayomi/api/graphql"))
        assertThrows(IllegalArgumentException::class.java) { url.asset("https://other.org/image") }
        assertThrows(IllegalArgumentException::class.java) { SuwayomiUrl("https://user:secret@example.org") }
        assertThrows(IllegalArgumentException::class.java) { url.asset("../outside") }
    }

    @Test
    fun `local and remote identifiers cannot collide or overflow`() {
        listOf(1, 17, Int.MAX_VALUE).forEach {
            assertTrue(SuwayomiMode.readerId(it) < -1)
            assertEquals(it, SuwayomiMode.remoteId(SuwayomiMode.readerId(it)))
        }
        assertThrows(IllegalArgumentException::class.java) { SuwayomiMode.remoteId(1) }
    }

    @Test
    fun `server long scalar retains full precision`() {
        val json = Json { ignoreUnknownKeys = true }
        val manga = json.decodeFromString<ServerManga>("""{"id":1,"sourceId":"9223372036854775807","title":"Test","user":{"inLibrary":true}}""")
        assertEquals(Long.MAX_VALUE, manga.sourceId)
    }

    @Test
    fun `mark unread invalidates old progress including a completed chapter`() {
        var disk = ""
        val outbox = ChapterOutbox(null) { disk = it }
        val ticket = outbox.ticket(listOf(9))
        outbox.enqueue(9, ChapterIntentKind.PROGRESS, ChapterPatch(isRead = true, lastPageRead = 19), ticket)
        outbox.enqueue(9, ChapterIntentKind.MARK_UNREAD, ChapterPatch(isRead = false, lastPageRead = 0))
        assertFalse(outbox.enqueue(9, ChapterIntentKind.PROGRESS, ChapterPatch(isRead = true, lastPageRead = 20), ticket))
        val recovered = ChapterOutbox(disk) {}
        assertEquals(listOf(ChapterIntentKind.MARK_UNREAD), recovered.operations().map { it.kind })
        assertEquals(false, recovered.operations().single().patch.isRead)
    }

    @Test
    fun `progress coalesces exactly within a session without taking maximum`() {
        val outbox = ChapterOutbox(null) {}
        val ticket = outbox.ticket(listOf(3))
        outbox.enqueue(3, ChapterIntentKind.PROGRESS, ChapterPatch(lastPageRead = 9), ticket)
        outbox.enqueue(3, ChapterIntentKind.PROGRESS, ChapterPatch(lastPageRead = 2), ticket)
        assertEquals(2, outbox.operations().single().patch.lastPageRead)
        outbox.enqueue(3, ChapterIntentKind.BOOKMARK, ChapterPatch(isBookmarked = false))
        assertEquals(2, outbox.operations().size)
    }

    @Test
    fun `backwards navigation cannot drop a pending completion`() {
        val outbox = ChapterOutbox(null) {}
        val ticket = outbox.ticket(listOf(3))
        outbox.enqueue(3, ChapterIntentKind.PROGRESS, ChapterPatch(isRead = true, lastPageRead = 9), ticket)
        outbox.enqueue(3, ChapterIntentKind.PROGRESS, ChapterPatch(lastPageRead = 2), ticket)
        assertEquals(true, outbox.operations().single().patch.isRead)
        assertEquals(2, outbox.operations().single().patch.lastPageRead)
    }

    @Test
    fun `source filter group changes retain both positional indices`() {
        val change = sourceFilterChange(listOf(2, 4), "checkBoxState", kotlinx.serialization.json.JsonPrimitive(false))
        assertEquals("{\"position\":2,\"groupChange\":{\"position\":4,\"checkBoxState\":false}}", change.toString())
    }

    @Test
    fun `process death preserves uncertainty and does not blindly replay`() {
        var disk = ""
        val outbox = ChapterOutbox(null) { disk = it }
        outbox.enqueue(4, ChapterIntentKind.MARK_READ, ChapterPatch(isRead = true))
        outbox.setStatus(outbox.operations().single().id, ChapterIntentStatus.SENDING)
        val recovered = ChapterOutbox(disk) {}
        assertEquals(ChapterIntentStatus.AMBIGUOUS, recovered.operations().single().status)
    }
}
// KMK <--
