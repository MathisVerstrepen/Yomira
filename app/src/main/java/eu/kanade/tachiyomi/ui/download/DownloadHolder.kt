package eu.kanade.tachiyomi.ui.download

// KMK -->
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme.Companion.setColors
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadItemBinding
import eu.kanade.tachiyomi.util.view.popupMenu
import tachiyomi.core.common.i18n.stringResource
// KMK <--

/**
 * Class used to hold the data of a download.
 * All the elements from the layout file "download_item" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @constructor creates a new download holder.
 */
class DownloadHolder(private val view: View, val adapter: DownloadAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = DownloadItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.menu.setOnClickListener { it.post { showPopupMenu(it) } }
    }

    // KMK -->
    private lateinit var download: eu.kanade.tachiyomi.data.download.model.DownloadDisplay
    // KMK <--

    /**
     * Binds this holder with the given download.
     *
     * @param download The download to bind.
     */
    // KMK -->
    fun bind(download: eu.kanade.tachiyomi.data.download.model.DownloadDisplay) {
        // KMK <--
        this.download = download
        // Update the chapter name.
        binding.chapterTitle.text = download.chapter.name

        // Update the manga title
        binding.mangaFullTitle.text = download.manga.title

        // Update the progress bar and the number of downloaded pages
        // KMK -->
        val pages = download.totalPages
        // KMK <--
        if (pages == null) {
            binding.downloadProgress.progress = 0
            binding.downloadProgress.max = 1
            // KMK -->
            binding.downloadProgressText.text = if (download.serverError) view.context.stringResource(tachiyomi.i18n.kmk.KMR.strings.suwayomi_download_failed) else ""
            // KMK <--
        } else {
            binding.downloadProgress.max = /* KMK --> */ pages /* KMK <-- */ * 100
            notifyProgress()
            notifyDownloadedPages()
        }
        // KMK -->
        binding.downloadProgress.setColors(adapter.colorScheme)
        binding.downloadProgress.visibility = if (download.serverError) View.INVISIBLE else View.VISIBLE
        // KMK <--
    }

    /**
     * Updates the progress bar of the download.
     */
    fun notifyProgress() {
        // KMK -->
        val pages = download.totalPages ?: return
        // KMK <--
        if (binding.downloadProgress.max == 1) {
            binding.downloadProgress.max = /* KMK --> */ pages /* KMK <-- */ * 100
        }
        binding.downloadProgress.setProgressCompat(download.totalProgress, true)
    }

    /**
     * Updates the text field of the number of downloaded pages.
     */
    fun notifyDownloadedPages() {
        // KMK -->
        val pages = download.totalPages ?: return
        // KMK <--
        // KMK -->
        binding.downloadProgressText.text = if (download.serverError) {
            view.context.stringResource(tachiyomi.i18n.kmk.KMR.strings.suwayomi_download_failed)
        } else {
            "${download.downloadedImages}/$pages"
        }
        // KMK <--
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.downloadItemListener.onItemReleased(position)
        binding.container.isDragged = false
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
        }
    }

    private fun showPopupMenu(view: View) {
        // KMK -->
        val expectedChapterId = download.chapter.id
        // KMK <--
        view.popupMenu(
            menuRes = R.menu.download_single,
            initMenu = {
                findItem(R.id.move_to_top).isVisible = bindingAdapterPosition > 1
                findItem(R.id.move_to_bottom).isVisible =
                    bindingAdapterPosition != adapter.itemCount - 1
                // KMK -->
                if (!adapter.canControlQueue) {
                    for (index in 0 until size()) {
                        val item = getItem(index)
                        if (item.itemId != R.id.show_manga) item.isVisible = false
                    }
                }
                // KMK <--
            },
            onMenuItemClick = {
                // KMK -->
                adapter.downloadItemListener.onMenuItemClick(bindingAdapterPosition, this, expectedChapterId)
                // KMK <--
            },
        )
    }
}
