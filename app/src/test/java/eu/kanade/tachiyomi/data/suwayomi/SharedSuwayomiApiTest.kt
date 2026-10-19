// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedSuwayomiApiTest {
    @Test
    fun `shared chapter state and nested last read state retain the modern projection shape`() {
        val data = Json.parseToJsonElement("""{"manga":{"inLibrary":true,"unreadCount":9,"lastReadChapter":{"lastReadAt":"1792353910"},"chapters":{"nodes":[{"id":41,"isRead":false,"lastPageRead":7,"isBookmarked":true}]}}}""").jsonObject
        val manga = SharedSuwayomiApi.data(data).getValue("manga").jsonObject
        assertEquals("9", manga.getValue("user").jsonObject.getValue("unreadCount").jsonPrimitive.content)
        val lastRead = manga.getValue("lastReadChapter").jsonObject
        assertEquals("1792353910", lastRead.getValue("user").jsonObject.getValue("lastReadAt").jsonPrimitive.content)
        assertTrue(manga.toString().contains("\"lastPageRead\":7"))
        assertTrue(manga.toString().contains("\"isBookmarked\":true"))
    }

    @Test
    fun `flattening removes nested user selections and preserves literals and comments`() {
        val query = """query { manga(id:1) { user { inLibrary lastReadChapter { user {lastReadAt} } } } source(query:"user { untouched }") {id} } # user { comment }"""
        val wire = SharedSuwayomiApi.query(query)
        assertFalse(wire.contains("user { inLibrary"))
        assertFalse(wire.contains("user {lastReadAt"))
        assertTrue(wire.contains("\"user { untouched }\""))
        assertTrue(wire.endsWith("# user { comment }"))
    }

    @Test
    fun `settings and subscription adapters use the stable schema without altering original variables`() {
        val query = "mutation(\$input:SetUserSettingsInput!){setUserSettings(input:\$input){clientMutationId}}"
        val variables = Json.parseToJsonElement("""{"input":{"clientMutationId":"correlation","userSettings":{"updateMangas":false}}}""").jsonObject
        assertEquals("false", SharedSuwayomiApi.variables(query, variables).getValue("input").jsonObject.getValue("settings").jsonObject.getValue("updateMangas").jsonPrimitive.content)
        assertTrue("userSettings" in variables.getValue("input").jsonObject)
        assertTrue(SharedSuwayomiApi.query(query).contains("setUserSettings: setSettings"))
        assertTrue(SharedSuwayomiApi.query("subscription {downloadChanged {state}}").contains("downloadStatusChanged(input: {})"))
        val status = SharedSuwayomiApi.data(Json.parseToJsonElement("""{"updateStatus":{"jobsInfo":{"isRunning":false}}}""").jsonObject)
        assertEquals("false", status.getValue("updateStatus").jsonObject.getValue("isRunning").jsonPrimitive.content)
    }
}
// KMK <--
