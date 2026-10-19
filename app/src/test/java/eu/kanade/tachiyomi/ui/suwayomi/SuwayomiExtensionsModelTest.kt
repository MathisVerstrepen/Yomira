// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.tachiyomi.data.suwayomi.ServerExtension
import eu.kanade.tachiyomi.data.suwayomi.ServerUser
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(cafe.adriel.voyager.core.annotation.InternalVoyagerApi::class)
class SuwayomiExtensionsModelTest {
    private val session = mockk<SuwayomiSession>()
    private val backend = mockk<SuwayomiBackend>()
    private val owners = mutableListOf<String>()
    private val extension = ServerExtension("provider.package", "Provider", "en", "1.0", true, false, false, "/icon.png")
    private val response = Json.parseToJsonElement("""{"extensions":{"nodes":[{"pkgName":"provider.package","name":"Provider","lang":"en","versionName":"1.0","isInstalled":true,"hasUpdate":false,"isObsolete":false,"iconUrl":"/icon.png"}],"totalCount":1,"pageInfo":{"hasNextPage":false}}}""").jsonObject

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { backend.requireSession("session") } returns session
        coEvery { session.user() } returns ServerUser(1, "Reader", emptyList(), emptyList())
        coEvery { session.execute(any(), any()) } returns response
    }

    @AfterEach
    fun cleanup() {
        owners.forEach(ScreenModelStore::onDisposeNavigator)
        Dispatchers.resetMain()
    }

    private fun model(): SuwayomiExtensionsModel {
        val owner = "extensions-test-${java.util.UUID.randomUUID()}".also { owners += it }
        return ScreenModelStore.getOrPut(owner, null) { SuwayomiExtensionsModel("session", backend = backend) }
    }

    @Test
    fun `extensions load independently of server settings and repository permissions`() = runBlocking {
        val model = model()
        model.refresh().join()
        assertTrue(model.state.value.hasLoaded)
        assertEquals(listOf(extension), model.state.value.extensions)
        coVerify(exactly = 0) { session.execute(match { it.contains("userSettings") || it.contains("extensionStores") || it.contains("globalUpdateInterval") }, any()) }
    }

    @Test
    fun `returning refresh preserves the list silently and keeps it after network failure`() = runBlocking {
        val model = model()
        model.refresh().join()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { session.user() } coAnswers {
            started.complete(Unit)
            finish.await()
            throw SuwayomiException(SuwayomiFailure.NETWORK)
        }
        val refresh = model.refresh()
        withTimeout(5000) { started.await() }
        assertEquals(listOf(extension), model.state.value.extensions)
        assertFalse(model.state.value.loading)
        finish.complete(Unit)
        refresh.join()
        assertEquals(listOf(extension), model.state.value.extensions)
        assertEquals(SuwayomiFailure.NETWORK, model.state.value.error)
    }
}
// KMK <--
