// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import eu.kanade.tachiyomi.ui.reader.loader.SuwayomiPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

class SuwayomiOfflineTest {
    @TempDir lateinit var directory: File
    private val manga = ServerManga(17, 8, "Offline title", user = ServerMangaUser(true), url = "manga/17")
    private val chapter = ServerChapter(44, 17, "Chapter 44", 44.0, 44, pageCount = 3, user = ServerChapterUser(false, false, 0, 0, false), url = "chapter/44", manga = ServerChapterMangaIdentity(8, "manga/17"))
    private val urls = listOf("/page/0", "/page/1", "/page/2")

    private fun store() = SuwayomiOfflineStore(directory).also { it.enqueue(manga, listOf(chapter), listOf(44)) }
    private fun downloader(
        store: SuwayomiOfflineStore,
        checkNetwork: () -> Unit = {},
        fetch: suspend (String, File, (Int) -> Unit) -> Unit = { path, destination, guard ->
            val bytes = path.encodeToByteArray()
            guard(bytes.size)
            destination.writeBytes(bytes)
        },
    ) = SuwayomiOfflineDownloader(store, { chapter }, { ServerPages(urls, chapter) }, fetch, checkNetwork)

    @Test
    fun `completed files and metadata survive restart and are readable without any server request`() = runBlocking {
        val store = store()
        downloader(store).download(store.next()!!)
        val restarted = SuwayomiOfflineStore(directory)
        assertEquals(OfflineState.COMPLETE, restarted.state.value.downloads.single().state)
        assertEquals(manga, restarted.state.value.titles.getValue(17).manga)
        val session = mockk<SuwayomiSession>()
        every { session.offline } returns restarted
        every { session.pageCache } returns File(directory, "temporary-reader-cache")
        coEvery { session.pages(any()) } throws AssertionError("Offline reading must not contact the server")
        val databaseChapter = mockk<eu.kanade.tachiyomi.data.database.models.Chapter>()
        every { databaseChapter.id } returns SuwayomiMode.readerId(44)
        val preferences = mockk<ReaderPreferences>()
        every { preferences.readerThreads() } returns mockk { every { get() } returns 1 }
        val loader = SuwayomiPageLoader(ReaderChapter(databaseChapter), session, { _, _ -> }, preferences)
        val pages = loader.getPages()
        assertTrue(loader.isLocal)
        assertEquals(urls, pages.map { it.stream!!.invoke().use { stream -> stream.readBytes().decodeToString() } })
        loader.loadPage(pages[1])
        loader.recycle()
        assertNotNull(restarted.files(44))
        coVerify(exactly = 0) { session.pages(any()) }
    }

    @Test
    fun `interrupted transfer is unavailable offline and resumes only checkpointed pages after restart`() = runBlocking {
        val store = store()
        val failure = runCatching {
            downloader(store, fetch = { path, file, guard ->
                if (path == urls[1]) {
                    file.writeText("partial")
                    throw SuwayomiException(SuwayomiFailure.NETWORK)
                }
                guard(1)
                file.writeText("x")
            }).download(store.next()!!)
        }.exceptionOrNull()
        assertTrue(failure is SuwayomiException)
        assertNull(store.files(44))
        val restarted = SuwayomiOfflineStore(directory)
        val fetched = mutableListOf<String>()
        downloader(restarted, fetch = { path, file, guard ->
            fetched += path
            guard(1)
            file.writeText("x")
        }).download(restarted.next()!!)
        assertEquals(urls.drop(1), fetched)
        assertEquals(3, restarted.files(44)!!.size)
    }

    @Test
    fun `Wi-Fi loss blocks writes during a transfer and no partial chapter becomes readable`() = runBlocking {
        val store = store()
        var wifi = true
        var writes = 0
        val error = runCatching {
            downloader(store, checkNetwork = { if (store.state.value.wifiOnly && !wifi) throw OfflineWifiRequired() }, fetch = { _, file, guard ->
                file.outputStream().use { output ->
                    guard(1)
                    output.write(1)
                    writes++
                    wifi = false
                    guard(1)
                    output.write(2)
                    writes++
                }
            }).download(store.next()!!)
        }.exceptionOrNull()
        assertTrue(error is OfflineWifiRequired)
        assertEquals(1, writes)
        assertNull(store.files(44))
        assertFalse(File(directory, "44.part/0").exists())
    }

    @Test
    fun `Wi-Fi restriction can be disabled and settings persist independently of downloads`() = runBlocking {
        val store = store()
        store.configure(false, 512)
        downloader(store, checkNetwork = { if (store.state.value.wifiOnly) throw OfflineWifiRequired() }).download(store.next()!!)
        val restarted = SuwayomiOfflineStore(directory)
        assertFalse(restarted.state.value.wifiOnly)
        assertEquals(512, restarted.state.value.limitMiB)
        assertNotNull(restarted.files(44))
    }

    @Test
    fun `storage quota rejects a response before its next chunk is written`() = runBlocking {
        val store = store()
        store.configure(true, 128)
        RandomAccessFile(File(directory, "existing-pages"), "rw").use { it.setLength(128L * 1024 * 1024 - 65536) }
        var writes = 0
        val error = runCatching {
            downloader(store, fetch = { _, file, guard ->
                guard(8192)
                file.writeBytes(ByteArray(8192))
                writes++
            }).download(store.next()!!)
        }.exceptionOrNull()
        assertTrue(error is OfflineStorageFull)
        assertEquals(0, writes)
        assertNull(store.files(44))
        assertTrue(store.usedBytes() < 128L * 1024 * 1024)
    }

