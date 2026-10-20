// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.tachiyomi.data.suwayomi.ServerConnection
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerMangaUser
import eu.kanade.tachiyomi.data.suwayomi.ServerPageInfo
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@OptIn(cafe.adriel.voyager.core.annotation.InternalVoyagerApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SuwayomiMigrationLibraryModelTest {
    private val owners = mutableListOf<String>()

    @BeforeEach fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach fun cleanup() {
        owners.forEach(ScreenModelStore::onDisposeNavigator)
        Dispatchers.resetMain()
    }

    private inner class Fixture {
        val session = mockk<SuwayomiSession>()
        val backend = mockk<SuwayomiBackend>()
        val mangas = AtomicReference((1..3).map { ServerManga(it, 100, "Title $it", user = ServerMangaUser(true)) })
        val fetch = AtomicReference<suspend () -> Unit>({})
        val owner = "library-migration-${UUID.randomUUID()}".also { owners += it }
        val model: SuwayomiMigrationLibraryModel
        init {
            every { backend.requireSession("session") } returns session
            coEvery { session.execute(any(), any()) } coAnswers {
                fetch.get()()
                buildJsonObject { put("mangas", Json.encodeToJsonElement(ServerConnection(mangas.get(), ServerPageInfo(false), mangas.get().size))) }
            }
            model = ScreenModelStore.getOrPut(owner, null) { SuwayomiMigrationLibraryModel("session", 100, backend) }
        }
    }

    @Test
    fun `select all applies to visible matches while queue includes retained selections in title order`() = runBlocking<Unit> {
        val f = Fixture()
        f.model.refresh().join()
        f.model.toggle(3)
        f.model.selectAll(setOf(1, 2, 999))
        assertEquals(listOf(1, 2, 3), f.model.queue())
        f.model.selectAll(setOf(1, 2))
        assertEquals(listOf(3), f.model.queue())
        f.model.toggle(999)
        assertEquals(setOf(3), f.model.state.value.selected)
    }

    @Test
    fun `refresh preserves valid selections prunes removed titles and blocks queue after a failed read`() = runBlocking<Unit> {
        val f = Fixture()
        f.model.refresh().join()
        f.model.selectAll(setOf(1, 2, 3))
        f.mangas.set(f.mangas.get().take(2))
        f.model.refresh().join()
        assertEquals(listOf(1, 2), f.model.queue())
        f.fetch.set { throw SuwayomiException(SuwayomiFailure.NETWORK) }
        f.model.refresh().join()
        assertEquals(setOf(1, 2), f.model.state.value.selected)
        assertTrue(f.model.queue().isEmpty())
        assertEquals(SuwayomiFailure.NETWORK, f.model.state.value.error)
        f.fetch.set { }
        f.model.refresh().join()
        assertEquals(listOf(1, 2), f.model.queue())
    }

    @Test
    fun `in flight refresh deduplicates and prevents changing or launching selected titles`() = runBlocking<Unit> {
        val f = Fixture()
        f.model.refresh().join()
        f.model.toggle(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.fetch.set {
            entered.complete(Unit)
            release.await()
        }
        val job = f.model.refresh()
        entered.await()
        assertTrue(job === f.model.refresh())
        f.model.toggle(2)
        f.model.selectAll(setOf(2, 3))
        assertEquals(setOf(1), f.model.state.value.selected)
        assertTrue(f.model.queue().isEmpty())
        release.complete(Unit)
        job.join()
        assertEquals(listOf(1), f.model.queue())
    }

    @Test
    fun `changing session while loading cannot publish library titles`() = runBlocking<Unit> {
        val f = Fixture()
        f.fetch.set { every { f.backend.requireSession("session") } throws SuwayomiException(SuwayomiFailure.SESSION_CHANGED) }
        f.model.refresh().join()
        assertFalse(f.model.state.value.hasLoaded)
        assertTrue(f.model.state.value.mangas.isEmpty())
        assertEquals(SuwayomiFailure.SESSION_CHANGED, f.model.state.value.error)
    }

    @Test
    fun `disposed screen ignores even a non cooperative late response`() = runBlocking<Unit> {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.fetch.set {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val job = f.model.refresh()
        entered.await()
        ScreenModelStore.onDisposeNavigator(f.owner)
        release.complete(Unit)
        job.join()
        assertFalse(f.model.state.value.hasLoaded)
        assertTrue(f.model.state.value.mangas.isEmpty())
    }
}
// KMK <--
