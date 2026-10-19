// KMK -->
package eu.kanade.tachiyomi.ui.download

import androidx.recyclerview.widget.RecyclerView
import cafe.adriel.voyager.core.model.ScreenModel
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.download.model.DownloadDisplay
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The queue screen owns its existing layout; controllers own queue data and operations. */
interface DownloadQueueController : ScreenModel {
    val state: StateFlow<List<DownloadHeaderItem>>
    val isDownloaderRunning: StateFlow<Boolean>
    val hasLoaded: StateFlow<Boolean> get() = defaultsAllowed
    val isLoading: StateFlow<Boolean> get() = defaultsNotLoading
    val canControlQueue: StateFlow<Boolean> get() = defaultsAllowed
    val error: StateFlow<String?> get() = defaultsNoError
    val notice: StringResource? get() = null
    var controllerBinding: DownloadListBinding?
    var adapter: DownloadAdapter?
    /** A controller may outlive its Android view during navigation and empty-queue transitions. */
    fun attachView(binding: DownloadListBinding, adapter: DownloadAdapter) {
        controllerBinding = binding
        this.adapter = adapter
    }
    fun detachView(root: RecyclerView) {
        // Release of an older view must not detach a newly created view for this controller.
        if (controllerBinding?.root === root) {
            controllerBinding = null
            adapter = null
        }
    }
    val listener: DownloadAdapter.DownloadItemListener
    fun getDownloadStatusFlow(): Flow<DownloadDisplay>
    fun getDownloadProgressFlow(): Flow<DownloadDisplay>
    fun onStatusChange(download: DownloadDisplay)
    fun onUpdateDownloadedPages(download: DownloadDisplay)
    fun startDownloads()
    fun pauseDownloads()
    fun clearQueue()
    fun retry() {}
    fun <R : Comparable<R>> reorderQueue(selector: (DownloadItem) -> R, reverse: Boolean = false)
}

private val defaultsNotLoading = MutableStateFlow(false)
private val defaultsAllowed = MutableStateFlow(true)
private val defaultsNoError = MutableStateFlow<String?>(null)
// KMK <--
