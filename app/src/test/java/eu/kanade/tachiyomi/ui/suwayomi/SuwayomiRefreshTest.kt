// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.suwayomi.LibraryPresentation
import eu.kanade.tachiyomi.data.suwayomi.ServerConnection
import eu.kanade.tachiyomi.data.suwayomi.ServerInfo
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerMangaUser
import eu.kanade.tachiyomi.data.suwayomi.ServerPageInfo
import eu.kanade.tachiyomi.data.suwayomi.ServerUser
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(cafe.adriel.voyager.core.annotation.InternalVoyagerApi::class)
class SuwayomiRefreshTest {
    private val owners = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun cleanup() {
        owners.forEach(ScreenModelStore::onDisposeNavigator)
        Dispatchers.resetMain()
    }

    private fun manga(id: Int) = ServerManga(id, 8, "Manga $id", user = ServerMangaUser(true))
    private fun page(ids: List<Int>, next: String? = null) = ServerConnection(ids.map(::manga), ServerPageInfo(next != null, next), ids.size)

    private inner class Fixture(initial: List<Int>) {
        val session = mockk<SuwayomiSession>()
        val backend = mockk<SuwayomiBackend>()
        val connection = MutableStateFlow<SuwayomiSession?>(session)
        val fetch = AtomicReference<suspend (String?) -> ServerConnection<ServerManga>>({ page(initial) })
        val reads = AtomicInteger()
        val model: SuwayomiScreenModel
        init {
            every { session.id } returns "connection"
            every { session.libraryPresentation() } returns LibraryPresentation()
            coEvery { session.flush() } returns Unit
            coEvery { session.user() } returns ServerUser(1, "Account", emptyList(), emptyList())
            coEvery { session.info() } returns ServerInfo("Server", "test")
            coEvery { session.library(any()) } coAnswers {
                reads.incrementAndGet()
                fetch.get()(firstArg())
            }
            coEvery { session.execute(any(), any()) } returns Json.parseToJsonElement("""{"categories":{"nodes":[],"totalCount":0,"pageInfo":{"hasNextPage":false}}}""") as JsonObject
            every { backend.session } returns connection
            every { backend.requireSession(any()) } answers { connection.value ?: throw SuwayomiException(SuwayomiFailure.CONNECTION_REQUIRED) }
            val owner = "refresh-test-${java.util.UUID.randomUUID()}".also { owners += it }
            model = ScreenModelStore.getOrPut(owner, null) { SuwayomiScreenModel(backend) }
        }
        suspend fun ready() {
            withTimeout(5000) {
                model.state.first { it.hasLoaded }
                model.refresh().join()
            }
        }
    }

    @Test
    fun `navigation refresh retains full library and selection until all replacement pages arrive`() = runBlocking {
        val fixture = Fixture(listOf(1, 2, 3, 4))
        fixture.ready()
        fixture.model.select(4)
        val secondPageStarted = CompletableDeferred<Unit>()
        val secondPage = CompletableDeferred<ServerConnection<ServerManga>>()
        fixture.fetch.set { cursor ->
            if (cursor == null) {
                page(listOf(1), "next")
            } else {
                secondPageStarted.complete(Unit)
                secondPage.await()
            }
        }
        val before = fixture.reads.get()
        val refresh = fixture.model.refresh()
        withTimeout(5000) { secondPageStarted.await() }
        repeat(15) { assertSame(refresh, fixture.model.refresh()) }
        assertEquals(before + 2, fixture.reads.get())
        assertEquals(listOf(1, 2, 3, 4), fixture.model.state.value.mangas.map { it.id })
        assertEquals(setOf(4), fixture.model.state.value.selected)
        assertTrue(fixture.model.state.value.hasLoaded)
        assertFalse(fixture.model.state.value.loading)
        secondPage.complete(page(listOf(2, 3)))
        withTimeout(5000) { refresh.join() }
        assertEquals(listOf(1, 2, 3), fixture.model.state.value.mangas.map { it.id })
        assertTrue(fixture.model.state.value.selected.isEmpty())
    }

