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
data class ServerExtension(val pkgName: String, val name: String, val lang: String, val versionName: String, val isInstalled: Boolean, val hasUpdate: Boolean, val isObsolete: Boolean, val iconUrl: String = "")

@Serializable
data class ServerExtensionStore(val name: String, val indexUrl: String)

@Serializable
data class ServerPreference(
    @SerialName("__typename") val type: String,
    val key: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val valueBool: Boolean? = null,
    val defaultBool: Boolean? = null,
    val valueString: String? = null,
    val defaultString: String? = null,
    val valueList: List<String>? = null,
    val defaultList: List<String>? = null,
    val entries: List<String> = emptyList(),
    val entryValues: List<String> = emptyList(),
) {
    /** Android list preferences use the selected entry's label in their summary template. */
    fun displaySummary(): String? {
        if (type != "ListPreference") return summary
        val value = valueString ?: defaultString.orEmpty()
        val label = entries.getOrNull(entryValues.indexOf(value)) ?: value
        return summary?.replace("%1\$s", label)?.replace("%s", label)
    }

    fun sameSchema(other: ServerPreference): Boolean = type == other.type && key == other.key && entryValues == other.entryValues
}

@Serializable
data class ServerUserOptions(
    val excludeUnreadChapters: Boolean,
    val excludeNotStarted: Boolean,
    val excludeCompleted: Boolean,
    val updateMangas: Boolean,
    val autoDownloadNewChapters: Boolean,
    val excludeEntryWithUnreadChapters: Boolean,
    val autoDownloadIgnoreReUploads: Boolean,
    val autoDownloadNewChaptersLimit: Int,
)

private val adminJson = Json { ignoreUnknownKeys = true }
private suspend inline fun <reified T> SuwayomiSession.adminResult(query: String, field: String, variables: JsonObject = JsonObject(emptyMap())): T = adminJson.decodeFromJsonElement(execute(query, variables)[field] ?: throw SuwayomiException(SuwayomiFailure.CONTRACT))

fun ServerUser.allowed(permission: String): Boolean = "ADMIN" in roles || permission in permissions
private suspend fun SuwayomiSession.requirePermission(permission: String) {
    if (!user().allowed(permission)) throw SuwayomiException(SuwayomiFailure.PERMISSION_DENIED)
}