    @Test
    fun `lowering quota keeps completed chapters and deleting a chapter releases its storage`() = runBlocking {
        val store = store()
        downloader(store).download(store.next()!!)
        val before = store.usedBytes()
        store.configure(true, 128)
        assertNotNull(store.files(44))
        store.remove(44)
        assertNull(store.files(44))
        assertTrue(store.state.value.titles.isEmpty())
        assertTrue(store.usedBytes() < before)
    }

    @Test
    fun `deleting an active chapter defers file removal until its reader releases it`() = runBlocking {
        val store = store()
        downloader(store).download(store.next()!!)
        val lease = store.acquire(44)!!
        store.remove(44)
        assertNull(store.files(44))
        assertTrue(lease.all { it.isFile })
        store.release(44)
        assertTrue(lease.none { it.exists() })
    }

    @Test
    fun `a missing cover does not stop chapter downloads`() = runBlocking {
        val store = store()
        store.remember(manga.copy(thumbnailUrl = "/broken-cover"), listOf(chapter))
        downloader(store, fetch = { path, file, guard ->
            if (path.startsWith("/broken-cover")) throw SuwayomiException(SuwayomiFailure.CONTRACT)
            guard(1)
            file.writeText("x")
        }).download(store.next()!!)
        assertNotNull(store.files(44))
        assertNull(store.cover(17))
    }

    @Test
    fun `changed remote chapter identity cannot populate an offline download`() = runBlocking {
        val store = store()
        var wrote = false
        val error = runCatching {
            SuwayomiOfflineDownloader(store, { chapter.copy(url = "different-title") }, { ServerPages(urls, chapter) }, { _, _, _ -> wrote = true }, {}).download(store.next()!!)
        }.exceptionOrNull()
        assertEquals(SuwayomiFailure.SESSION_CHANGED, (error as SuwayomiException).failure)
        assertFalse(wrote)
        assertNull(store.files(44))
    }

    @Test
    fun `a truncated completed file is never returned to the reader`() = runBlocking {
        val store = store()
        downloader(store).download(store.next()!!)
        store.files(44)!![0].writeBytes(byteArrayOf())
        assertNull(SuwayomiOfflineStore(directory).files(44))
    }

    @Test
    fun `offline progress survives restart after the pending operation has synchronized`() {
        val store = store()
        store.patch(44, ChapterPatch(lastPageRead = 2, isRead = true))
        val restored = SuwayomiOfflineStore(directory).state.value.titles.getValue(17)
        assertEquals(2, restored.chapters.single().user.lastPageRead)
        assertTrue(restored.chapters.single().user.isRead)
        assertEquals(0, restored.manga.user.unreadCount)
    }

    @Test
    fun `requests failing before sending a mutation stay pending for automatic reconnect retry`() {
        assertEquals(ChapterIntentStatus.PENDING, failedIntentStatus(ChapterIntentStatus.PENDING, false, SuwayomiFailure.NETWORK))
        assertEquals(ChapterIntentStatus.AMBIGUOUS, failedIntentStatus(ChapterIntentStatus.PENDING, true, SuwayomiFailure.NETWORK))
        assertEquals(ChapterIntentStatus.AMBIGUOUS, failedIntentStatus(ChapterIntentStatus.AMBIGUOUS, false, SuwayomiFailure.NETWORK))
        assertEquals(ChapterIntentStatus.FAILED, failedIntentStatus(ChapterIntentStatus.PENDING, false, SuwayomiFailure.AUTHENTICATION_REQUIRED))
    }

    @Test
    fun `remote ID reuse invalidates saved files before a new title is displayed`() = runBlocking {
        val store = store()
        downloader(store).download(store.next()!!)
        store.remember(manga.copy(url = "restored/other-title"), listOf(chapter.copy(url = "restored/other-chapter")))
        assertNull(store.files(44))
        assertEquals(OfflineState.ERROR, store.state.value.downloads.single().state)
    }

    @Test
    fun `paused queue stays paused after process restart`() {
        val store = store()
        store.pause(true)
        assertNull(SuwayomiOfflineStore(directory).next())
        store.pause(false)
        assertNotNull(store.next())
    }

    @Test
    fun `cancellation keeps completed pages and never publishes a partial chapter`() = runBlocking {
        val store = store()
        val result = runCatching {
            downloader(store, fetch = { path, file, guard ->
                if (path == urls[1]) throw kotlinx.coroutines.CancellationException()
                guard(1)
                file.writeText("x")
            }).download(store.next()!!)
        }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(store.files(44))
        val retried = mutableListOf<String>()
        downloader(store, fetch = { path, file, guard ->
            retried += path
            guard(1)
            file.writeText("x")
        }).download(store.next()!!)
        assertEquals(urls.drop(1), retried)
    }

    @Test
    fun `disconnect clears account downloads and closed stores cannot write late results`() = runBlocking {
        val store = store()
        downloader(store).download(store.next()!!)
        store.close(true)
        assertFalse(directory.exists())
        assertNull(store.files(44))
        assertThrows(IllegalStateException::class.java) { store.patch(44, ChapterPatch(lastPageRead = 2)) }
        assertFalse(directory.exists())
    }
}
// KMK <--
