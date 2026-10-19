// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

@Serializable
data class ServerSource(
    @Serializable(with = ServerLongSerializer::class) val id: Long,
    val name: String,
    val lang: String,
    val iconUrl: String,
    val supportsLatest: Boolean,
)

@Serializable
data class ServerFilter(
    @SerialName("__typename") val type: String,
    val name: String = "",
    val text: String? = null,
    val checked: Boolean? = null,
    val selected: Int? = null,
    val triState: String? = null,
    val sort: ServerSort? = null,
    val values: List<String> = emptyList(),
    val filters: List<ServerFilter> = emptyList(),
)

/** Defaults are state; positional type, label, choices and nesting define the schema. */
fun List<ServerFilter>.sameFilterSchema(other: List<ServerFilter>): Boolean = size == other.size && indices.all { index ->
    val a = this[index]
    val b = other[index]
    a.type == b.type && a.name == b.name && a.values == b.values && a.filters.sameFilterSchema(b.filters)
}

@Serializable
data class ServerSort(val index: Int, val ascending: Boolean)

@Serializable
data class SourceResults(val mangas: List<ServerManga>, val hasNextPage: Boolean)

@Serializable
data class ServerCategory(val id: Int, val name: String, val order: Int)

@Serializable
data class ServerDownload(val chapter: ServerChapter, val manga: ServerManga, val state: String, val progress: Float)

@Serializable
data class ServerDownloads(val state: String, val queue: List<ServerDownload>)

@Serializable
private data class CategoryList(val categories: ServerConnection<ServerCategory>)

private val operationJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private suspend inline fun <reified T> SuwayomiSession.result(query: String, field: String, variables: JsonObject = JsonObject(emptyMap())): T {
    val value = execute(query, variables)[field] ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)
    return operationJson.decodeFromJsonElement(value)
}