private suspend inline fun <reified T> SuwayomiSession.adminPages(field: String, fields: String): List<T> {
    val all = mutableListOf<T>()
    var cursor: String? = null
    do {
        val page = adminResult<ServerConnection<T>>(
            "query(\$after:Cursor) {$field(first:100,after:\$after) {nodes{$fields} totalCount pageInfo{hasNextPage endCursor}}}",
            field,
            variables("after" to (cursor?.let(::JsonPrimitive) ?: JsonNull)),
        )
        all.addAll(page.nodes)
        if (!page.pageInfo.hasNextPage) break
        val next = page.pageInfo.endCursor
        if (next == null || next == cursor || page.nodes.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        cursor = next
    } while (true)
    return all
}

suspend fun SuwayomiSession.extensions(): List<ServerExtension> = adminPages("extensions", "pkgName name lang versionName isInstalled hasUpdate isObsolete iconUrl")
suspend fun SuwayomiSession.extensionStores(): List<ServerExtensionStore> = adminPages("extensionStores", "name indexUrl")

/** Fetch all languages here: configuration must remain accessible for hidden browsing sources. */
suspend fun SuwayomiSession.extensionSources(pkgName: String): List<ServerSource> {
    @Serializable data class Sources(val nodes: List<ServerSource>)

    @Serializable data class ExtensionSources(val source: Sources)
    return adminResult<ExtensionSources>(
        "query(\$pkgName:String!) {extension(pkgName:\$pkgName) {source {nodes {id name lang iconUrl supportsLatest}}}}",
        "extension",
        variables("pkgName" to JsonPrimitive(pkgName)),
    ).source.nodes
}

suspend fun SuwayomiSession.refreshExtensions() {
    execute("mutation {fetchExtensions(input:{}) {clientMutationId}}")
}

suspend fun SuwayomiSession.changeExtension(pkg: String, action: String) {
    require(action in listOf("install", "update", "uninstall"))
    requirePermission(if (action == "uninstall") "UNINSTALL_EXTENSIONS" else "INSTALL_EXTENSIONS")
    execute(
        "mutation(\$input:UpdateExtensionInput!) {updateExtension(input:\$input) {clientMutationId}}",
        variables(
            "input" to buildJsonObject {
                put("id", pkg)
                put("patch", buildJsonObject { put(action, true) })
            },
        ),
    )
}

suspend fun SuwayomiSession.changeExtensionStore(indexUrl: String, add: Boolean) {
    requirePermission("MANAGE_EXTENSION_STORES")
    val action = if (add) "addExtensionStore" else "removeExtensionStore"
    val type = if (add) "AddExtensionStoreInput" else "RemoveExtensionStoreInput"
    execute("mutation(\$input:$type!) {$action(input:\$input) {clientMutationId}}", variables("input" to buildJsonObject { put("indexUrl", indexUrl) }))
}

private const val PREFERENCE_COMMON = "key title summary visible enabled"
internal val SOURCE_PREFERENCE_FIELDS = """
    __typename
    ... on SwitchPreference {$PREFERENCE_COMMON valueBool:currentValue defaultBool:default}
    ... on CheckBoxPreference {$PREFERENCE_COMMON valueBool:currentValue defaultBool:default}
    ... on EditTextPreference {$PREFERENCE_COMMON valueString:currentValue defaultString:default}
    ... on ListPreference {$PREFERENCE_COMMON valueString:currentValue defaultString:default entries entryValues}
    ... on MultiSelectListPreference {$PREFERENCE_COMMON valueList:currentValue defaultList:default entries entryValues}
""".trimIndent()

suspend fun SuwayomiSession.sourcePreferences(sourceId: Long): List<ServerPreference> {
    @Serializable data class SourceOptions(val preferences: List<ServerPreference>)
    return adminResult<SourceOptions>("query(\$id:LongString!) {source(id:\$id) {preferences{$SOURCE_PREFERENCE_FIELDS}}}", "source", variables("id" to JsonPrimitive(sourceId.toString()))).preferences
}

suspend fun SuwayomiSession.changeSourcePreference(sourceId: Long, position: Int, expected: ServerPreference, field: String, value: JsonElement) {
    requirePermission("MANAGE_SOURCE_PREFERENCES")
    val actual = sourcePreferences(sourceId).getOrNull(position)
    if (actual == null || !actual.sameSchema(expected) || !actual.enabled || !actual.visible) throw SuwayomiException(SuwayomiFailure.CONTRACT)
    val expectedField = when (actual.type) {
        "SwitchPreference" -> "switchState"
        "CheckBoxPreference" -> "checkBoxState"
        "EditTextPreference" -> "editTextState"
        "ListPreference" -> "listState"
        "MultiSelectListPreference" -> "multiSelectState"
        else -> throw SuwayomiException(SuwayomiFailure.CONTRACT)
    }
    require(field == expectedField)
    execute(
        "mutation(\$input:UpdateSourcePreferenceInput!) {updateSourcePreference(input:\$input) {clientMutationId}}",
        variables(
            "input" to buildJsonObject {
                put("sourceId", sourceId.toString())
                put(
                    "change",
                    buildJsonObject {
                        put("position", position)
                        put(field, value)
                    },
                )
            },
        ),
    )
}

internal const val USER_OPTION_FIELDS = "excludeUnreadChapters excludeNotStarted excludeCompleted updateMangas autoDownloadNewChapters excludeEntryWithUnreadChapters autoDownloadIgnoreReUploads autoDownloadNewChaptersLimit"
suspend fun SuwayomiSession.userOptions(): ServerUserOptions = adminResult("query {userSettings {$USER_OPTION_FIELDS}}", "userSettings")

suspend fun SuwayomiSession.changeUserOption(field: String, value: JsonElement) {
    require(field in USER_OPTION_FIELDS.split(' '))
    execute("mutation(\$input:SetUserSettingsInput!) {setUserSettings(input:\$input) {clientMutationId}}", variables("input" to buildJsonObject { put("userSettings", buildJsonObject { put(field, value) }) }))
}

suspend fun SuwayomiSession.updateInterval(): Double {
    @Serializable data class Interval(val globalUpdateInterval: Double)
    requirePermission("MANAGE_SETTINGS")
    return adminResult<Interval>("query {settings {globalUpdateInterval}}", "settings").globalUpdateInterval
}

suspend fun SuwayomiSession.setUpdateInterval(hours: Double) {
    require(hours == 0.0 || (hours >= 6.0 && hours.isFinite()))
    requirePermission("MANAGE_SETTINGS")
    execute("mutation(\$input:SetSettingsInput!) {setSettings(input:\$input) {clientMutationId}}", variables("input" to buildJsonObject { put("settings", buildJsonObject { put("globalUpdateInterval", hours) }) }))
}
// KMK <--
