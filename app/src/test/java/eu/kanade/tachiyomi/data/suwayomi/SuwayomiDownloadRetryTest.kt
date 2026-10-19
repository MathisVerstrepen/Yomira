// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SuwayomiDownloadRetryTest {
    private fun download(id: Int, state: String) = ServerDownload(
        chapter = ServerChapter(id, 7, "Chapter $id", id.toDouble(), id, pageCount = -1, user = ServerChapterUser(false, false, 0, 0, false), url = "chapter-$id", manga = ServerChapterMangaIdentity(8, "manga")),
        manga = ServerManga(7, 8, "Title", user = ServerMangaUser(true)),
        state = state,
        progress = 0f,
    )

    @Test
    fun `resume requeues failed jobs before starting and leaves active jobs alone`() = runBlocking {
        val session = mockk<SuwayomiSession>()
        val failed = download(1, "ERROR")
        coEvery { session.chapter(1) } returns failed.chapter
        coEvery { session.execute(any(), any()) } returns JsonObject(emptyMap())
        session.resumeServerDownloads(ServerDownloads("STOPPED", listOf(failed, download(2, "QUEUED"), download(3, "DOWNLOADING"))))
        coVerifyOrder {
            session.chapter(1)
            session.execute(match { it.contains("enqueueChapterDownload(") }, match { it["input"]?.jsonObject?.get("id") == JsonPrimitive(1) })
            session.execute(match { it.contains("startDownloader(") }, any())
        }
        coVerify(exactly = 2) { session.execute(any(), any()) }
        coVerify(exactly = 0) { session.chapter(2) }
        coVerify(exactly = 0) { session.chapter(3) }
    }

    @Test
    fun `resume with no failed entries simply starts the downloader`() = runBlocking {
        val session = mockk<SuwayomiSession>()
        coEvery { session.execute(any(), any()) } returns JsonObject(emptyMap())
        session.resumeServerDownloads(ServerDownloads("STOPPED", listOf(download(2, "QUEUED"))))
        coVerify(exactly = 1) { session.execute(match { it.contains("startDownloader(") }, any()) }
        coVerify(exactly = 0) { session.chapter(any()) }
    }

    @Test
    fun `reused chapter identity aborts the entire retry batch before any mutation`() = runBlocking<Unit> {
        val session = mockk<SuwayomiSession>()
        val first = download(1, "ERROR")
        val second = download(2, "ERROR")
        coEvery { session.chapter(1) } returns first.chapter
        coEvery { session.chapter(2) } returns second.chapter.copy(url = "different-chapter")
        assertThrows(SuwayomiException::class.java) { runBlocking { session.resumeServerDownloads(ServerDownloads("STOPPED", listOf(first, second))) } }
        coVerify(exactly = 0) { session.execute(any(), any()) }
    }
}
// KMK <--
