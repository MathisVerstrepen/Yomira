// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiLibraryTest {
    private fun manga(id: Int, unread: Int = 0, categories: List<ServerCategory> = emptyList()) = ServerManga(
        id = id,
        sourceId = 9,
        title = "Same title",
        author = "Author outside the title",
        user = ServerMangaUser(true, unreadCount = unread),
        categories = ServerMangaCategories(categories),
    )

    @Test
    fun `uncategorized matches only manga without explicit category membership`() {
        val categorized = manga(1, categories = listOf(ServerCategory(2, "Category", 1)))
        assertFalse(libraryMatches(categorized, "", 0, LibraryFilter.ALL))
        assertTrue(libraryMatches(manga(2), "", 0, LibraryFilter.ALL))
        assertTrue(libraryMatches(categorized, "AUTHOR", 2, LibraryFilter.ALL))
        assertFalse(libraryMatches(categorized, "", null, LibraryFilter.DOWNLOADED))
    }

    @Test
    fun `aggregate ordering and selection candidates retain exact IDs for duplicate titles`() {
        val items = listOf(manga(3, 1), manga(1, 2), manga(2, 2))
        assertEquals(listOf(1, 2, 3), libraryOrder(items, CategoryPresentation(sort = LibrarySort.UNREAD)).map { it.id })
        assertEquals(listOf(3, 2, 1), libraryOrder(items, CategoryPresentation(sort = LibrarySort.UNREAD, reverse = true)).map { it.id })
        assertEquals(3, items.filter { libraryMatches(it, "same", null, LibraryFilter.UNREAD) }.size)
    }

    @Test
    fun `category presentation changes cannot overwrite another category or global view`() {
        val view = LibraryPresentation().withCategory(null, CategoryPresentation(grid = false)).withCategory(2, CategoryPresentation(hidden = true, sort = LibrarySort.ADDED))
        assertFalse(view.category(null).grid)
        assertTrue(view.category(2).hidden)
        assertFalse(view.category(3).hidden)
        assertEquals(LibrarySort.TITLE, view.category(null).sort)
    }
}
// KMK <--
