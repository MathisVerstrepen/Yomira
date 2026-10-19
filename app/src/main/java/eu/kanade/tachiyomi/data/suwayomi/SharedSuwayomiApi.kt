// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** v2.4.2366 owns one shared library; newer servers expose these fields under user projections. */
internal object SharedSuwayomiApi {
    private val userQuery = Regex("\\s*(?:query(?:\\s+\\w+)?\\s*)?\\{\\s*user\\s*\\{[^{}]*\\}\\s*\\}\\s*")
    private val mangaFields = setOf("inLibrary", "inLibraryAt", "unreadCount", "downloadCount", "bookmarkCount", "lastReadChapter", "latestReadChapter", "firstUnreadChapter")
    private val chapterFields = setOf("isRead", "isBookmarked", "lastPageRead", "lastReadAt", "isDownloaded")

    fun isUserQuery(query: String): Boolean = userQuery.matches(query)

    fun query(query: String): String = flattenUserSelections(query)
        .replace(Regex("\\buserSettings\\s*\\{"), "userSettings: settings {")
        .replace(Regex("\\bsetUserSettings\\s*\\("), "setUserSettings: setSettings(")
        .replace("SetUserSettingsInput", "SetSettingsInput")
        .replace(Regex("\\bupdateStatus\\s*\\{\\s*isRunning\\s*\\}"), "updateStatus: libraryUpdateStatus { jobsInfo { isRunning } }")
        .replace(Regex("\\bdownloadChanged\\s*\\{"), "downloadChanged: downloadStatusChanged(input: {}) {")

    fun variables(query: String, variables: JsonObject): JsonObject {
        if (!Regex("\\bsetUserSettings\\s*\\(").containsMatchIn(query)) return variables
        val input = variables["input"] as? JsonObject ?: return variables
        val settings = input["userSettings"] ?: return variables
        return JsonObject(variables + ("input" to JsonObject(input - "userSettings" + ("settings" to settings))))
    }

    fun data(data: JsonObject): JsonObject = normalize(data) as JsonObject

    private fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::normalize))
        is JsonObject -> {
            val fields = value.mapValues { (key, child) ->
                if (key == "updateStatus" && child is JsonObject && "jobsInfo" in child) normalize(child.getValue("jobsInfo")) else normalize(child)
            }
            val state = when {
                "inLibrary" in fields -> fields.filterKeys { it in mangaFields }
                fields.keys.any { it in chapterFields } -> fields.filterKeys { it in chapterFields }
                else -> emptyMap()
            }
            JsonObject(if (state.isEmpty() || (fields["user"] != null && fields["user"] != JsonNull)) fields else fields + ("user" to JsonObject(state)))
        }
        else -> value
    }

    /** Remove only field wrappers, respecting quoted GraphQL strings and comments. */
    private fun flattenUserSelections(query: String): String {
        val removed = BooleanArray(query.length)
        var index = 0
        while (index < query.length) {
            val skipped = skipLiteral(query, index)
            if (skipped != index) {
                index = skipped
                continue
            }
            if (query[index].isLetter() || query[index] == '_') {
                val start = index++
                while (index < query.length && (query[index].isLetterOrDigit() || query[index] == '_')) index++
                if (query.substring(start, index) != "user") continue
                var open = index
                while (open < query.length && query[open].isWhitespace()) open++
                if (query.getOrNull(open) != '{') continue
                var cursor = open + 1
                var depth = 1
                while (cursor < query.length && depth > 0) {
                    val end = skipLiteral(query, cursor)
                    if (end != cursor) {
                        cursor = end
                        continue
                    }
                    if (query[cursor] == '{') depth++
                    if (query[cursor] == '}') depth--
                    cursor++
                }
                if (depth != 0) throw SuwayomiException(SuwayomiFailure.CONTRACT)
                for (position in start..open) removed[position] = true
                removed[cursor - 1] = true
            } else {
                index++
            }
        }
        return buildString { query.forEachIndexed { position, character -> if (!removed[position]) append(character) } }
    }

    private fun skipLiteral(query: String, start: Int): Int {
        if (query[start] == '#') return query.indexOf('\n', start).let { if (it < 0) query.length else it + 1 }
        if (query[start] != '"') return start
        if (query.startsWith("\"\"\"", start)) return query.indexOf("\"\"\"", start + 3).let { if (it < 0) query.length else it + 3 }
        var index = start + 1
        while (index < query.length) {
            when (query[index++]) {
                '\\' -> index++
                '"' -> return index
            }
        }
        return query.length
    }
}
// KMK <--
