// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.content.Context
import android.view.MenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.DownloadDisplay
import eu.kanade.tachiyomi.data.suwayomi.ServerDownload
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.downloadAction
import eu.kanade.tachiyomi.data.suwayomi.identity
import eu.kanade.tachiyomi.data.suwayomi.serverDownloads
import eu.kanade.tachiyomi.data.suwayomi.toKomikkuManga
import eu.kanade.tachiyomi.data.suwayomi.toReaderChapter
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import eu.kanade.tachiyomi.ui.download.DownloadAdapter
import eu.kanade.tachiyomi.ui.download.DownloadHeaderItem
import eu.kanade.tachiyomi.ui.download.DownloadHolder
import eu.kanade.tachiyomi.ui.download.DownloadItem
import eu.kanade.tachiyomi.ui.download.DownloadQueueContent
import eu.kanade.tachiyomi.ui.download.DownloadQueueController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.floor
import kotlin.math.roundToInt

/** Only presentation data reaches the Android queue adapter; server files stay server-owned. */
internal class ServerDownloadDisplay(private val sessionId: String, @Volatile var snapshot: ServerDownload) : DownloadDisplay {
    override val manga: Manga get() = snapshot.manga.toKomikkuManga(sessionId)
    override val chapter: Chapter get() = snapshot.chapter.toReaderChapter()
    override val totalPages: Int? get() = snapshot.chapter.pageCount.takeIf { it > 0 }
    override val totalProgress: Int get() = ((totalPages ?: 0) * 100 * snapshot.progress.coerceIn(0f, 1f)).roundToInt()
    override val downloadedImages: Int get() = floor((totalPages ?: 0) * snapshot.progress.coerceIn(0f, 1f).toDouble() + 0.0001).toInt()
    override val serverError: Boolean get() = snapshot.state.equals("ERROR", true)
}

