// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Search only on the server. A failed provider must not discard other providers' results. */
internal suspend fun searchServerSources(
    sources: List<ServerSource>,
    query: String,
    concurrency: Int = 5,
    timeoutMillis: Long = 30_000,
    fetch: suspend (ServerSource, String) -> SourceResults,
    emit: suspend (ServerSource, Result<SourceResults>) -> Unit,
) = coroutineScope {
    val permits = Semaphore(concurrency)
    sources.distinctBy { it.id }.forEach { source ->
        launch {
            permits.withPermit {
                val result = try {
                    val page = withTimeout(timeoutMillis) { fetch(source, query) }
                    if (page.mangas.any { it.sourceId != source.id } || (page.hasNextPage && page.mangas.isEmpty())) throw SuwayomiException(SuwayomiFailure.CONTRACT)
                    Result.success(page)
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    Result.failure(SuwayomiException(SuwayomiFailure.NETWORK))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Result.failure(e)
                }
                currentCoroutineContext().ensureActive()
                emit(source, result)
            }
        }
    }
}
// KMK <--
