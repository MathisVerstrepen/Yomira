// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source

/** Immutable view projections only: these are never inserted into the Android manga database. */
internal fun ServerManga.toKomikkuManga(sessionId: String): Manga = Manga.create().copy(
    id = SuwayomiMode.readerId(id),
    source = sourceId,
    favorite = user.inLibrary,
    initialized = true,
    url = "suwayomi:$sessionId:$id",
    ogTitle = title,
    ogAuthor = author,
    ogArtist = artist,
    ogDescription = description,
    ogGenre = genre,
    ogStatus = when (status) {
        "ONGOING" -> SManga.ONGOING
        "COMPLETED" -> SManga.COMPLETED
        "LICENSED" -> SManga.LICENSED
        "PUBLISHING_FINISHED" -> SManga.PUBLISHING_FINISHED
        "CANCELLED" -> SManga.CANCELLED
        "ON_HIATUS" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }.toLong(),
    ogThumbnailUrl = coverUrl()?.let { SuwayomiImages.url(sessionId, it) },
    coverLastModified = thumbnailUrlLastFetched ?: 0,
    dateAdded = serverEpochMillis(user.inLibraryAt),
    chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_DESC,
)

internal fun ServerSource.toKomikkuSource(sessionId: String) = Source(
    id = id,
    lang = lang,
    name = name,
    supportsLatest = supportsLatest,
    isStub = false,
    iconUrl = SuwayomiImages.url(sessionId, iconUrl),
)

internal fun ServerManga.toLibraryItem(sessionId: String, downloadBadge: Boolean, unreadBadge: Boolean, languageBadge: Boolean, useLanguageIcon: Boolean, sourceBadge: Boolean): LibraryItem {
    val count = maxOf(chapters.totalCount, user.unreadCount).toLong()
    return LibraryItem(
        libraryManga = LibraryManga(
            manga = toKomikkuManga(sessionId),
            categories = categories.nodes.map { it.id.toLong() }.ifEmpty { listOf(0L) },
            totalChapters = count,
            readCount = count - user.unreadCount,
            bookmarkCount = user.bookmarkCount.toLong(),
            bookmarkReadCount = 0,
            chapterFlags = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = serverEpochMillis(user.lastReadChapter?.user?.lastReadAt ?: 0),
        ),
        downloadCount = if (downloadBadge) user.downloadCount.toLong() else -1,
        unreadCount = if (unreadBadge) user.unreadCount.toLong() else -1,
        sourceLanguage = if (languageBadge) source?.lang.orEmpty() else "",
        useLangIcon = useLanguageIcon,
        source = source?.takeIf { sourceBadge }?.let {
            Source(sourceId, it.lang, it.name, false, false, iconUrl = it.iconUrl?.let { url -> SuwayomiImages.url(sessionId, url) })
        },
    )
}
/** Server state contains both epoch seconds and epoch milliseconds, depending on the field/version. */
internal fun serverEpochMillis(value: Long): Long = if (value in 1..99_999_999_999) value * 1000 else value
// KMK <--
