// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import tachiyomi.core.common.preference.Preference

/** Version 1 allowlist. Orientation, brightness, cutouts, rotation and display hardware stay local. */
class SuwayomiReadingPreferences(private val preferences: ReaderPreferences) {
    private val booleans: Map<String, Preference<Boolean>> get() = mapOf(
        "cropPager" to preferences.cropBorders(),
        "cropWebtoon" to preferences.cropBordersWebtoon(),
        "cropContinuous" to preferences.cropBordersContinuousVertical(),
    )

    suspend fun upload(session: SuwayomiSession) {
        val values = booleans.mapValues { it.value.get().toString() } + ("defaultMode" to preferences.defaultReadingMode().get().toString())
        // Each field is independent: do not overwrite unrelated metadata or preferences.
        session.execute(
            "mutation(\$input:SetGlobalMetasInput!) { setGlobalMetas(input:\$input) { metas {key value} } }",
            variables(
                "input" to buildJsonObject {
                    put(
                        "metas",
                        JsonArray(
                            values.map { (key, value) ->
                                buildJsonObject {
                                    put("key", JsonPrimitive(PREFIX + key))
                                    put("value", JsonPrimitive(value))
                                }
                            },
                        ),
                    )
                },
            ),
        )
    }

    suspend fun apply(session: SuwayomiSession) {
        val data = session.execute("query { metas(first:20, filter:{key:{startsWith:\"$PREFIX\"}}) {nodes{key value} pageInfo{hasNextPage endCursor} totalCount} }")
        val fields = Json { ignoreUnknownKeys = true }.decodeFromJsonElement<ServerConnection<ServerMeta>>(data.getValue("metas")).nodes.associate { it.key.removePrefix(PREFIX) to it.value }
        fields["defaultMode"]?.toIntOrNull()?.takeIf { value -> ReadingMode.entries.any { it != ReadingMode.DEFAULT && it.flagValue == value } }?.let { preferences.defaultReadingMode().set(it) }
        booleans.forEach { (key, preference) -> fields[key]?.toBooleanStrictOrNull()?.let(preference::set) }
    }

    companion object {
        private const val PREFIX = "komikku.reader.v1."
    }
}
// KMK <--
