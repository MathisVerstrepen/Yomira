// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.Serializable

@Serializable
enum class LibrarySort { TITLE, UNREAD, DOWNLOADED, ADDED, LAST_READ }

@Serializable
enum class LibraryGroup { NONE, CATEGORY, SOURCE, STATUS }

@Serializable
enum class LibraryFilter { ALL, UNREAD, READ, DOWNLOADED, BOOKMARKED }

/** Device presentation belongs to the active account session, never to canonical category fields. */
@Serializable
data class CategoryPresentation(val grid: Boolean = true, val sort: LibrarySort = LibrarySort.TITLE, val reverse: Boolean = false, val hidden: Boolean = false)

@Serializable
data class LibraryPresentation(
    val group: LibraryGroup = LibraryGroup.NONE,
    val categories: Map<Int, CategoryPresentation> = emptyMap(),
) {
    fun category(id: Int?) = categories[id ?: -1] ?: CategoryPresentation()
    fun withCategory(id: Int?, value: CategoryPresentation) = copy(categories = categories + ((id ?: -1) to value))
}

fun libraryMatches(manga: ServerManga, search: String, categoryId: Int?, filter: LibraryFilter): Boolean {
    val categoryMatches = categoryId == null || if (categoryId == 0) manga.categories.nodes.all { it.id == 0 } else manga.categories.nodes.any { it.id == categoryId }
    return categoryMatches && listOfNotNull(manga.title, manga.author, manga.artist, manga.description, manga.genre.joinToString(" ")).any { it.contains(search.trim(), ignoreCase = true) } && when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.UNREAD -> manga.user.unreadCount > 0
        LibraryFilter.READ -> manga.user.unreadCount == 0
        LibraryFilter.DOWNLOADED -> manga.user.downloadCount > 0
        LibraryFilter.BOOKMARKED -> manga.user.bookmarkCount > 0
    }
}

fun libraryOrder(mangas: List<ServerManga>, presentation: CategoryPresentation): List<ServerManga> {
    val title = Comparator<ServerManga> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title) }.thenBy { it.id }
    val comparator = when (presentation.sort) {
        LibrarySort.TITLE -> title
        LibrarySort.UNREAD -> compareByDescending<ServerManga> { it.user.unreadCount }.then(title)
        LibrarySort.DOWNLOADED -> compareByDescending<ServerManga> { it.user.downloadCount }.then(title)
        LibrarySort.ADDED -> compareByDescending<ServerManga> { it.user.inLibraryAt }.then(title)
        LibrarySort.LAST_READ -> compareByDescending<ServerManga> { it.user.lastReadChapter?.user?.lastReadAt ?: 0 }.then(title)
    }
    return mangas.sortedWith(if (presentation.reverse) comparator.reversed() else comparator)
}
// KMK <--
