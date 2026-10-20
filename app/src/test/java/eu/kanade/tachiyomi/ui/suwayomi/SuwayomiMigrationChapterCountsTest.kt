// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import eu.kanade.tachiyomi.data.suwayomi.ServerChapterCount
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerMangaUser
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SuwayomiMigrationChapterCountsTest {
    private fun manga(id: Int, count: Int = 0) = ServerManga(id, 200, "Story $id", user = ServerMangaUser(false), url = "/$id", chapters = ServerChapterCount(count))

    @Test
    fun `only submitted visible titles run with at most three requests and queued work is cancelled`() = runTest {
        val requests = mutableListOf<Int>()
        var active = 0
        var peak = 0
        val release = CompletableDeferred<Unit>()
        val counts = MigrationChapterCounts(backgroundScope, StandardTestDispatcher(testScheduler)) {
            requests += it.id
            active++
            peak = maxOf(peak, active)
            try {
                release.await()
                it.id
            } finally {
                active--
            }
        }
        counts.visible((1..6).map { manga(it) })
        runCurrent()
        assertEquals(listOf(1, 2, 3), requests)
        assertEquals(3, peak)
        counts.visible(listOf(manga(1), manga(7)))
        runCurrent()
        assertEquals(listOf(1, 2, 3, 7), requests)
        assertEquals(2, active)
        for (id in 2..6) assertNull(counts.state.value[manga(id).migrationCountKey()])
        release.complete(Unit)
        runCurrent()
        assertEquals(1, counts.state.value.getValue(manga(1).migrationCountKey()).count)
        assertEquals(7, counts.state.value.getValue(manga(7).migrationCountKey()).count)
        assertEquals(3, peak)
    }

    @Test
    fun `known counts and fetched zero are cached across scrolling and fresh preview wins over old metadata`() = runTest {
        val requests = mutableListOf<Int>()
        val counts = MigrationChapterCounts(backgroundScope, StandardTestDispatcher(testScheduler)) {
            requests += it.id
            0
        }
        counts.visible(listOf(manga(1), manga(2, 40)))
        runCurrent()
        assertEquals(listOf(1), requests)
        assertEquals(0, counts.state.value.getValue(manga(1).migrationCountKey()).count)
        counts.visible(emptyList())
        counts.visible(listOf(manga(1), manga(2, 40)))
        runCurrent()
        assertEquals(listOf(1), requests)
        counts.record(manga(2), 45)
        counts.visible(listOf(manga(2, 40)))
        assertEquals(45, counts.state.value.getValue(manga(2).migrationCountKey()).count)
        // A reused ID with different source identity must not reuse a count.
        counts.visible(listOf(manga(1).copy(url = "/different")))
        runCurrent()
        assertEquals(listOf(1, 1), requests)
    }

    @Test
    fun `failures are isolated and scrolling does not trigger automatic retry loops`() = runTest {
        var failed = true
        val requests = mutableListOf<Int>()
        val counts = MigrationChapterCounts(backgroundScope, StandardTestDispatcher(testScheduler)) {
            requests += it.id
            if (it.id == 1 && failed) throw SuwayomiException(SuwayomiFailure.NETWORK)
            40
        }
        counts.visible(listOf(manga(1), manga(2)))
        runCurrent()
        assertEquals(SuwayomiFailure.NETWORK, counts.state.value.getValue(manga(1).migrationCountKey()).error)
        assertEquals(40, counts.state.value.getValue(manga(2).migrationCountKey()).count)
        counts.visible(emptyList())
        counts.retry(manga(1))
        counts.visible(listOf(manga(1), manga(2)))
        runCurrent()
        assertEquals(listOf(1, 2), requests)
        failed = false
        counts.retry(manga(1))
        runCurrent()
        assertEquals(listOf(1, 2, 1), requests)
        assertEquals(40, counts.state.value.getValue(manga(1).migrationCountKey()).count)
    }

    @Test
    fun `timeout releases a slot without changing healthy results or recording cancellations as errors`() = runTest {
        val counts = MigrationChapterCounts(backgroundScope, StandardTestDispatcher(testScheduler), concurrency = 1, timeoutMillis = 50) {
            if (it.id == 1) awaitCancellation() else 40
        }
        counts.visible(listOf(manga(1), manga(2)))
        runCurrent()
        advanceTimeBy(51)
        runCurrent()
        assertEquals(SuwayomiFailure.NETWORK, counts.state.value.getValue(manga(1).migrationCountKey()).error)
        assertEquals(40, counts.state.value.getValue(manga(2).migrationCountKey()).count)
        counts.retry(manga(1))
        runCurrent()
        counts.visible(emptyList())
        runCurrent()
        assertFalse(counts.state.value.values.any { it.loading })
        assertNull(counts.state.value[manga(1).migrationCountKey()])
    }

    @Test
    fun `late cancelled response cannot overwrite the replacement request or a recorded preview`() = runTest {
        var calls = 0
        val releaseOld = CompletableDeferred<Unit>()
        val counts = MigrationChapterCounts(backgroundScope, StandardTestDispatcher(testScheduler), concurrency = 1) {
            calls++
            if (calls == 1) {
                withContext(NonCancellable) {
                    releaseOld.await()
                    999
                }
            } else {
                40
            }
        }
        counts.visible(listOf(manga(1)))
        runCurrent()
        counts.visible(emptyList())
        counts.visible(listOf(manga(1)))
        runCurrent()
        assertEquals(1, calls)
        releaseOld.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(40, counts.state.value.getValue(manga(1).migrationCountKey()).count)
        assertTrue(counts.state.value.values.none { it.error != null || it.loading })
    }
}
// KMK <--