private class SuwayomiDownloadQueueController(
    private val sessionId: String,
    private val service: SuwayomiDownloadsModel,
    private val context: Context,
    private val navigator: Navigator,
) : DownloadQueueController {
    private val mutableState = MutableStateFlow(emptyList<DownloadHeaderItem>())
    override val state = mutableState.asStateFlow()
    private val queueError = MutableStateFlow<String?>(null)
    override val error = combine(service.state, queueError) { status, local -> status.error?.let { context.stringResource(failureResource(it)) } ?: local }
        .stateIn(screenModelScope, SharingStarted.Eagerly, null)
    override val isLoading = service.state.map { it.loading }.stateIn(screenModelScope, SharingStarted.Eagerly, true)
    override val canControlQueue = service.state.map { it.canDownload }.stateIn(screenModelScope, SharingStarted.Eagerly, false)
    override val isDownloaderRunning = service.state.map { it.downloads?.state.equals("STARTED", true) }.stateIn(screenModelScope, SharingStarted.Eagerly, false)
    override val notice = KMR.strings.suwayomi_shared_queue_notice
    override lateinit var controllerBinding: DownloadListBinding
    override var adapter: DownloadAdapter? = null
    val confirmClear = MutableStateFlow(false)
    private val events = MutableSharedFlow<DownloadDisplay>(extraBufferCapacity = 128)
    private val items = mutableMapOf<Int, ServerDownloadDisplay>()

    init {
        screenModelScope.launch {
            service.state.collect { status ->
                val queue = status.downloads?.queue ?: return@collect
                if (status.loading || status.error != null) return@collect
                val displays = queue.map { remote -> items.getOrPut(remote.chapter.id) { ServerDownloadDisplay(sessionId, remote) }.also { it.snapshot = remote } }
                val previous = state.value.flatMap { it.subItems }.map { it.download.chapter.id }
                if (previous != displays.map { it.chapter.id }) {
                    mutableState.value = if (displays.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            DownloadHeaderItem(SuwayomiMode.SOURCE_ID, context.stringResource(KMR.strings.suwayomi_server_downloads), displays.size).apply {
                                addSubItems(0, displays.map { DownloadItem(it, this) })
                            },
                        )
                    }
                }
                items.keys.retainAll(queue.map { it.chapter.id }.toSet())
                displays.forEach { events.tryEmit(it) }
            }
        }
    }

    override val listener = object : DownloadAdapter.DownloadItemListener {
        override fun onItemReleased(position: Int) {
            reorder(adapterOrder())
        }
        override fun onMenuItemClick(position: Int, menuItem: MenuItem, expectedChapterId: Long) {
            val item = adapter?.getItem(position) as? DownloadItem ?: return
            if (item.download.chapter.id != expectedChapterId) {
                stale()
                return
            }
            if (menuItem.itemId == R.id.show_manga) {
                navigator.push(serverMangaScreen(sessionId, SuwayomiMode.remoteId(item.download.manga.id)))
                return
            }
            if (!canControlQueue.value) return
            val current = adapterOrder()
            val series = current.filter { it.manga.id == item.download.manga.id }
            when (menuItem.itemId) {
                R.id.move_to_top -> reorder(listOf(item.download) + current.filterNot { it.chapter.id == expectedChapterId })
                R.id.move_to_bottom -> reorder(current.filterNot { it.chapter.id == expectedChapterId } + item.download)
                R.id.move_to_top_series -> reorder(series + current.filterNot { it.manga.id == item.download.manga.id })
                R.id.move_to_bottom_series -> reorder(current.filterNot { it.manga.id == item.download.manga.id } + series)
                R.id.cancel_download -> cancel(listOf(item.download))
                R.id.cancel_series -> cancel(series)
            }
        }
    }

    private fun adapterOrder(): List<DownloadDisplay> = adapter?.headerItems.orEmpty().flatMap { header -> adapter?.getSectionItems(header).orEmpty().filterIsInstance<DownloadItem>().map { it.download } }
    private fun stale() {
        queueError.value = context.stringResource(KMR.strings.suwayomi_queue_changed)
        service.load()
    }
    private fun reorder(displays: List<DownloadDisplay>) {
        if (!canControlQueue.value) return
        queueError.value = null
        val desired = displays.filterIsInstance<ServerDownloadDisplay>().map { it.snapshot }
        service.batch {
            val current = serverDownloads().queue
            if (current.map { it.chapter.id }.toSet() != desired.map { it.chapter.id }.toSet()) {
                stale()
                return@batch
            }
            desired.forEach { expected ->
                val actual = current.first { it.chapter.id == expected.chapter.id }
                if (actual.chapter.identity() != expected.chapter.identity()) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
            }
            desired.forEachIndexed { index, item -> downloadAction("reorder", item.chapter.id, index) }
        }
    }
    private fun cancel(displays: List<DownloadDisplay>) {
        queueError.value = null
        val expected = displays.filterIsInstance<ServerDownloadDisplay>().map { it.snapshot.chapter }
        service.batch {
            expected.forEach { previous ->
                if (chapter(previous.id).identity() != previous.identity()) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                downloadAction("cancel", previous.id)
            }
        }
    }
    override fun <R : Comparable<R>> reorderQueue(selector: (DownloadItem) -> R, reverse: Boolean) {
        val list = state.value.flatMap { it.subItems }.sortedBy(selector).let { if (reverse) it.reversed() else it }
        reorder(list.map { it.download })
    }
    override fun getDownloadStatusFlow() = emptyFlow<DownloadDisplay>()
    override fun getDownloadProgressFlow() = events
    override fun onStatusChange(download: DownloadDisplay) {
        onUpdateDownloadedPages(download)
    }
    override fun onUpdateDownloadedPages(download: DownloadDisplay) {
        if (::controllerBinding.isInitialized) (controllerBinding.root.findViewHolderForItemId(download.chapter.id) as? DownloadHolder)?.bind(download)
    }
    override fun startDownloads() {
        queueError.value = null
        service.action("start")
    }
    override fun pauseDownloads() {
        queueError.value = null
        service.action("stop")
    }
    override fun clearQueue() {
        confirmClear.value = true
    }
    override fun retry() {
        queueError.value = null
        service.load()
    }
    fun clearConfirmed() {
        confirmClear.value = false
        queueError.value = null
        service.action("clear")
    }
    override fun onDispose() {
        adapter = null
        items.clear()
    }
}

@Composable
internal fun SuwayomiDownloadQueuePresenter(expectedSession: String? = null) {
    val navigator = LocalNavigator.currentOrThrow
    val owner = navigator.lastItem
    val backend = remember { Injekt.get<SuwayomiBackend>() }
    val current by backend.session.collectAsState()
    val expected by rememberSaveable { mutableStateOf(expectedSession ?: current?.id) }
    val session = current?.takeIf { it.id == expected }
    if (session == null) {
        ConnectionError(SuwayomiFailure.SESSION_CHANGED, { navigator.pop() })
        return
    }
    val context = LocalContext.current.applicationContext
    val service = owner.rememberScreenModel(tag = "server-queue-${session.id}") { SuwayomiDownloadsModel(session.id) }
    val controller = owner.rememberScreenModel(tag = "server-queue-ui-${session.id}") { SuwayomiDownloadQueueController(session.id, service, context, navigator) }
    val clear by controller.confirmClear.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { service.load() }
    LaunchedEffect(service) {
        try {
            service.observe()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            service.observationFailed(e)
        }
    }
    DownloadQueueContent(controller)
    if (clear) {
        AlertDialog(
            onDismissRequest = { controller.confirmClear.value = false },
            title = { Text(stringResource(KMR.strings.suwayomi_clear_queue)) },
            text = { Text(stringResource(KMR.strings.suwayomi_clear_queue_notice)) },
            confirmButton = { TextButton(onClick = controller::clearConfirmed) { Text(stringResource(KMR.strings.suwayomi_clear_queue)) } },
            dismissButton = { TextButton(onClick = { controller.confirmClear.value = false }) { Text(stringResource(MR.strings.action_cancel)) } },
        )
    }
}
// KMK <--
