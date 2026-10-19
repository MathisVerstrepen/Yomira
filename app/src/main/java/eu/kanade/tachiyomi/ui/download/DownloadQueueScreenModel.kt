package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    // KMK -->
    private val navigator: Navigator? = null,
    // KMK <--
) /* KMK --> */ : DownloadQueueController /* KMK <-- */ {

    private val _state = MutableStateFlow(emptyList<DownloadHeaderItem>())
    /* KMK --> */ override /* KMK <-- */ val state = _state.asStateFlow()

    // KMK -->
    override var controllerBinding: DownloadListBinding? = null
    // KMK <--

    /**
     * Adapter containing the active downloads.
     */
    /* KMK --> */ override /* KMK <-- */ var adapter: DownloadAdapter? = null

    /**
     * Map of jobs for active downloads.
     */
    private val progressJobs = mutableMapOf<Download, Job>()

    /* KMK --> */ override /* KMK <-- */ val listener = object : DownloadAdapter.DownloadItemListener {
        /**
         * Called when an item is released from a drag.
         *
         * @param position The position of the released item.
         */
        override fun onItemReleased(position: Int) {
            val adapter = adapter ?: return
            val downloads = adapter.headerItems.flatMap { header ->
                adapter.getSectionItems(header).map { item ->
                    (item as DownloadItem).download
                }
            }
            reorder(downloads)
        }

        /**
         * Called when the menu item of a download is pressed
         *
         * @param position The position of the item
         * @param menuItem The menu Item pressed
         */
        // KMK -->
        override fun onMenuItemClick(position: Int, menuItem: MenuItem, expectedChapterId: Long) {
            // KMK <--
            val item = adapter?.getItem(position) ?: return
            // KMK -->
            if (item is DownloadItem && item.download.chapter.id != expectedChapterId) return
            // KMK <--
            if (item is DownloadItem) {
                when (menuItem.itemId) {
                    R.id.move_to_top, R.id.move_to_bottom -> {
                        val headerItems = adapter?.headerItems ?: return
                        val newDownloads = /* KMK --> */ mutableListOf<eu.kanade.tachiyomi.data.download.model.DownloadDisplay>() /* KMK <-- */
                        headerItems.forEach { headerItem ->
                            headerItem as DownloadHeaderItem
                            if (headerItem == item.header) {
                                headerItem.removeSubItem(item)
                                if (menuItem.itemId == R.id.move_to_top) {
                                    headerItem.addSubItem(0, item)
                                } else {
                                    headerItem.addSubItem(item)
                                }
                            }
                            newDownloads.addAll(headerItem.subItems.map { it.download })
                        }
                        reorder(newDownloads)
                    }
                    R.id.move_to_top_series, R.id.move_to_bottom_series -> {
                        val (selectedSeries, otherSeries) = adapter?.currentItems
                            ?.filterIsInstance<DownloadItem>()
                            ?.map(DownloadItem::download)
                            ?.partition { item.download.manga.id == it.manga.id }
                            ?: Pair(emptyList(), emptyList())
                        if (menuItem.itemId == R.id.move_to_top_series) {
                            reorder(selectedSeries + otherSeries)
                        } else {
                            reorder(otherSeries + selectedSeries)
                        }
                    }
                    R.id.cancel_download -> {
                        cancel(listOf(item.download))
                    }
                    R.id.cancel_series -> {
                        val allDownloadsForSeries = adapter?.currentItems
                            ?.filterIsInstance<DownloadItem>()
                            ?.filter { item.download.manga.id == it.download.manga.id }
                            ?.map(DownloadItem::download)
                        if (!allDownloadsForSeries.isNullOrEmpty()) {
                            cancel(allDownloadsForSeries)
                        }
                    }
                    // KMK -->
                    R.id.show_manga -> {
                        val mangaId = item.download.manga.id
                        showManga(mangaId = mangaId)
                    }
                    // KMK <--
                }
            }
        }
    }

    init {
        screenModelScope.launch {
            downloadManager.queueState
                .map { downloads ->
                    downloads
                        .groupBy { it.source }
                        .map { entry ->
                            DownloadHeaderItem(entry.key.id, entry.key.name, entry.value.size).apply {
                                addSubItems(0, entry.value.map { DownloadItem(it, this) })
                            }
                        }
                }
                .collect { newList -> _state.update { newList } }
        }
    }

    override fun onDispose() {
        for (job in progressJobs.values) {
            job.cancel()
        }
        progressJobs.clear()
        adapter = null
        // KMK -->
        controllerBinding = null
        // KMK <--
    }

    /* KMK --> */ override /* KMK <-- */ val isDownloaderRunning = downloadManager.isDownloaderRunning
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), false)

    /* KMK --> */ override /* KMK <-- */ fun getDownloadStatusFlow() = downloadManager.statusFlow()
    /* KMK --> */ override /* KMK <-- */ fun getDownloadProgressFlow() = downloadManager.progressFlow()

    /* KMK --> */ override /* KMK <-- */ fun startDownloads() {
        downloadManager.startDownloads()
    }

    /* KMK --> */ override /* KMK <-- */ fun pauseDownloads() {
        downloadManager.pauseDownloads()
    }

    /* KMK --> */ override /* KMK <-- */ fun clearQueue() {
        downloadManager.clearQueue()
    }

    // KMK -->
    fun reorder(downloads: List<eu.kanade.tachiyomi.data.download.model.DownloadDisplay>) {
        require(downloads.all { it is Download })
        downloadManager.reorderQueue(downloads.map { it as Download })
    }
    // KMK <--

    // KMK -->
    fun cancel(downloads: List<eu.kanade.tachiyomi.data.download.model.DownloadDisplay>) {
        require(downloads.all { it is Download })
        downloadManager.cancelQueuedDownloads(downloads.map { it as Download })
    }
    // KMK <--

    // KMK -->
    fun showManga(mangaId: Long) {
        navigator?.push(MangaScreen(mangaId))
    }
    // KMK <--

    // KMK -->
    override fun <R : Comparable<R>> reorderQueue(selector: (DownloadItem) -> R, reverse: Boolean) {
        // KMK <--
        val adapter = adapter ?: return
        val newDownloads = /* KMK --> */ mutableListOf<eu.kanade.tachiyomi.data.download.model.DownloadDisplay>() /* KMK <-- */
        adapter.headerItems.forEach { headerItem ->
            headerItem as DownloadHeaderItem
            headerItem.subItems = headerItem.subItems.sortedBy(selector).toMutableList().apply {
                if (reverse) {
                    reverse()
                }
            }
            newDownloads.addAll(headerItem.subItems.map { it.download })
        }
        reorder(newDownloads)
    }

    /**
     * Called when the status of a download changes.
     *
     * @param download the download whose status has changed.
     */
    // KMK -->
    override fun onStatusChange(download: eu.kanade.tachiyomi.data.download.model.DownloadDisplay) {
        // KMK <--
        // KMK -->
        require(download is Download)
        // KMK <--
        when (download.status) {
            Download.State.DOWNLOADING -> {
                launchProgressJob(download)
                // Initial update of the downloaded pages
                onUpdateDownloadedPages(download)
            }
            Download.State.DOWNLOADED -> {
                cancelProgressJob(download)
                onUpdateProgress(download)
                onUpdateDownloadedPages(download)
            }
            Download.State.ERROR -> cancelProgressJob(download)
            else -> {
                /* unused */
            }
        }
    }

    /**
     * Observe the progress of a download and notify the view.
     *
     * @param download the download to observe its progress.
     */
    private fun launchProgressJob(download: Download) {
        val job = screenModelScope.launch {
            while (download.pages == null) {
                delay(50)
            }

            val progressFlows = download.pages!!.map(Page::progressFlow)
            combine(progressFlows, Array<Int>::sum)
                .distinctUntilChanged()
                .debounce(50)
                .collectLatest {
                    onUpdateProgress(download)
                }
        }

        // Avoid leaking jobs
        progressJobs.remove(download)?.cancel()

        progressJobs[download] = job
    }

    /**
     * Unsubscribes the given download from the progress subscriptions.
     *
     * @param download the download to unsubscribe.
     */
    private fun cancelProgressJob(download: Download) {
        progressJobs.remove(download)?.cancel()
    }

    /**
     * Called when the progress of a download changes.
     *
     * @param download the download whose progress has changed.
     */
    private fun onUpdateProgress(download: Download) {
        getHolder(download)?.notifyProgress()
    }

    /**
     * Called when a page of a download is downloaded.
     *
     * @param download the download whose page has been downloaded.
     */
    // KMK -->
    override fun onUpdateDownloadedPages(download: eu.kanade.tachiyomi.data.download.model.DownloadDisplay) {
        // KMK <--
        getHolder(download)?.notifyDownloadedPages()
    }

    /**
     * Returns the holder for the given download.
     *
     * @param download the download to find.
     * @return the holder of the download or null if it's not bound.
     */
    // KMK -->
    private fun getHolder(download: eu.kanade.tachiyomi.data.download.model.DownloadDisplay): DownloadHolder? {
        // KMK <--
        // KMK -->
        return controllerBinding?.root?.findViewHolderForItemId(download.chapter.id) as? DownloadHolder
        // KMK <--
    }
}
