// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiChapterCountTest {
    private val manga = ServerManga(2, 200, "Story", user = ServerMangaUser(false), url = "/story")
    private fun result(id: Int = 2, sourceId: Long = 200, url: String = "/story", count: Int = 42) = buildJsonObject {
        put(
            "fetchMangaAndChapters",
            buildJsonObject {
                put(
                    "manga",
                    buildJsonObject {
                        put("id", id)
                        put("sourceId", sourceId.toString())
                        put("url", url)
                        put("chapters", buildJsonObject { put("totalCount", count) })
                    },
                )
            },
        )
    }

    private inner class Fixture {
        val session = mockk<SuwayomiSession>()
        var enabled = true
        var response = result()
        var request: JsonObject? = null
        var query = ""
        init {
            coEvery { session.execute(any(), any()) } coAnswers {
                val document = firstArg<String>()
                when {
                    "metas(" in document -> buildJsonObject { put("metas", Json.encodeToJsonElement(ServerConnection<ServerMeta>(emptyList(), ServerPageInfo(false), 0))) }
                    "source(" in document -> buildJsonObject { put("source", Json.encodeToJsonElement(ServerSource(200, "Provider", "en", "", false, listOf(ServerMeta("webUI_isEnabled", enabled.toString()))))) }
                    "MigrationChapterCount" in document -> {
                        request = secondArg<JsonObject>().getValue("input").jsonObject
                        query = document
                        response
                    }
                    else -> error("Unexpected request")
                }
            }
        }
    }

    @Test
    fun `fetches chapters only and returns a count without full manga or chapter payloads`() = runBlocking<Unit> {
        val fixture = Fixture()
        assertEquals(42, fixture.session.migrationChapterCount(manga))
        val input = fixture.request!!
        assertEquals(JsonPrimitive(2), input.getValue("id"))
        assertFalse(input.getValue("fetchManga").jsonPrimitive.boolean)
        assertTrue(input.getValue("fetchChapters").jsonPrimitive.boolean)
        assertTrue("chapters { totalCount }" in fixture.query)
        assertFalse("nodes" in fixture.query || "description" in fixture.query || "user" in fixture.query)
        fixture.response = result(count = 0)
        assertEquals(0, fixture.session.migrationChapterCount(manga))
    }

    @Test
    fun `rejects counts for a different title or invalid count and never treats missing data as zero`() = runBlocking<Unit> {
        val fixture = Fixture()
        for (response in listOf(result(id = 3), result(sourceId = 300), result(url = "/other"), result(count = -1))) {
            fixture.response = response
            val error = runCatching { fixture.session.migrationChapterCount(manga) }.exceptionOrNull()
            assertTrue(error is SuwayomiException)
            assertEquals(SuwayomiFailure.CONTRACT, (error as SuwayomiException).failure)
        }
        fixture.response = buildJsonObject { put("fetchMangaAndChapters", buildJsonObject { put("manga", buildJsonObject { put("id", 2) }) }) }
        assertTrue(runCatching { fixture.session.migrationChapterCount(manga) }.isFailure)
    }

    @Test
    fun `disabled provider is rejected before fetching chapters`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.enabled = false
        val error = runCatching { fixture.session.migrationChapterCount(manga) }.exceptionOrNull()
        assertTrue(error is SuwayomiException)
        assertEquals(SuwayomiFailure.PERMISSION_DENIED, (error as SuwayomiException).failure)
        assertEquals(null, fixture.request)
    }
}
// KMK <--
