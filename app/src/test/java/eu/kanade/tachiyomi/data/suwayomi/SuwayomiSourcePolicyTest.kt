// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiSourcePolicyTest {
    private fun source(id: Long, language: String, enabled: Boolean? = null) = ServerSource(id, "Provider", language, "/icon", true, enabled?.let { listOf(ServerMeta("webUI_isEnabled", it.toString())) }.orEmpty())
    private fun policy(value: String) = ServerSourcePolicy.from(listOf(ServerMeta("webUI_browseLanguages", value)))

    @Test
    fun `server English and multilingual allowlist limits actual searched providers`() = runBlocking {
        val sources = listOf(source(1, "en"), source(2, "all"), source(3, "la"), source(4, "ne"), source(5, "en", false), source(0, "localsourcelang"))
        val searched = mutableSetOf<Long>()
        searchServerSources(policy("[\"en\",\"all\"]").select(sources), "query", fetch = { source, _ ->
            searched += source.id
            SourceResults(emptyList(), false)
        }, emit = { _, result -> assertTrue(result.isSuccess) })
        assertEquals(setOf(0L, 1L, 2L), searched)
    }

    @Test
    fun `multilingual is a language value rather than a wildcard and empty means no remote providers`() {
        val sources = listOf(source(1, "en"), source(2, "all"), source(3, "fr"), source(0, "localsourcelang"))
        assertEquals(listOf(2L, 0L), policy("[\"all\"]").select(sources).map { it.id })
        assertEquals(listOf(0L), policy("[]").select(sources).map { it.id })
        assertEquals(listOf(3L, 0L), policy("[\"fr\"]").select(sources).map { it.id })
        assertEquals(listOf(8L), policy("[\"PT_br\"]").select(listOf(source(8, "pt-BR"))).map { it.id })
    }

    @Test
    fun `missing policy keeps unconfigured sources eligible but explicit disabled sources stay excluded`() {
        assertEquals(listOf(1L), ServerSourcePolicy.from(emptyList()).select(listOf(source(1, "en"), source(2, "en", false))).map { it.id })
    }

    @Test
    fun `invalid saved restrictions fail instead of silently searching every language`() {
        for (value in listOf("null", "invalid", "{}", "[42]", "[\"\"]")) {
            val error = assertThrows(SuwayomiException::class.java) { policy(value) }
            assertEquals(SuwayomiFailure.CONTRACT, error.failure)
        }
        assertThrows(SuwayomiException::class.java) { policy("[\"en\"]").allows(source(1, "en").copy(meta = listOf(ServerMeta("webUI_isEnabled", "broken")))) }
    }

    @Test
    fun `server restrictions are reread between searches and cached provider links are rechecked`() = runBlocking {
        val session = mockk<SuwayomiSession>()
        val sources = listOf(source(1, "en"), source(2, "fr"))
        var languages = "[\"en\"]"
        coEvery { session.execute(any(), any()) } coAnswers {
            val query = firstArg<String>()
            when {
                "metas(" in query -> JsonObject(mapOf("metas" to Json.encodeToJsonElement(ServerConnection(listOf(ServerMeta("webUI_browseLanguages", languages)), ServerPageInfo(false), 1))))
                "sources(" in query -> JsonObject(mapOf("sources" to Json.encodeToJsonElement(ServerConnection(sources, ServerPageInfo(false), sources.size))))
                else -> JsonObject(mapOf("source" to Json.encodeToJsonElement(sources.first())))
            }
        }
        assertEquals(listOf(1L), session.browsableServerSources().map { it.id })
        session.requireBrowsableSource(1)
        languages = "[\"fr\"]"
        assertEquals(listOf(2L), session.browsableServerSources().map { it.id })
        var rejected = false
        try {
            session.requireBrowsableSource(1)
        } catch (e: SuwayomiException) {
            rejected = e.failure == SuwayomiFailure.PERMISSION_DENIED
        }
        assertTrue(rejected)
    }
}
// KMK <--