    @Test
    fun `failed later page preserves the last complete library`() = runBlocking {
        val fixture = Fixture(listOf(1, 2, 3))
        fixture.ready()
        fixture.fetch.set { cursor -> if (cursor == null) page(listOf(99), "next") else throw SuwayomiException(SuwayomiFailure.NETWORK) }
        withTimeout(5000) { fixture.model.refresh().join() }
        assertEquals(listOf(1, 2, 3), fixture.model.state.value.mangas.map { it.id })
        assertEquals(SuwayomiFailure.NETWORK, fixture.model.state.value.error)
        assertTrue(fixture.model.state.value.hasLoaded)
        assertFalse(fixture.model.state.value.loading)
    }

    @Test
    fun `successfully loaded empty library stays an empty state during background refresh`() = runBlocking {
        val fixture = Fixture(emptyList())
        fixture.ready()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ServerConnection<ServerManga>>()
        fixture.fetch.set {
            started.complete(Unit)
            release.await()
        }
        val refresh = fixture.model.refresh()
        withTimeout(5000) { started.await() }
        assertTrue(fixture.model.state.value.hasLoaded)
        assertFalse(fixture.model.state.value.loading)
        assertTrue(fixture.model.state.value.mangas.isEmpty())
        release.complete(page(emptyList()))
        withTimeout(5000) { refresh.join() }
    }

    @Test
    fun `connection replacement cancels an old refresh and removes its visible snapshot`() = runBlocking {
        val fixture = Fixture(listOf(1, 2, 3))
        fixture.ready()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ServerConnection<ServerManga>>()
        fixture.fetch.set {
            started.complete(Unit)
            release.await()
        }
        val refresh = fixture.model.refresh()
        withTimeout(5000) { started.await() }
        fixture.connection.value = null
        withTimeout(5000) {
            fixture.model.state.first { it.session == null }
            refresh.join()
        }
        release.complete(page(listOf(99)))
        assertTrue(fixture.model.state.value.mangas.isEmpty())
        assertFalse(fixture.model.state.value.hasLoaded)
        assertTrue(refresh.isCancelled)
    }

    @Test
    fun `refreshing a paged activity window retains its depth and append cursor`() = runBlocking {
        val requested = mutableListOf<String?>()
        val window = readServerWindow(pages = 3, keyOf = { value: Int -> value }) { cursor ->
            requested += cursor
            val index = cursor?.toInt() ?: 0
            ServerConnection(listOf(index + 1), ServerPageInfo(true, (index + 1).toString()), 100)
        }
        assertEquals(listOf(null, "1", "2"), requested)
        assertEquals(listOf(1, 2, 3), window.items)
        assertEquals("3", window.cursor)
        assertEquals(3, window.pages)
        assertTrue(window.hasNext)
        val next = readServerWindow(pages = 1, after = window.cursor, keyOf = { value: Int -> value }) { cursor ->
            assertEquals("3", cursor)
            ServerConnection(listOf(4), ServerPageInfo(false, "4"), 1)
        }
        assertEquals(listOf(4), next.items)
    }

    @Test
    fun `cursor cycles abort private snapshot construction`() = runBlocking {
        val result = async {
            runCatching {
                readServerWindow(keyOf = { value: Int -> value }) { cursor ->
                    ServerConnection(listOf(1), ServerPageInfo(true, if (cursor == "a") "b" else "a"), 100)
                }
            }
        }.await()
        assertEquals(SuwayomiFailure.CONTRACT, (result.exceptionOrNull() as SuwayomiException).failure)
    }

    @Test
    fun `routine progress does not move the library but sync problems stay visible`() {
        val outbox = eu.kanade.tachiyomi.data.suwayomi.ChapterOutbox(null) {}
        outbox.enqueue(1, eu.kanade.tachiyomi.data.suwayomi.ChapterIntentKind.PROGRESS, eu.kanade.tachiyomi.data.suwayomi.ChapterPatch(lastPageRead = 2), outbox.ticket(listOf(1)))
        assertFalse(showPendingChanges(outbox.operations(), null, false))
        assertTrue(showPendingChanges(outbox.operations(), null, true))
        assertTrue(showPendingChanges(outbox.operations(), SuwayomiFailure.NETWORK, false))
        outbox.setStatus(outbox.operations().single().id, eu.kanade.tachiyomi.data.suwayomi.ChapterIntentStatus.AMBIGUOUS)
        assertTrue(showPendingChanges(outbox.operations(), null, false))
        outbox.setStatus(outbox.operations().single().id, eu.kanade.tachiyomi.data.suwayomi.ChapterIntentStatus.FAILED)
        assertTrue(showPendingChanges(outbox.operations(), null, false))
    }
}
// KMK <--
