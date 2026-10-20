// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiMigrationLibraryTest {
    private fun entry(id: Int, sourceId: Long, source: ServerMangaSource? = null) = buildJsonObject {
        put("id", id)
        put("sourceId", sourceId.toString())
        put("source", source?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
    }
    private fun page(nodes: List<JsonElement>, next: Boolean = false, cursor: String? = null) = buildJsonObject {
        put(
            "mangas",
            buildJsonObject {
                put("nodes", kotlinx.serialization.json.JsonArray(nodes))
                put("totalCount", nodes.size)
                put("pageInfo", Json.encodeToJsonElement(ServerPageInfo(next, cursor)))
            },
        )
    }

    @Test
    fun `groups every library page and keeps unavailable and local providers without browsing them`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val source = ServerMangaSource("Disabled provider", "en", "/icon")
        coEvery { session.execute(any(), any()) } answers {
            requests += firstArg<String>() to secondArg<JsonObject>()
            when (secondArg<JsonObject>()["after"]) {
                JsonNull -> page(listOf(entry(1, 100, source), entry(2, 999)), true, "cursor")
                JsonPrimitive("cursor") -> page(listOf(entry(1, 100, source), entry(3, 100, source), entry(4, 0)))
                else -> error("Unexpected cursor")
            }
        }
        val result = session.migrationLibrarySources().associateBy { it.id }
        assertEquals(setOf(100L, 999L, 0L), result.keys)
        assertEquals(2, result.getValue(100).mangaCount)
        assertEquals(source, result.getValue(100).source)
        assertEquals(null, result.getValue(999).source)
        assertEquals(2, requests.size)
        assertTrue(requests.all { "inLibrary: true" in it.first && "first: 100" in it.first })
        assertFalse(requests.any { "mutation" in it.first || "fetch" in it.first || "sources(" in it.first })
    }

    @Test
    fun `rejects repeated missing or empty continuing cursors rather than returning incomplete counts`() = runBlocking<Unit> {
        for (response in listOf(page(listOf(entry(1, 100)), true, "same"), page(listOf(entry(1, 100)), true), page(emptyList(), true, "next"))) {
            val session = mockk<SuwayomiSession>()
            coEvery { session.execute(any(), any()) } returns response
            val error = runCatching { session.migrationLibrarySources() }.exceptionOrNull()
            assertEquals(SuwayomiFailure.CONTRACT, (error as? SuwayomiException)?.failure)
        }
    }

    @Test
    fun `does not publish a partial provider list when a later page fails or identity changes`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        coEvery { session.execute(any(), any()) } returnsMany listOf(page(listOf(entry(1, 100)), true, "next"), page(listOf(entry(1, 200))))
        assertEquals(SuwayomiFailure.CONTRACT, (runCatching { session.migrationLibrarySources() }.exceptionOrNull() as? SuwayomiException)?.failure)
        coEvery { session.execute(any(), any()) } returns page(listOf(entry(1, 100)), true, "next") andThenThrows SuwayomiException(SuwayomiFailure.NETWORK)
        assertEquals(SuwayomiFailure.NETWORK, (runCatching { session.migrationLibrarySources() }.exceptionOrNull() as? SuwayomiException)?.failure)
    }

    @Test
    fun `title picker is scoped to library and provider including source zero and returns alphabetical unique titles`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        val mangas = listOf(ServerManga(2, 0, "zebra", user = ServerMangaUser(true)), ServerManga(1, 0, "Alpha", user = ServerMangaUser(true)))
        coEvery { session.execute(any(), any()) } answers {
            assertEquals(JsonPrimitive("0"), secondArg<JsonObject>()["source"])
            assertTrue("inLibrary: true, sourceId: \$source" in firstArg<String>())
            page((mangas + mangas.first()).map { Json.encodeToJsonElement(it) })
        }
        assertEquals(listOf(1, 2), session.migrationLibraryTitles(0).map { it.id })
    }

    @Test
    fun `rejects title results outside requested library provider`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        for (manga in listOf(ServerManga(1, 200, "Other", user = ServerMangaUser(true)), ServerManga(1, 100, "Removed", user = ServerMangaUser(false)))) {
            coEvery { session.execute(any(), any()) } returns page(listOf(Json.encodeToJsonElement(manga)))
            assertEquals(SuwayomiFailure.CONTRACT, (runCatching { session.migrationLibraryTitles(100) }.exceptionOrNull() as? SuwayomiException)?.failure)
        }
    }
}
// KMK <--
