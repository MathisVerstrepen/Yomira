// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Transfer policy separated from Android scheduling so interruption and quota behavior are testable. */
internal class SuwayomiOfflineDownloader(
    private val store: SuwayomiOfflineStore,
    private val chapter: suspend (Int) -> ServerChapter,
    private val pages: suspend (Int) -> ServerPages,
    private val image: suspend (String, File, (Int) -> Unit) -> Unit,
    private val checkNetwork: () -> Unit,
    private val progress: suspend (OfflineDownload) -> Unit = {},
) {
    suspend fun download(item: OfflineDownload) {
        fun checkCurrent() {
            if (store.state.value.downloads.none { it.chapterId == item.chapterId && it.generation == item.generation }) throw CancellationException("Download removed or replaced")
        }
        checkCurrent()
        checkNetwork()
        val title = store.state.value.titles.getValue(item.mangaId)
        val expected = title.chapters.first { it.id == item.chapterId }
        if (chapter(item.chapterId).identity() != expected.identity()) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
        val result = pages(item.chapterId)
        if (result.chapter.identity() != expected.identity() || result.pages.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        val (staging, previous) = store.begin(item.chapterId, expected.identity(), result.pages)
        val sizes = previous.toMutableList()
        suspend fun fetch(path: String, destination: File) {
            checkNetwork()
            val used = store.usedBytes()
            val free = destination.parentFile!!.usableSpace
            var received = 0L
            val coroutine = currentCoroutineContext()
            try {
                image(path, destination) { count ->
                    coroutine.ensureActive()
                    checkCurrent()
                    checkNetwork()
                    received += count
                    if (used + received + 64 * 1024 > store.state.value.limitMiB * 1024L * 1024 || received + 16L * 1024 * 1024 > free) throw OfflineStorageFull()
                }
                check(destination.length() > 0)
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        }
        if (store.cover(item.mangaId) == null) {
            title.manga.coverUrl()?.let { path ->
                val cover = store.coverDestination(item.mangaId)
                try {
                    fetch(path, cover)
                    store.commitCover(item.mangaId, cover)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A missing thumbnail must not prevent downloading readable chapters.
                } finally {
                    cover.delete()
                }
            }
        }
        for (index in previous.size until result.pages.size) {
            currentCoroutineContext().ensureActive()
            if (store.state.value.downloads.none { it.chapterId == item.chapterId }) return
            store.update(item.chapterId, OfflineState.DOWNLOADING, index, result.pages.size)
            progress(item.copy(state = OfflineState.DOWNLOADING, pages = index, total = result.pages.size))
            val file = File(staging, index.toString())
            fetch(result.pages[index], file)
            sizes += file.length()
            store.checkpoint(staging, expected.identity(), result.pages, sizes)
        }
        currentCoroutineContext().ensureActive()
        checkCurrent()
        if (store.state.value.downloads.any { it.chapterId == item.chapterId }) store.commit(item.chapterId, staging, sizes)
    }
}
// KMK <--
