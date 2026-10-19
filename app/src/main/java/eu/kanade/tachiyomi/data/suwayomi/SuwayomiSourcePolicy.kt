// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** WebUI stores browse restrictions on the server, independently of installed extensions. */
internal data class ServerSourcePolicy(val languages: Set<String>?) {
    fun allows(source: ServerSource): Boolean {
        // The server WebUI keeps its local source available regardless of browse language filters.
        if (source.id == 0L) return true
        val enabled = source.meta.firstOrNull { it.key == "webUI_isEnabled" }?.value?.let {
            it.toBooleanStrictOrNull() ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)
        } ?: true
        return enabled && (languages == null || normalize(source.lang) in languages)
    }

    fun select(sources: List<ServerSource>): List<ServerSource> = sources.filter(::allows)

    companion object {
        private fun normalize(language: String) = language.trim().replace('_', '-').lowercase(java.util.Locale.ROOT)

        fun from(metadata: List<ServerMeta>): ServerSourcePolicy {
            val raw = metadata.firstOrNull { it.key == "webUI_browseLanguages" }?.value ?: return ServerSourcePolicy(null)
            try {
                val values = Json.parseToJsonElement(raw) as? JsonArray ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)
                val languages = values.map { item ->
                    val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)
                    normalize(text)
                }.toSet()
                // "all" denotes multilingual providers, not a wildcard for every language.
                return ServerSourcePolicy(languages)
            } catch (e: Exception) {
                if (e is SuwayomiException) throw e
                throw SuwayomiException(SuwayomiFailure.CONTRACT)
            }
        }
    }
}

private val sourcePolicyJson = Json { ignoreUnknownKeys = true }

internal suspend fun SuwayomiSession.sourceBrowsePolicy(): ServerSourcePolicy {
    val data = execute("query { metas(first:1, condition:{key:\"webUI_browseLanguages\"}) {nodes{key value} totalCount pageInfo{hasNextPage endCursor}} }")
    val meta = sourcePolicyJson.decodeFromJsonElement<ServerConnection<ServerMeta>>(data.getValue("metas"))
    return ServerSourcePolicy.from(meta.nodes)
}

internal suspend fun SuwayomiSession.browsableServerSources(): List<ServerSource> = sourceBrowsePolicy().select(serverSources())

internal suspend fun SuwayomiSession.requireBrowsableSource(id: Long) {
    val policy = sourceBrowsePolicy()
    val data = execute("query(\$id:LongString!) { source(id:\$id) { id name lang iconUrl supportsLatest meta{key value} } }", variables("id" to JsonPrimitive(id.toString())))
    val source = sourcePolicyJson.decodeFromJsonElement<ServerSource>(data.getValue("source"))
    if (source.id != id || !policy.allows(source)) throw SuwayomiException(SuwayomiFailure.PERMISSION_DENIED)
}
// KMK <--
