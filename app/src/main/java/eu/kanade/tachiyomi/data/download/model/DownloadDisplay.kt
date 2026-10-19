// KMK -->
package eu.kanade.tachiyomi.data.download.model

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Read-only queue presentation; a server download never needs an executable Android HttpSource. */
interface DownloadDisplay {
    val manga: Manga
    val chapter: Chapter
    val totalPages: Int?
    val totalProgress: Int
    val downloadedImages: Int
    val serverError: Boolean get() = false
}
// KMK <--
