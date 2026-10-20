// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

@Serializable
private data class ChapterCountResult(val manga: CountedManga)

@Serializable
private data class CountedManga(
    val id: Int,
    @Serializable(with = ServerLongSerializer::class) val sourceId: Long,
    val url: String,
    val chapters: FetchedChapterCount,
)

@Serializable
private data class FetchedChapterCount(val totalCount: Int)

private val chapterCountJson = Json { ignoreUnknownKeys = true }

/** Fetch the provider's chapter list on the server; only its count crosses back to the app. */
internal suspend fun SuwayomiSession.migrationChapterCount(manga: ServerManga): Int {
    requireBrowsableSource(manga.sourceId)
    val response = execute(
        "mutation MigrationChapterCount(\$input: FetchMangaAndChaptersInput!) { fetchMangaAndChapters(input: \$input) { manga { id sourceId url chapters { totalCount } } } }",
        variables(
            "input" to buildJsonObject {
                put("id", manga.id)
                put("fetchManga", false)
                put("fetchChapters", true)
            },
        ),
    )
    val counted = chapterCountJson.decodeFromJsonElement<ChapterCountResult>(response.getValue("fetchMangaAndChapters")).manga
    if (counted.id != manga.id || counted.sourceId != manga.sourceId || counted.url != manga.url || counted.chapters.totalCount < 0) {
        throw SuwayomiException(SuwayomiFailure.CONTRACT)
    }
    return counted.chapters.totalCount
}
// KMK <--
