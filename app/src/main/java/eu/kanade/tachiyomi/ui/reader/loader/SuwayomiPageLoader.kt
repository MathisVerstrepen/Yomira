// KMK -->
package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream

/** Server-returned URLs only. Pages stay in a bounded chapter cache until this loader is recycled. */
internal class SuwayomiPageLoader(
    private val chapter: ReaderChapter,
    private val session: SuwayomiSession,
    private val onConflict: (String, Int) -> Unit,
) : PageLoader() {
    override var isLocal = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(3)
    private val locks = mutableMapOf<Int, Mutex>()
    private val images = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > 8
    }
    private var pages: List<ReaderPage> = emptyList()

    override suspend fun getPages(): List<ReaderPage> {
        val result = session.pages(SuwayomiMode.remoteId(chapter.chapter.id!!))
        result.syncConflict?.let { onConflict(it.deviceName, it.remotePage) }
        // fetchChapterPages can pull newer resume state from a server integration.
        chapter.chapter.last_page_read = result.chapter.user.lastPageRead
        chapter.chapter.read = result.chapter.user.isRead
        chapter.chapter.bookmark = result.chapter.user.isBookmarked
        pages = result.pages.mapIndexed { index, url -> ReaderPage(index, imageUrl = url) }
        return pages
    }

    override suspend fun loadPage(page: ReaderPage) {
        load(page)
        pages.drop(page.index + 1).take(3).forEach { next -> scope.launch { load(next) } }
    }

    private suspend fun load(page: ReaderPage) {
        val lock = synchronized(locks) { locks.getOrPut(page.index) { Mutex() } }
        lock.withLock {
            if (isRecycled) return
            val cached = synchronized(images) { images[page.index] }
            if (cached != null) {
                page.stream = { ByteArrayInputStream(synchronized(images) { images[page.index] } ?: throw SuwayomiException(SuwayomiFailure.NETWORK)) }
                page.status = Page.State.Ready
                return
            }
            try {
                page.status = Page.State.DownloadImage
                val bytes = slots.withPermit { session.image(page.imageUrl ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)) }
                if (isRecycled) return
                synchronized(images) { images[page.index] = bytes }
                // Resolve the bytes at read time, so evicted page objects do not retain whole chapters.
                page.stream = { ByteArrayInputStream(synchronized(images) { images[page.index] } ?: throw SuwayomiException(SuwayomiFailure.NETWORK)) }
                page.status = Page.State.Ready
            } catch (e: CancellationException) {
                page.status = Page.State.Queue
                throw e
            } catch (e: Exception) {
                page.status = Page.State.Error(e)
            }
        }
    }

    override fun retryPage(page: ReaderPage) {
        page.status = Page.State.Queue
        scope.launch { load(page) }
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        synchronized(images) { images.clear() }
        pages.forEach { it.stream = null }
        pages = emptyList()
    }
}
// KMK <--
