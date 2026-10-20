// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

internal data class MigrationLibrarySource(val id: Long, val source: ServerMangaSource?, val mangaCount: Int)

@Serializable
private data class MigrationLibraryEntry(
    val id: Int,
    @Serializable(with = ServerLongSerializer::class) val sourceId: Long,
    val source: ServerMangaSource? = null,
)

private val migrationLibraryJson = Json { ignoreUnknownKeys = true }

/** Original providers come from the library, including disabled or uninstalled providers. */
internal suspend fun SuwayomiSession.migrationLibrarySources(): List<MigrationLibrarySource> {
    val rows = migrationLibraryPages<MigrationLibraryEntry>("id sourceId source { name lang iconUrl }")
    val unique = rows.groupBy { it.id }.map { (_, entries) ->
        if (entries.map { it.sourceId }.distinct().size != 1) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        entries.first()
    }
    return unique.groupBy { it.sourceId }.map { (id, entries) ->
        MigrationLibrarySource(id, entries.firstNotNullOfOrNull { it.source }, entries.size)
    }
}

internal suspend fun SuwayomiSession.migrationLibraryTitles(sourceId: Long): List<ServerManga> {
    val mangas = migrationLibraryPages<ServerManga>(
        "id sourceId title url author thumbnailUrl thumbnailUrlLastFetched chapters { totalCount } user { inLibrary }",
        sourceId,
    )
    if (mangas.any { it.sourceId != sourceId || !it.user.inLibrary }) throw SuwayomiException(SuwayomiFailure.CONTRACT)
    return mangas.distinctBy { it.id }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}

private suspend inline fun <reified T> SuwayomiSession.migrationLibraryPages(fields: String, sourceId: Long? = null): List<T> {
    val rows = mutableListOf<T>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val data = execute(
            "query MigrationLibrary(\$after: Cursor${if (sourceId != null) ", \$source: LongString!" else ""}) { mangas(first: 100, after: \$after, condition: { inLibrary: true${if (sourceId != null) ", sourceId: \$source" else ""} }, order: [{by: ID, byType: ASC}]) { nodes { $fields } totalCount pageInfo { hasNextPage endCursor } } }",
            buildJsonObject {
                put("after", cursor?.let(::JsonPrimitive) ?: JsonNull)
                sourceId?.let { put("source", it.toString()) }
            },
        )
        currentCoroutineContext().ensureActive()
        val page = migrationLibraryJson.decodeFromJsonElement<ServerConnection<T>>(data.getValue("mangas"))
        rows += page.nodes
        if (!page.pageInfo.hasNextPage) break
        val next = page.pageInfo.endCursor
        if (next == null || !cursors.add(next) || page.nodes.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        cursor = next
    } while (true)
    return rows
}
// KMK <--
