// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.Serializable

/** Remember every ID tied at the last fetched timestamp, including ties spanning cursor pages. */
@Serializable
data class SuwayomiUpdateCheckpoint(val fetchedAt: Long = 0, val ids: Set<Int> = emptySet()) {
    fun contains(chapter: ServerActivityChapter): Boolean = chapter.fetchedAt < fetchedAt || (chapter.fetchedAt == fetchedAt && chapter.id in ids)

    fun advance(chapters: List<ServerActivityChapter>): SuwayomiUpdateCheckpoint {
        val latest = maxOf(fetchedAt, chapters.maxOfOrNull { it.fetchedAt } ?: 0)
        return SuwayomiUpdateCheckpoint(latest, (if (latest == fetchedAt) ids else emptySet()) + chapters.filter { it.fetchedAt == latest }.map { it.id })
    }
}
// KMK <--
