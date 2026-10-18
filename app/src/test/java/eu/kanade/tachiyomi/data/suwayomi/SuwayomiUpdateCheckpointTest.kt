// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiUpdateCheckpointTest {
    private fun chapter(id: Int, time: Long) = ServerActivityChapter(id, "Chapter", ServerManga(1, 0, "Manga", user = ServerMangaUser(true)), ServerChapterUser(false, false, 0, 0, false), time)

    @Test
    fun `timestamp ties across pages survive restart and only new IDs notify`() {
        val first = (1..122).map { chapter(it, 100) }
        val checkpoint = SuwayomiUpdateCheckpoint().advance(first.take(60)).advance(first.drop(60))
        val restored = Json.decodeFromString<SuwayomiUpdateCheckpoint>(Json.encodeToString(SuwayomiUpdateCheckpoint.serializer(), checkpoint))
        assertTrue(first.all(restored::contains))
        assertFalse(restored.contains(chapter(123, 100)))
        val advanced = restored.advance(listOf(chapter(123, 100), chapter(124, 101)))
        assertEquals(setOf(124), advanced.ids)
        assertTrue(advanced.contains(chapter(123, 100)))
        assertFalse(advanced.contains(chapter(125, 101)))
    }

    @Test
    fun `empty check never rewinds the persisted watermark`() {
        val checkpoint = SuwayomiUpdateCheckpoint(100, setOf(1))
        assertEquals(checkpoint, checkpoint.advance(emptyList()))
        assertEquals(checkpoint, checkpoint.advance(listOf(chapter(2, 99))))
    }
}
// KMK <--
