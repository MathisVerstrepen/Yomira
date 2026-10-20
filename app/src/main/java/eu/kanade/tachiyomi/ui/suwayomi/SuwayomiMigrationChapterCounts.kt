// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class MigrationCountKey(val id: Int, val sourceId: Long, val url: String)
internal fun ServerManga.migrationCountKey() = MigrationCountKey(id, sourceId, url)

internal data class MigrationChapterCount(
    val count: Int? = null,
    val loading: Boolean = false,
    val error: SuwayomiFailure? = null,
)

/** UI-scope bookkeeping, bounded IO, and a cache scoped to this migration and server connection. */
internal class MigrationChapterCounts(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    concurrency: Int = 3,
    private val timeoutMillis: Long = 30_000,
    private val fetch: suspend (ServerManga) -> Int,
) {
    private val permits = Semaphore(concurrency)
    private val jobs = mutableMapOf<MigrationCountKey, Job>()
    private var visible = emptyList<ServerManga>()
    private val mutableState = MutableStateFlow<Map<MigrationCountKey, MigrationChapterCount>>(emptyMap())
    val state = mutableState.asStateFlow()

    fun visible(mangas: List<ServerManga>) {
        visible = mangas.distinctBy { it.migrationCountKey() }
        val wanted = visible.map { it.migrationCountKey() }.toSet()
        jobs.keys.toList().filter { it !in wanted }.forEach(::cancel)
        visible.forEach { manga ->
            if (manga.chapters.totalCount > 0) {
                if (mutableState.value[manga.migrationCountKey()]?.count == null) record(manga, manga.chapters.totalCount)
            } else if (mutableState.value[manga.migrationCountKey()] == null) {
                start(manga)
            }
        }
    }

    fun retry(manga: ServerManga) {
        val key = manga.migrationCountKey()
        if (visible.none { it.migrationCountKey() == key } || mutableState.value[key]?.error == null) return
        mutableState.update { it - key }
        start(manga)
    }

    fun record(manga: ServerManga, count: Int) {
        require(count >= 0)
        val key = manga.migrationCountKey()
        cancel(key)
        mutableState.update { it + (key to MigrationChapterCount(count = count)) }
    }

    private fun cancel(key: MigrationCountKey) {
        jobs.remove(key)?.cancel()
        if (mutableState.value[key]?.loading == true) mutableState.update { it - key }
    }

    private fun start(manga: ServerManga) {
        val key = manga.migrationCountKey()
        mutableState.update { it + (key to MigrationChapterCount(loading = true)) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val count = permits.withPermit {
                    withContext(dispatcher) { withTimeout(timeoutMillis) { fetch(manga) } }
                }
                currentCoroutineContext().ensureActive()
                if (count < 0) throw SuwayomiException(SuwayomiFailure.CONTRACT)
                mutableState.update { it + (key to MigrationChapterCount(count = count)) }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it + (key to MigrationChapterCount(error = SuwayomiFailure.NETWORK)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it + (key to MigrationChapterCount(error = failure(e))) }
            } finally {
                // A cancelled request must not clear or overwrite a newer request for this title.
                if (jobs[key] === currentCoroutineContext()[Job]) {
                    jobs.remove(key)
                    if (mutableState.value[key]?.loading == true) mutableState.update { it - key }
                }
            }
        }
        jobs[key] = job
        job.start()
    }
}
// KMK <--
