// KMK -->
package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Server URLs only. Temporary image files are scoped to the connection and active chapter. */
internal class SuwayomiPageLoader(
    private val chapter: ReaderChapter,
    private val session: SuwayomiSession,
    private val onConflict: (String, Int) -> Unit,
    private val preferences: ReaderPreferences = Injekt.get(),
) : PageLoader() {
    override var isLocal = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(preferences.readerThreads().get().coerceIn(1, 8))
    private val locks = mutableMapOf<Int, Mutex>()
    private val directory = File(session.pageCache, "chapter-${chapter.chapter.id}")
    private var pages: List<ReaderPage> = emptyList()

    override suspend fun getPages(): List<ReaderPage> {
        val result = session.pages(SuwayomiMode.remoteId(chapter.chapter.id!!))
        result.syncConflict?.let { onConflict(it.deviceName, it.remotePage) }
        // Page fetching may pull newer resume state from a server-owned integration.
        chapter.chapter.last_page_read = result.chapter.user.lastPageRead
        chapter.chapter.read = result.chapter.user.isRead
        chapter.chapter.bookmark = result.chapter.user.isBookmarked
        pages = result.pages.mapIndexed { index, url -> ReaderPage(index, imageUrl = url) }
        if (preferences.aggressivePageLoading().get()) pages.forEach { page -> scope.launch { load(page) } }
        return pages
    }

    override suspend fun loadPage(page: ReaderPage) {
        load(page)
        pages.drop(page.index + 1).take(preferences.preloadSize().get().coerceIn(0, 20)).forEach { next -> scope.launch { load(next) } }
    }

    private suspend fun load(page: ReaderPage) {
        val lock = synchronized(locks) { locks.getOrPut(page.index) { Mutex() } }
        lock.withLock {
            if (isRecycled) return
            val file = File(directory, page.index.toString())
            if (file.isFile) {
                page.stream = file::inputStream
                page.status = Page.State.Ready
                return
            }
            val temporary = File(directory, "${page.index}.part")
            try {
                page.status = Page.State.DownloadImage
                directory.mkdirs()
                slots.withPermit { session.imageFile(page.imageUrl ?: throw SuwayomiException(SuwayomiFailure.CONTRACT), temporary) }
                if (isRecycled) return
                check(temporary.renameTo(file))
                page.stream = file::inputStream
                page.status = Page.State.Ready
            } catch (e: CancellationException) {
                page.status = Page.State.Queue
                throw e
            } catch (e: Exception) {
                page.status = Page.State.Error(e)
            } finally {
                temporary.delete()
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
        directory.deleteRecursively()
        pages.forEach { it.stream = null }
        pages = emptyList()
    }
}
// KMK <--
