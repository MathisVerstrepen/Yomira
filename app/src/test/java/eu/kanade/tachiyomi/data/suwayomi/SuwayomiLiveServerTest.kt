// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Opt in only against a disposable server with fixture manga/chapter 1, never a personal library. */
@EnabledIfEnvironmentVariable(named = "KOMIKKU_SUWAYOMI_TEST_URL", matches = ".+")
class SuwayomiLiveServerTest {
    @Test
    fun `supported server authenticates queries images subscriptions and exact chapter state`() = runBlocking {
        val mode = SuwayomiAuthMode.valueOf(System.getenv("KOMIKKU_SUWAYOMI_TEST_AUTH") ?: "NONE")
        val client = SuwayomiClient(
            StoredSession(
                baseUrl = System.getenv("KOMIKKU_SUWAYOMI_TEST_URL"),
                mode = mode,
                username = System.getenv("KOMIKKU_SUWAYOMI_TEST_USER") ?: "",
                password = System.getenv("KOMIKKU_SUWAYOMI_TEST_PASSWORD") ?: "",
            ),
        ) {}
        try {
            client.login()
            val user = client.field<ServerUser>("query { user { id username permissions roles } }", "user")
            assertTrue(user.id > 0)
            val library = client.field<ServerConnection<ServerManga>>(
                "query { mangas(first: 1, condition: {inLibrary: true}) { nodes { ${SuwayomiSession.MANGA_FIELDS} } pageInfo { hasNextPage endCursor } totalCount } }",
                "mangas",
            )
            assertTrue(library.nodes.isNotEmpty())
            assertTrue(client.image(library.nodes.first().thumbnailUrl!!).isNotEmpty())
            val pages = client.field<ServerPages>(
                "mutation { fetchChapterPages(input: {chapterId: 1}) { pages chapter { ${SuwayomiSession.CHAPTER_FIELDS} } syncConflict {deviceName remotePage} } }",
                "fetchChapterPages",
            )
            assertTrue(pages.pages.size >= 3)
            assertTrue(client.image(pages.pages[1]).isNotEmpty())
            val status = withTimeout(15000) { client.subscribe("subscription { downloadChanged { state } }").first() }
            assertTrue(status.containsKey("downloadChanged"))
            try {
                patch(client, false, 2, true)
                val updated = chapter(client)
                assertEquals(2, updated.user.lastPageRead)
                assertEquals(false, updated.user.isRead)
                assertEquals(true, updated.user.isBookmarked)
                patch(client, false, 0, false)
                val reset = chapter(client)
                assertEquals(0, reset.user.lastPageRead)
                assertEquals(false, reset.user.isRead)
                assertEquals(false, reset.user.isBookmarked)
            } finally {
                patch(client, pages.chapter.user.isRead, pages.chapter.user.lastPageRead, pages.chapter.user.isBookmarked)
            }
        } finally {
            client.close()
        }
    }

    private suspend fun chapter(client: SuwayomiClient): ServerChapter = client.field(
        "query { chapter(id: 1) { ${SuwayomiSession.CHAPTER_FIELDS} } }",
        "chapter",
    )

    private suspend fun patch(client: SuwayomiClient, read: Boolean, page: Int, bookmark: Boolean) {
        client.graphql(
            "mutation(\$input: UpdateChapterInput!) { updateChapter(input: \$input) { chapter { id } } }",
            variables(
                "input" to buildJsonObject {
                    put("id", 1)
                    put(
                        "patch",
                        buildJsonObject {
                            put("isRead", read)
                            put("lastPageRead", page)
                            put("isBookmarked", bookmark)
                        },
                    )
                },
            ),
        )
    }
}
// KMK <--