suspend fun SuwayomiSession.serverSources(): List<ServerSource> {
    val sources = linkedMapOf<Long, ServerSource>()
    var cursor: String? = null
    do {
        val page = result<ServerConnection<ServerSource>>(
            "query(\$after:Cursor) { sources(first:100, after:\$after) { nodes { id name lang iconUrl supportsLatest } totalCount pageInfo { hasNextPage endCursor } } }",
            "sources",
            variables("after" to (cursor?.let(::JsonPrimitive) ?: JsonNull)),
        )
        page.nodes.forEach { sources[it.id] = it }
        if (!page.pageInfo.hasNextPage) break
        val next = page.pageInfo.endCursor
        if (next == null || next == cursor || page.nodes.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        cursor = next
    } while (true)
    return sources.values.toList()
}

private fun filterFields(depth: Int): String = """
    __typename
    ... on TextFilter { name text: default }
    ... on CheckBoxFilter { name checked: default }
    ... on SelectFilter { name selected: default values }
    ... on TriStateFilter { name triState: default }
    ... on SortFilter { name sort: default { index ascending } values }
    ... on HeaderFilter { name }
    ... on SeparatorFilter { name }
    ... on GroupFilter { name ${if (depth > 0) "filters { ${filterFields(depth - 1)} }" else ""} }
""".trimIndent()

suspend fun SuwayomiSession.sourceFilters(sourceId: Long): List<ServerFilter> {
    @Serializable data class SourceSchema(val filters: List<ServerFilter>)
    return result<SourceSchema>(
        "query(\$id:LongString!) { source(id:\$id) { filters { ${filterFields(4)} } } }",
        "source",
        variables("id" to JsonPrimitive(sourceId.toString())),
    ).filters
}

suspend fun SuwayomiSession.browseSource(sourceId: Long, page: Int, query: String, type: String, filters: List<JsonObject>): SourceResults = result(
    "mutation(\$input:FetchSourceMangaInput!) { fetchSourceManga(input:\$input) { mangas { ${SuwayomiSession.MANGA_FIELDS} } hasNextPage } }",
    "fetchSourceManga",
    variables(
        "input" to buildJsonObject {
            put("source", sourceId.toString())
            put("page", page)
            put("type", type)
            put("query", query)
            put("filters", JsonArray(filters))
        },
    ),
)

suspend fun SuwayomiSession.serverCategories(): List<ServerCategory> {
    val categories = linkedMapOf<Int, ServerCategory>()
    var cursor: String? = null
    do {
        val page = result<ServerConnection<ServerCategory>>(
            "query(\$after:Cursor) { categories(first:100, after:\$after) { nodes { id name order } totalCount pageInfo { hasNextPage endCursor } } }",
            "categories",
            variables("after" to (cursor?.let(::JsonPrimitive) ?: JsonNull)),
        )
        page.nodes.forEach { categories[it.id] = it }
        if (!page.pageInfo.hasNextPage) break
        val next = page.pageInfo.endCursor
        if (next == null || next == cursor || page.nodes.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        cursor = next
    } while (true)
    return categories.values.sortedBy { it.order }
}

suspend fun SuwayomiSession.mangaCategories(mangaId: Int): List<ServerCategory> = result<CategoryList>(
    "query(\$id:Int!) { manga(id:\$id) { categories { nodes {id name order} totalCount pageInfo {hasNextPage endCursor} } } }",
    "manga",
    variables("id" to JsonPrimitive(mangaId)),
).categories.nodes

suspend fun SuwayomiSession.categoryMutation(action: String, categoryId: Int? = null, name: String? = null, position: Int? = null) {
    val (operation, inputType, selection) = when (action) {
        "create" -> Triple("createCategory", "CreateCategoryInput", "category { id }")
        "rename" -> Triple("updateCategory", "UpdateCategoryInput", "category { id }")
        "reorder" -> Triple("updateCategoryOrder", "UpdateCategoryOrderInput", "categories { id }")
        "delete" -> Triple("deleteCategory", "DeleteCategoryInput", "clientMutationId")
        else -> error("Unknown category operation")
    }
    execute(
        "mutation(\$input:$inputType!) { $operation(input:\$input) { $selection } }",
        variables(
            "input" to buildJsonObject {
                categoryId?.let { put(if (action == "delete") "categoryId" else "id", it) }
                if (action == "rename") put("patch", buildJsonObject { put("name", name!!) }) else name?.let { put("name", it) }
                position?.let { put("position", it) }
            },
        ),
    )
}

suspend fun SuwayomiSession.setCategoryMembership(mangaId: Int, categoryId: Int, member: Boolean) {
    execute(
        "mutation(\$input:UpdateMangaCategoriesInput!) { updateMangaCategories(input:\$input) { manga { id } } }",
        variables(
            "input" to buildJsonObject {
                put("id", mangaId)
                put("patch", buildJsonObject { put(if (member) "addToCategories" else "removeFromCategories", JsonArray(listOf(JsonPrimitive(categoryId)))) })
            },
        ),
    )
}

suspend fun SuwayomiSession.serverDownloads(): ServerDownloads = result(
    "query { downloadStatus { state queue { state progress chapter { ${SuwayomiSession.CHAPTER_FIELDS} } manga { ${SuwayomiSession.MANGA_FIELDS} } } } }",
    "downloadStatus",
)

suspend fun SuwayomiSession.downloadAction(action: String, chapterId: Int? = null, position: Int? = null) {
    val (operation, inputType, selection) = when (action) {
        "enqueue" -> Triple("enqueueChapterDownload", "EnqueueChapterDownloadInput", "clientMutationId")
        "cancel" -> Triple("dequeueChapterDownload", "DequeueChapterDownloadInput", "clientMutationId")
        "delete" -> Triple("deleteDownloadedChapter", "DeleteDownloadedChapterInput", "clientMutationId")
        "start" -> Triple("startDownloader", "StartDownloaderInput", "clientMutationId")
        "stop" -> Triple("stopDownloader", "StopDownloaderInput", "clientMutationId")
        "clear" -> Triple("clearDownloader", "ClearDownloaderInput", "clientMutationId")
        "reorder" -> Triple("reorderChapterDownload", "ReorderChapterDownloadInput", "clientMutationId")
        else -> error("Unknown download operation")
    }
    execute(
        "mutation(\$input:$inputType!) { $operation(input:\$input) { $selection } }",
        variables(
            "input" to buildJsonObject {
                chapterId?.let { put(if (action == "reorder") "chapterId" else "id", it) }
                if (action == "reorder") put("to", requireNotNull(position).also { require(it >= 0) })
            },
        ),
    )
}

suspend fun SuwayomiSession.updateLibraryOnServer(categories: List<Int>? = null) {
    execute(
        "mutation(\$input:UpdateLibraryInput!) { updateLibrary(input:\$input) { clientMutationId } }",
        variables("input" to buildJsonObject { if (categories != null) put("categories", JsonArray(categories.map(::JsonPrimitive))) }),
    )
}

/** Source schemas are positional. Build exact nested group changes; do not flatten indices. */
fun sourceFilterChange(path: List<Int>, field: String, value: JsonElement): JsonObject {
    require(path.isNotEmpty())
    return buildJsonObject {
        put("position", path.first())
        if (path.size == 1) put(field, value) else put("groupChange", sourceFilterChange(path.drop(1), field, value))
    }
}

suspend fun SuwayomiSession.setMangaReadingMode(mangaId: Int, mode: Long) {
    execute(
        "mutation(\$input:SetMangaMetaInput!) { setMangaMeta(input:\$input) { meta { key value } } }",
        variables(
            "input" to buildJsonObject {
                put(
                    "meta",
                    buildJsonObject {
                        put("mangaId", mangaId)
                        put("key", "komikku.reader.v1.mode")
                        put("value", mode.toString())
                    },
                )
            },
        ),
    )
}

@Serializable
data class ServerActivityChapter(val id: Int, val name: String, val manga: ServerManga, val user: ServerChapterUser, @Serializable(with = ServerLongSerializer::class) val fetchedAt: Long = 0, val chapterNumber: Double = -1.0)

/** Explicit timestamp/ID keysets avoid the reference server's descending timestamp cursor tie bug. */
suspend fun SuwayomiSession.activityPage(history: Boolean, after: String?): ServerConnection<ServerActivityChapter> {
    val field = if (history) "lastReadAt" else "fetchedAt"
    val base = if (history) "lastReadAt: {greaterThan: \"0\"}" else "inLibrary: {equalTo: true}"
    val seek = after?.let {
        val parts = it.split(':')
        require(parts.size == 2)
        val time = parts[0].toLong()
        val id = parts[1].toInt()
        require(time >= 0 && id > 0)
        ", or:[{$field:{lessThan:\"$time\"}},{$field:{equalTo:\"$time\"},id:{greaterThan:$id}}]"
    }.orEmpty()
    val filter = if (after == null) base else "$base, and:[{${seek.removePrefix(", ")}}]"
    val sort = if (history) "LAST_READ_AT" else "FETCHED_AT"
    val page = result<ServerConnection<ServerActivityChapter>>(
        "query { chapters(first:60, filter: {$filter}, order:[{by:$sort,byType:DESC}]) { nodes { id name chapterNumber fetchedAt manga { ${SuwayomiSession.MANGA_FIELDS} } user { isRead isBookmarked lastPageRead lastReadAt isDownloaded } } pageInfo{hasNextPage endCursor} totalCount } }",
        "chapters",
    )
    val cursor = page.nodes.lastOrNull()?.let { "${if (history) it.user.lastReadAt else it.fetchedAt}:${it.id}" }
    return page.copy(pageInfo = page.pageInfo.copy(endCursor = cursor))
}

suspend fun SuwayomiSession.updateRunning(): Boolean {
    @Serializable data class UpdateState(val isRunning: Boolean)
    return result<UpdateState>("query { updateStatus {isRunning} }", "updateStatus").isRunning
}

@Serializable
data class ServerTrackStatus(val name: String, val value: Int)

@Serializable
data class ServerTracker(val id: Int, val name: String, val isLoggedIn: Boolean, val authUrl: String? = null, val statuses: List<ServerTrackStatus>, val scores: List<String>)

@Serializable
data class ServerTrackRecord(val id: Int, val trackerId: Int, val title: String, val lastChapterRead: Double, val status: Int, val displayScore: String)

@Serializable
data class ServerTrackSearch(val title: String, @Serializable(with = ServerLongSerializer::class) val remoteId: Long)

suspend fun SuwayomiSession.serverTrackers(): List<ServerTracker> = result<ServerConnection<ServerTracker>>(
    "query {trackers(first:100) {nodes {id name isLoggedIn authUrl statuses{name value} scores} totalCount pageInfo{hasNextPage endCursor}}}",
    "trackers",
).nodes

suspend fun SuwayomiSession.mangaTracks(mangaId: Int): List<ServerTrackRecord> = result<ServerConnection<ServerTrackRecord>>(
    "query(\$id:Int!) {trackRecords(first:100, condition:{mangaId:\$id}) {nodes {id trackerId title lastChapterRead status displayScore} totalCount pageInfo{hasNextPage endCursor}}}",
    "trackRecords",
    variables("id" to JsonPrimitive(mangaId)),
).nodes

suspend fun SuwayomiSession.searchTracker(trackerId: Int, query: String): List<ServerTrackSearch> {
    @Serializable data class SearchResults(val trackSearches: List<ServerTrackSearch>)
    return result<SearchResults>(
        "query(\$input:SearchTrackerInput!) {searchTracker(input:\$input) {trackSearches{title remoteId}}}",
        "searchTracker",
        variables(
            "input" to buildJsonObject {
                put("trackerId", trackerId)
                put("query", query)
            },
        ),
    ).trackSearches
}

suspend fun SuwayomiSession.trackerLogin(trackerId: Int, username: String, password: String, callback: String?) {
    val operation = if (callback == null) "loginTrackerCredentials" else "loginTrackerOAuth"
    val input = if (callback == null) "LoginTrackerCredentialsInput" else "LoginTrackerOAuthInput"
    execute(
        "mutation(\$input:$input!) {$operation(input:\$input){isLoggedIn}}",
        variables(
            "input" to buildJsonObject {
                put("trackerId", trackerId)
                if (callback == null) {
                    put("username", username)
                    put("password", password)
                } else {
                    put("callbackUrl", callback)
                }
            },
        ),
    )
}

suspend fun SuwayomiSession.bindTrack(mangaId: Int, trackerId: Int, remoteId: Long) {
    execute(
        "mutation(\$input:BindTrackInput!) {bindTrack(input:\$input){trackRecord{id}}}",
        variables(
            "input" to buildJsonObject {
                put("mangaId", mangaId)
                put("trackerId", trackerId)
                put("remoteId", remoteId.toString())
            },
        ),
    )
}

suspend fun SuwayomiSession.unbindTrack(recordId: Int) {
    execute(
        "mutation(\$input:UnbindTrackInput!) {unbindTrack(input:\$input){clientMutationId}}",
        variables(
            "input" to buildJsonObject {
                put("recordId", recordId)
                put("deleteRemoteTrack", false)
            },
        ),
    )
}

suspend fun SuwayomiSession.updateTrack(recordId: Int, chapter: Double, status: Int, score: String) {
    execute(
        "mutation(\$input:UpdateTrackInput!) {updateTrack(input:\$input){trackRecord{id}}}",
        variables(
            "input" to buildJsonObject {
                put("recordId", recordId)
                put("lastChapterRead", chapter)
                put("status", status)
                put("scoreString", score)
            },
        ),
    )
}

// KMK <--
