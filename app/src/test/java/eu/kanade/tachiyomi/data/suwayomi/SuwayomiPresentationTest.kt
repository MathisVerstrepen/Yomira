// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.ui.suwayomi.downloadChapters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiPresentationTest {
    @Test
    fun `server projections preserve membership identity and session scoped covers without local overrides`() {
        val remote = ServerManga(17, Long.MAX_VALUE, "Server title", thumbnailUrl = "/cover/17", user = ServerMangaUser(true, unreadCount = 2, inLibraryAt = 1_700_000_000))
        val a = remote.toKomikkuManga("connection-a")
        val b = remote.toKomikkuManga("connection-b")
        assertEquals(SuwayomiMode.readerId(17), a.id)
        assertEquals(Long.MAX_VALUE, a.source)
        assertTrue(a.favorite)
        assertEquals("Server title", a.title)
        assertEquals(1_700_000_000_000, a.dateAdded)
        assertNotEquals(a.thumbnailUrl, b.thumbnailUrl)
        assertTrue(SuwayomiImages.handles(a.thumbnailUrl))
        assertNotEquals(a.url, b.url)
    }

    @Test
    fun `mixed timestamp contracts normalize to the same instant`() {
        assertEquals(1_700_000_000_000, serverEpochMillis(1_700_000_000))
        assertEquals(1_700_000_000_000, serverEpochMillis(1_700_000_000_000))
        assertEquals(0, serverEpochMillis(0))
    }

    @Test
    fun `native filter controls retain nested server positions and explicit reset values`() {
        val descriptors = listOf(ServerFilter("HeaderFilter", name = "Heading"), ServerFilter("GroupFilter", name = "Group", filters = listOf(ServerFilter("CheckBoxFilter", name = "Option", checked = true), ServerFilter("TextFilter", name = "Query", text = "old"))))
        val filters = descriptors.toKomikkuFilters()
        val group = filters[1] as Filter.Group<*>
        (group.state[0] as Filter.CheckBox).state = false
        (group.state[1] as Filter.Text).state = ""
        assertEquals(listOf("{\"position\":1,\"groupChange\":{\"position\":0,\"checkBoxState\":false}}", "{\"position\":1,\"groupChange\":{\"position\":1,\"textState\":\"\"}}"), filters.toServerChanges().map { it.toString() })
        assertThrows(SuwayomiException::class.java) { listOf(ServerFilter("UnknownFilter")).toKomikkuFilters() }
    }

    @Test
    fun `next downloads start with the oldest unread source chapter and skip existing files`() {
        fun chapter(id: Int, order: Int, read: Boolean = false, downloaded: Boolean = false) = ServerChapter(id, 17, "Chapter", 1.0, order, pageCount = 1, user = ServerChapterUser(read, false, 0, 0, downloaded), url = "chapter/$id", manga = ServerChapterMangaIdentity(8, "manga"))
        val list = listOf(chapter(1, 0), chapter(2, 1, read = true), chapter(3, 2, downloaded = true), chapter(4, 3))
        assertEquals(listOf(4), downloadChapters(list, DownloadAction.NEXT_1_CHAPTER).map { it.id })
        assertEquals(listOf(4, 1), downloadChapters(list, DownloadAction.UNREAD_CHAPTERS).map { it.id })
    }
}
// KMK <--
