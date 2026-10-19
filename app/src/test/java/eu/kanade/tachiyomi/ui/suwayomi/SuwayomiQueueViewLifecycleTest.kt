// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.download.model.DownloadDisplay
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import eu.kanade.tachiyomi.ui.download.DownloadAdapter
import eu.kanade.tachiyomi.ui.download.DownloadHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(cafe.adriel.voyager.core.annotation.InternalVoyagerApi::class)
class SuwayomiQueueViewLifecycleTest {
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

    private fun controller(): SuwayomiDownloadQueueController {
        val service = mockk<SuwayomiDownloadsModel>()
        every { service.state } returns MutableStateFlow(SuwayomiDownloadsModel.State())
        val owner = "queue-view-test-${java.util.UUID.randomUUID()}".also { owners += it }
        return ScreenModelStore.getOrPut(owner, null) {
            SuwayomiDownloadQueueController("session", service, mockk<Context>(), mockk<Navigator>())
        }
    }

    private fun binding(root: RecyclerView) = mockk<DownloadListBinding>().also {
        every { it.root } returns root
    }

    @Test
    fun `progress before view attachment and after detachment is harmless`() {
        val controller = controller()
        val download = mockk<DownloadDisplay>()
        // This used to depend on a lateinit binding initialized only by AndroidView.factory.
        assertNull(controller.controllerBinding)
        controller.onUpdateDownloadedPages(download)
        controller.onStatusChange(download)

        val root = mockk<RecyclerView>()
        val holder = mockk<DownloadHolder>(relaxed = true)
        every { download.chapter.id } returns 7L
        every { root.findViewHolderForItemId(7L) } returns holder
        controller.attachView(binding(root), mockk<DownloadAdapter>())
        controller.onUpdateDownloadedPages(download)
        verify(exactly = 1) { holder.bind(download) }

        controller.detachView(root)
        controller.onUpdateDownloadedPages(download)
        controller.onStatusChange(download)
        assertNull(controller.controllerBinding)
        assertNull(controller.adapter)
        verify(exactly = 1) { holder.bind(download) }
    }

    @Test
    fun `late release of an old Android view cannot detach its replacement`() {
        val controller = controller()
        val oldRoot = mockk<RecyclerView>()
        controller.attachView(binding(oldRoot), mockk<DownloadAdapter>())
        val newRoot = mockk<RecyclerView>()
        val newBinding = binding(newRoot)
        val newAdapter = mockk<DownloadAdapter>()
        controller.attachView(newBinding, newAdapter)
        controller.detachView(oldRoot)
        assertSame(newBinding, controller.controllerBinding)
        assertSame(newAdapter, controller.adapter)
        controller.detachView(newRoot)
        assertNull(controller.controllerBinding)
        assertNull(controller.adapter)
    }
}
// KMK <--
