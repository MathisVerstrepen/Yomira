// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiFederatedSearchTest {
    private fun source(id: Long) = ServerSource(id, "Source $id", "en", "/icon/$id", true)
    private fun manga(id: Int, source: Long) = ServerManga(id, source, "Result", user = ServerMangaUser(false))

    @Test
    fun `providers run with bounded concurrency and retain successful results when one fails`() = runBlocking {
        var active = 0
        var peak = 0
        val results = mutableMapOf<Long, Result<SourceResults>>()
        searchServerSources((1L..9L).map(::source), "query", concurrency = 3, fetch = { source, query ->
            assertEquals("query", query)
            active++
            peak = maxOf(peak, active)
            try {
                delay(10)
                if (source.id == 4L) throw SuwayomiException(SuwayomiFailure.NETWORK)
                SourceResults(listOf(manga(source.id.toInt(), source.id)), false)
            } finally {
                active--
            }
        }, emit = { source, result -> results[source.id] = result })
        assertEquals(3, peak)
        assertEquals(9, results.size)
        assertEquals(8, results.values.count { it.isSuccess })
        assertEquals(SuwayomiFailure.NETWORK, (results.getValue(4).exceptionOrNull() as SuwayomiException).failure)
    }

    @Test
    fun `cancelling an obsolete search stops requests and emits no stale results`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val emitted = mutableListOf<Long>()
        val job = launch {
            searchServerSources(listOf(source(1), source(2)), "old", concurrency = 1, fetch = { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }, emit = { source, _ -> emitted += source.id })
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `slow and invalid providers end independently without losing healthy results`() = runBlocking {
        val results = mutableMapOf<Long, Result<SourceResults>>()
        searchServerSources((1L..4L).map(::source), "query", timeoutMillis = 50, fetch = { source, _ ->
            when (source.id) {
                1L -> awaitCancellation()
                2L -> SourceResults(listOf(manga(2, 999)), false)
                3L -> SourceResults(emptyList(), true)
                else -> SourceResults(listOf(manga(4, 4)), false)
            }
        }, emit = { source, result -> results[source.id] = result })
        assertEquals(SuwayomiFailure.NETWORK, (results.getValue(1).exceptionOrNull() as SuwayomiException).failure)
        for (id in listOf(2L, 3L)) assertEquals(SuwayomiFailure.CONTRACT, (results.getValue(id).exceptionOrNull() as SuwayomiException).failure)
        assertTrue(results.getValue(4).isSuccess)
    }

    @Test
    fun `parent timeout remains cancellation rather than becoming provider failure`() = runBlocking {
        var emitted = false
        withTimeoutOrNull(20) {
            searchServerSources(listOf(source(1)), "query", timeoutMillis = 1000, fetch = { _, _ -> awaitCancellation() }, emit = { _, _ -> emitted = true })
        }
        assertTrue(!emitted)
    }
}
// KMK <--
