// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.suwayomi.ServerChapter
import eu.kanade.tachiyomi.data.suwayomi.ServerChapterCount
import eu.kanade.tachiyomi.data.suwayomi.ServerChapterMangaIdentity
import eu.kanade.tachiyomi.data.suwayomi.ServerChapterUser
import eu.kanade.tachiyomi.data.suwayomi.ServerConnection
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerMangaUser
import eu.kanade.tachiyomi.data.suwayomi.ServerMeta
import eu.kanade.tachiyomi.data.suwayomi.ServerPageInfo
import eu.kanade.tachiyomi.data.suwayomi.ServerSource
import eu.kanade.tachiyomi.data.suwayomi.SourceMigrationOptions
import eu.kanade.tachiyomi.data.suwayomi.SourceResults
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@OptIn(cafe.adriel.voyager.core.annotation.InternalVoyagerApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SuwayomiMigrationScreenModelTest {
    private val owners = mutableListOf<String>()

    @BeforeEach fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach fun cleanup() {
        owners.forEach(ScreenModelStore::onDisposeNavigator)
        Dispatchers.resetMain()
    }

    private fun manga(id: Int) = ServerManga(id, if (id == 1) 100 else 200, "Story $id", user = ServerMangaUser(id == 1), url = "/manga/$id")
    private fun chapter(mangaId: Int) = ServerChapter(mangaId * 10, mangaId, "Chapter 1", 1.0, 0, pageCount = 20, user = ServerChapterUser(false, true, 5, 0, false), url = "/chapter/$mangaId", manga = ServerChapterMangaIdentity(if (mangaId == 1) 100 else 200, "/manga/$mangaId"))

    private inner class Fixture(additionalSources: List<ServerSource> = emptyList()) {
        val source = ServerSource(200, "Provider", "en", "", false)
        val sources = listOf(source) + additionalSources
        val session = mockk<SuwayomiSession>()
        val backend = mockk<SuwayomiBackend>()
        val pages = CopyOnWriteArrayList<Int>()
        val fetch = AtomicReference<suspend (Int) -> SourceResults>({ SourceResults(listOf(manga(2)), false) })
        val requests = CopyOnWriteArrayList<Triple<Long, Int, String>>()
        val fetchAll = AtomicReference<suspend (Long, Int, String) -> SourceResults>({ _, page, _ -> fetch.get()(page) })
        val countRequests = CopyOnWriteArrayList<Int>()
        val countFetch = AtomicReference<suspend (Int) -> Int>({ 40 })
        val refresh = AtomicReference<suspend () -> Unit>({})
        val model: SuwayomiMigrationScreenModel
        init {
            every { backend.requireSession(any()) } returns session
            every { session.pending } returns MutableStateFlow(emptyList())
            coEvery { session.flush() } returns Unit
            coEvery { session.manga(any()) } answers { manga(firstArg()) }
            coEvery { session.chapters(any()) } answers { listOf(chapter(firstArg())) }
            coEvery { session.refreshManga(any()) } coAnswers { refresh.get()() }
            coEvery { session.execute(any(), any()) } coAnswers {
                val query = firstArg<String>()
                when {
                    "metas(" in query -> buildJsonObject { put("metas", Json.encodeToJsonElement(ServerConnection<ServerMeta>(emptyList(), ServerPageInfo(false), 0))) }
                    "sources(" in query -> buildJsonObject { put("sources", Json.encodeToJsonElement(ServerConnection(sources, ServerPageInfo(false), sources.size))) }
                    "source(" in query -> {
                        val id = secondArg<kotlinx.serialization.json.JsonObject>().getValue("id").jsonPrimitive.content.toLong()
                        buildJsonObject { put("source", Json.encodeToJsonElement(sources.first { it.id == id })) }
                    }
                    "MigrationChapterCount" in query -> {
                        val id = secondArg<kotlinx.serialization.json.JsonObject>().getValue("input").jsonObject.getValue("id").jsonPrimitive.int
                        countRequests += id
                        val count = countFetch.get()(id)
                        buildJsonObject { put("fetchMangaAndChapters", buildJsonObject { put("manga", Json.encodeToJsonElement(manga(id).copy(chapters = ServerChapterCount(count)))) }) }
                    }
                    "fetchSourceManga(" in query -> {
                        val input = secondArg<kotlinx.serialization.json.JsonObject>().getValue("input").jsonObject
                        val page = input.getValue("page").jsonPrimitive.int
                        val sourceId = input.getValue("source").jsonPrimitive.content.toLong()
                        val title = input.getValue("query").jsonPrimitive.content
                        pages += page
                        requests += Triple(sourceId, page, title)
                        val result = fetchAll.get()(sourceId, page, title)
                        buildJsonObject { put("fetchSourceManga", Json.encodeToJsonElement(result)) }
                    }
                    else -> error("Unexpected query")
                }
            }
            val owner = "migration-ui-${UUID.randomUUID()}".also { owners += it }
            model = ScreenModelStore.getOrPut(owner, null) { SuwayomiMigrationScreenModel("session", 1, backend, mockk<BasePreferences>()) }
        }
        suspend fun idle() {
            withTimeout(5000) { model.state.first { !it.loading } }
        }
        suspend fun select() {
            idle()
            model.select(source)
            idle()
        }
    }

    @Test
    fun `preview retry reloads selected title and retains search results`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.select()
        fixture.refresh.set { throw SuwayomiException(SuwayomiFailure.NETWORK) }
        fixture.model.preview(manga(2))
        fixture.idle()
        assertEquals(MigrationOperation.PREVIEW, fixture.model.state.value.failedOperation)
        assertEquals(2, fixture.model.state.value.candidate?.id)
        fixture.refresh.set { }
        fixture.model.retry()
        fixture.idle()
        assertNotNull(fixture.model.state.value.preview)
        assertEquals(listOf(2), fixture.model.state.value.results.map { it.id })
        assertEquals(listOf(1), fixture.pages)
    }

    @Test
    fun `failed later page retries the same page and preserves earlier results`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.fetch.set { page -> if (page == 1) SourceResults(listOf(manga(2)), true) else throw SuwayomiException(SuwayomiFailure.NETWORK) }
        fixture.select()
        fixture.model.search(next = true)
        fixture.idle()
        assertEquals(listOf(2), fixture.model.state.value.results.map { it.id })
        assertEquals(1, fixture.model.state.value.page)
        fixture.fetch.set { SourceResults(listOf(manga(3)), false) }
        fixture.model.retry()
        fixture.idle()
        assertEquals(listOf(1, 2, 2), fixture.pages)
        assertEquals(listOf(2, 3), fixture.model.state.value.results.map { it.id })
    }

    @Test
    fun `back during search cancels and returns to providers in one action`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.idle()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<SourceResults>()
        fixture.fetch.set {
            started.complete(Unit)
            release.await()
        }
        fixture.model.select(fixture.source)
        withTimeout(5000) { started.await() }
        assertTrue(fixture.model.back())
        assertNull(fixture.model.state.value.source)
        assertFalse(fixture.model.state.value.loading)
        assertFalse(fixture.model.state.value.stopped)
        release.complete(SourceResults(listOf(manga(2)), false))
    }

    @Test
    fun `cancel preparing preview returns to existing title results`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.select()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.refresh.set {
            started.complete(Unit)
            release.await()
        }
        fixture.model.preview(manga(2))
        withTimeout(5000) { started.await() }
        assertTrue(fixture.model.back())
        assertEquals(fixture.source, fixture.model.state.value.source)
        assertEquals(listOf(2), fixture.model.state.value.results.map { it.id })
        assertNull(fixture.model.state.value.preview)
        assertFalse(fixture.model.state.value.stopped)
        release.complete(Unit)
    }

    @Test
    fun `reloading preview preserves chosen transfer options`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.select()
        fixture.model.preview(manga(2))
        fixture.idle()
        val options = SourceMigrationOptions(bookmarks = false, keepOriginal = false)
        fixture.model.options(options)
        fixture.refresh.set { throw SuwayomiException(SuwayomiFailure.NETWORK) }
        fixture.model.preview(manga(2))
        fixture.idle()
        fixture.refresh.set { }
        fixture.model.retry()
        fixture.idle()
        assertEquals(options, fixture.model.state.value.options)
    }

    @Test
    fun `all provider results stream and can be previewed before slow requests finish`() = runBlocking<Unit> {
        val fixture = Fixture(listOf(ServerSource(300, "Slow", "en", "", false)))
        fixture.idle()
        val slow = CompletableDeferred<SourceResults>()
        fixture.fetchAll.set { sourceId, _, _ ->
            if (sourceId == 300L) slow.await() else SourceResults(listOf(manga(2)), false)
        }
        fixture.model.selectAll()
        withTimeout(5000) { fixture.model.state.first { it.providerResults.any { p -> p.mangas.isNotEmpty() } } }
        assertTrue(fixture.model.state.value.loading)
        fixture.model.preview(manga(2))
        fixture.idle()
        assertNotNull(fixture.model.state.value.preview)
        assertTrue(fixture.model.back())
        assertTrue(fixture.model.state.value.allProviders)
        assertEquals(listOf(2), fixture.model.state.value.providerResults.flatMap { it.mangas }.map { it.id })
        fixture.fetchAll.set { sourceId, _, _ -> SourceResults(listOf(manga(4).copy(sourceId = sourceId)), false) }
        fixture.model.resumeAll()
        fixture.idle()
        assertEquals(1, fixture.requests.count { it.first == 200L })
        assertEquals(1, fixture.model.state.value.providerResults.first { it.source.id == 300L }.page)
        slow.complete(SourceResults(emptyList(), false))
    }

    @Test
    fun `provider failure is isolated and pagination retry uses original query and page`() = runBlocking<Unit> {
        val fixture = Fixture(listOf(ServerSource(300, "Secondary", "en", "", false)))
        fixture.idle()
        fixture.fetchAll.set { sourceId, _, _ ->
            if (sourceId == 300L) throw SuwayomiException(SuwayomiFailure.NETWORK)
            SourceResults(listOf(manga(2)), true)
        }
        fixture.model.selectAll()
        fixture.idle()
        assertNull(fixture.model.state.value.error)
        assertEquals(SuwayomiFailure.NETWORK, fixture.model.state.value.providerResults.last().error)
        fixture.fetchAll.set { sourceId, _, _ -> SourceResults(listOf(manga(4).copy(sourceId = sourceId)), false) }
        fixture.model.resumeAll()
        fixture.idle()
        assertEquals(1, fixture.requests.count { it.first == 200L })
        fixture.fetchAll.set { _, _, _ -> throw SuwayomiException(SuwayomiFailure.NETWORK) }
        fixture.model.query("Edited but not submitted")
        fixture.model.providerPage(200)
        fixture.idle()
        assertEquals(listOf(2), fixture.model.state.value.providerResults.first().mangas.map { it.id })
        fixture.fetchAll.set { _, _, _ -> SourceResults(listOf(manga(3)), false) }
        fixture.model.providerPage(200)
        fixture.idle()
        assertEquals(listOf(Triple(200L, 2, "Story 1"), Triple(200L, 2, "Story 1")), fixture.requests.takeLast(2))
        assertEquals(listOf(2, 3), fixture.model.state.value.providerResults.first().mangas.map { it.id })
    }

    @Test
    fun `new all provider query cancels old responses and back returns to provider picker`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.idle()
        val oldStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<SourceResults>()
        fixture.fetchAll.set { _, _, query ->
            if (query == "Story 1") {
                oldStarted.complete(Unit)
                oldResult.await()
            } else {
                SourceResults(listOf(manga(3)), false)
            }
        }
        fixture.model.selectAll()
        withTimeout(5000) { oldStarted.await() }
        fixture.model.query("New title")
        fixture.model.search()
        fixture.idle()
        oldResult.complete(SourceResults(listOf(manga(2)), false))
        assertEquals("New title", fixture.model.state.value.searchedQuery)
        assertEquals(listOf(3), fixture.model.state.value.providerResults.single().mangas.map { it.id })
        assertTrue(fixture.model.back())
        assertFalse(fixture.model.state.value.allProviders)
        assertNull(fixture.model.state.value.source)
    }

    @Test
    fun `all provider search excludes original local and disabled providers`() = runBlocking<Unit> {
        val fixture = Fixture(
            listOf(
                ServerSource(100, "Original", "en", "", false),
                ServerSource(0, "Local", "en", "", false),
                ServerSource(300, "Disabled", "en", "", false, listOf(ServerMeta("webUI_isEnabled", "false"))),
            ),
        )
        fixture.idle()
        fixture.model.selectAll()
        fixture.idle()
        assertEquals(listOf(200L), fixture.requests.map { it.first })
    }

    @Test
    fun `count loading is limited to visible search results and skips known counts`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.fetch.set { SourceResults(listOf(manga(2), manga(3), manga(4).copy(chapters = ServerChapterCount(50))), false) }
        fixture.select()
        assertTrue(fixture.countRequests.isEmpty())
        fixture.model.visibleTitles(setOf(2, 4, 999))
        withTimeout(5000) { fixture.model.chapterCounts.state.first { it[manga(2).migrationCountKey()]?.count == 40 } }
        assertEquals(listOf(2), fixture.countRequests)
        fixture.model.visibleTitles(emptySet())
        fixture.model.visibleTitles(setOf(2))
        assertEquals(listOf(2), fixture.countRequests)
        assertTrue(fixture.model.back())
        fixture.model.visibleTitles(setOf(3))
        assertEquals(listOf(2), fixture.countRequests)
    }

    @Test
    fun `preview cancels count requests and caches the fresh chapter count for returning to titles`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.select()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Int>()
        fixture.countFetch.set {
            started.complete(Unit)
            release.await()
        }
        fixture.model.visibleTitles(setOf(2))
        withTimeout(5000) { started.await() }
        fixture.model.preview(manga(2))
        fixture.idle()
        assertNotNull(fixture.model.state.value.preview)
        release.complete(999)
        assertTrue(fixture.model.back())
        fixture.model.visibleTitles(setOf(2))
        assertEquals(1, fixture.model.chapterCounts.state.value.getValue(manga(2).migrationCountKey()).count)
        assertEquals(listOf(2), fixture.countRequests)
    }

    @Test
    fun `visible count loads independently while slower providers are still searching`() = runBlocking<Unit> {
        val fixture = Fixture(listOf(ServerSource(300, "Slow", "en", "", false)))
        fixture.idle()
        val slow = CompletableDeferred<SourceResults>()
        fixture.fetchAll.set { sourceId, _, _ -> if (sourceId == 300L) slow.await() else SourceResults(listOf(manga(2)), false) }
        fixture.model.selectAll()
        withTimeout(5000) { fixture.model.state.first { it.providerResults.any { provider -> provider.mangas.any { manga -> manga.id == 2 } } } }
        fixture.model.visibleTitles(setOf(2))
        withTimeout(5000) { fixture.model.chapterCounts.state.first { it[manga(2).migrationCountKey()]?.count == 40 } }
        assertTrue(fixture.model.state.value.loading)
        assertEquals(listOf(2), fixture.countRequests)
        assertTrue(fixture.model.back())
        slow.complete(SourceResults(emptyList(), false))
    }
}
// KMK <--
