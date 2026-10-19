// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** A reader holds the connection it opened with; it can never switch accounts underneath a page. */
class SuwayomiReader private constructor(
    val session: SuwayomiSession,
    var manga: Manga,
    val chapters: List<Chapter>,
    private val ticket: ReadingTicket,
) {
    companion object {
        suspend fun open(session: SuwayomiSession, mangaId: Int, chapterId: Int? = null): SuwayomiReader {
            session.flushSoon()
            val cached = session.offline.state.value.titles[mangaId]
            val data = if (chapterId != null && session.offline.files(chapterId) != null && cached != null) cached.copy(chapters = session.overlay(cached.chapters)) else session.readerData(mangaId)
            val remoteManga = data.manga
            val remoteChapters = data.chapters
            val manga = Manga.create().copy(
                id = SuwayomiMode.readerId(mangaId),
                source = SuwayomiMode.SOURCE_ID,
                // Avoid file-based Android overrides and local library authority.
                favorite = false,
                ogTitle = remoteManga.title,
                ogAuthor = remoteManga.author,
                ogArtist = remoteManga.artist,
                ogDescription = remoteManga.description,
                ogGenre = remoteManga.genre,
                ogThumbnailUrl = null,
                initialized = true,
                url = "suwayomi:${session.id}:$mangaId",
                viewerFlags = session.readerFlags(mangaId).let { local ->
                    val remote = remoteManga.meta.firstOrNull { it.key == "komikku.reader.v1.mode" }?.value?.toLongOrNull()
                    val mask = ReadingMode.MASK.toLong()
                    if (chapterId != null && session.offline.files(chapterId) != null) {
                        local
                    } else if (remote != null && ReadingMode.entries.any { it.flagValue.toLong() == remote }) {
                        (local and mask.inv()) or remote
                    } else {
                        local
                    }
                },
                chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_ASC,
            )
            return SuwayomiReader(session, manga, remoteChapters.map { it.toReaderChapter() }, session.readingTicket(remoteChapters))
        }
    }

    fun progress(chapterId: Long, page: Int, completed: Boolean) {
        session.enqueue(
            SuwayomiMode.remoteId(chapterId),
            ChapterIntentKind.PROGRESS,
            ChapterPatch(isRead = if (completed) true else null, lastPageRead = page),
            ticket,
        )
    }

    fun bookmark(chapterId: Long, bookmarked: Boolean) {
        session.enqueue(SuwayomiMode.remoteId(chapterId), ChapterIntentKind.BOOKMARK, ChapterPatch(isBookmarked = bookmarked), ticket)
    }

    suspend fun setFlags(flag: Long, mask: Long): Manga {
        val flags = manga.viewerFlags and mask.inv() or (flag and mask)
        session.setReaderFlags(SuwayomiMode.remoteId(manga.id), flags)
        manga = manga.copy(viewerFlags = flags)
        if (mask == ReadingMode.MASK.toLong()) {
            try {
                session.setMangaReadingMode(SuwayomiMode.remoteId(manga.id), flag)
            } catch (e: SuwayomiException) {
                if (e.failure !in listOf(SuwayomiFailure.NETWORK, SuwayomiFailure.SERVER)) throw e
            }
        }
        return manga
    }
}

internal fun ServerChapter.toReaderChapter(): Chapter = Chapter.create().copy(
    id = SuwayomiMode.readerId(id),
    mangaId = SuwayomiMode.readerId(mangaId),
    name = name,
    url = "suwayomi:chapter:$id",
    chapterNumber = chapterNumber,
    // Suwayomi counts oldest to newest; Komikku expects newest to oldest.
    sourceOrder = -sourceOrder.toLong(),
    dateUpload = uploadDate,
    scanlator = scanlator,
    read = user.isRead,
    bookmark = user.isBookmarked,
    lastPageRead = user.lastPageRead.toLong(),
)
/** Pager events identify the first source page of a spread; persist its last displayed source page. */
internal fun canonicalServerPage(index: Int, count: Int, hasExtraPage: Boolean): Int {
    require(count > 0)
    return (index + if (hasExtraPage) 1 else 0).coerceIn(0, count - 1)
}
// KMK <--
