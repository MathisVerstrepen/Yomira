// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** This separate opt-in performs authenticated reads only; never run the disposable mutation suite on a personal server. */
@EnabledIfEnvironmentVariable(named = "KOMIKKU_SUWAYOMI_READ_ONLY_URL", matches = ".+")
class SuwayomiReadOnlyServerTest {
    @Test
    fun `installed server supports negotiated authentication library covers and status without state writes`() = runBlocking<Unit> {
        val client = SuwayomiClient(
            StoredSession(
                baseUrl = requireNotNull(System.getenv("KOMIKKU_SUWAYOMI_READ_ONLY_URL")),
                mode = SuwayomiAuthMode.valueOf(System.getenv("KOMIKKU_SUWAYOMI_READ_ONLY_AUTH") ?: "UI_LOGIN"),
                username = System.getenv("KOMIKKU_SUWAYOMI_READ_ONLY_USER").orEmpty(),
                password = System.getenv("KOMIKKU_SUWAYOMI_READ_ONLY_PASSWORD").orEmpty(),
            ),
        ) {}
        try {
            client.login()
            client.negotiateContract()
            val user = client.field<ServerUser>("query {user {id username permissions roles}}", "user")
            assertTrue(user.id > 0)
            val info = client.field<ServerInfo>("query {aboutServer {name version}}", "aboutServer")
            assertTrue(info.version.isNotBlank())
            val library = client.field<ServerConnection<ServerManga>>("query {mangas(first:2,condition:{inLibrary:true}) {nodes {${SuwayomiSession.MANGA_FIELDS}} totalCount pageInfo{hasNextPage endCursor}}}", "mangas")
            library.nodes.firstOrNull()?.let { manga ->
                assertTrue(manga.user.inLibrary)
                manga.coverUrl()?.let { assertTrue(client.image(it).isNotEmpty()) }
                client.field<ServerConnection<ServerChapter>>("query(\$id:Int!) {chapters(first:1,condition:{mangaId:\$id}) {nodes {${SuwayomiSession.CHAPTER_FIELDS}} totalCount pageInfo{hasNextPage endCursor}}}", "chapters", variables("id" to JsonPrimitive(manga.id)))
            }
            val session = mockk<SuwayomiSession>()
            coEvery { session.execute(any(), any()) } coAnswers { client.graphql(firstArg(), secondArg()) }
            coEvery { session.user() } returns user
            session.serverCategories()
            session.serverSources()
            session.serverDownloads()
            session.serverTrackers()
            session.extensions()
            session.extensionStores()
            session.userOptions()
            session.updateRunning()
            session.activityPage(true, null)
            session.activityPage(false, null)
            val status = withTimeout(20000) { client.subscribe("subscription {downloadChanged {state}}").first() }
            assertTrue("downloadChanged" in status)
        } finally {
            client.close()
        }
    }
}
// KMK <--
