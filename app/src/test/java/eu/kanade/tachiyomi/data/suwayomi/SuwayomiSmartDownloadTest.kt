// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SuwayomiSmartDownloadTest {
    @TempDir lateinit var directory: File
    private val manga = ServerManga(17, 8, "Selected title", user = ServerMangaUser(true), url = "manga/17")
    private fun chapters(count: Int = 6) = (1..count).map { id ->
        ServerChapter(id, 17, "Chapter $id", id.toDouble(), id, pageCount = 1, user = ServerChapterUser(false, false, 0, 0, false), url = "chapter/$id", manga = ServerChapterMangaIdentity(8, "manga/17"))
    }
    private fun store() = SuwayomiOfflineStore(directory).also { it.selectSmart(manga, chapters(), true) }
    private suspend fun complete(store: SuwayomiOfflineStore, id: Int) {
        val chapter = chapters().first { it.id == id }
        SuwayomiOfflineDownloader(store, { chapter }, { ServerPages(listOf("/page"), chapter) }, { _, file, guard ->
            guard(1)
            file.writeText("x")
        }, {}).download(store.state.value.downloads.first { it.chapterId == id })
    }
    private fun read(chapters: List<ServerChapter>, id: Int) = chapters.map { if (it.id == id) it.copy(user = it.user.copy(isRead = true)) else it }

    @Test
    fun `old manifests default to no automation`() {
        val old = Json.decodeFromString<OfflineSnapshot>("{\"wifiOnly\":false,\"limitMiB\":512}")
        assertTrue(old.smartTitles.isEmpty())
        assertFalse(old.wifiOnly)
        assertEquals(512, old.limitMiB)
    }

    @Test
    fun `only selected titles queue next unread chapters in source order without restarting transfers`() {
        val store = SuwayomiOfflineStore(directory)
        store.reconcileSmart(manga, chapters(), emptyList())
        assertTrue(store.state.value.downloads.isEmpty())
        store.selectSmart(manga, chapters(), true)
        store.reconcileSmart(manga, read(chapters(), 1).reversed(), emptyList())
        val queued = store.state.value.downloads
        assertEquals(listOf(2, 3, 4), queued.map { it.chapterId })
        assertTrue(queued.all { it.automatic })
        store.reconcileSmart(manga, read(chapters(), 1), emptyList())
        assertEquals(queued, store.state.value.downloads)
    }

    @Test
    fun `local read progress never deletes files until fresh server confirmation and pending intents drain`() = runBlocking {
        val store = store()
        store.reconcileSmart(manga, chapters(), emptyList())
        complete(store, 1)
        store.patch(1, ChapterPatch(isRead = true))
        val intent = ChapterIntent(chapterId = 1, kind = ChapterIntentKind.MARK_READ, patch = ChapterPatch(isRead = true), identity = chapters().first().identity())
        store.reconcileSmart(manga, chapters(), listOf(intent))
        assertNotNull(store.files(1))
        store.reconcileSmart(manga, read(chapters(), 1), listOf(intent.copy(status = ChapterIntentStatus.AMBIGUOUS)))
        assertNotNull(store.files(1))
        store.reconcileSmart(manga, read(chapters(), 1), emptyList())
        assertNull(store.files(1))
        assertEquals(listOf(2, 3, 4), store.state.value.downloads.map { it.chapterId })
    }

    @Test
    fun `pending mark unread protects a remotely read copy`() = runBlocking {
        val store = store()
        store.reconcileSmart(manga, chapters(), emptyList())
        complete(store, 1)
        val intent = ChapterIntent(chapterId = 1, kind = ChapterIntentKind.MARK_UNREAD, patch = ChapterPatch(isRead = false), identity = chapters().first().identity())
        store.reconcileSmart(manga, read(chapters(), 1), listOf(intent))
        assertNotNull(store.files(1))
        assertEquals(listOf(1, 2, 3), store.state.value.downloads.map { it.chapterId })
    }

    @Test
    fun `cleanup preserves manual downloads and respects disabling cleanup`() = runBlocking {
        val store = store()
        store.reconcileSmart(manga, chapters(), emptyList())
        complete(store, 1)
        complete(store, 2)
        store.enqueue(manga, chapters(), listOf(2))
        store.configureSmart(3, false)
        store.reconcileSmart(manga, read(read(chapters(), 1), 2), emptyList())
        assertNotNull(store.files(1))
        store.configureSmart(3, true)
        store.reconcileSmart(manga, read(read(chapters(), 1), 2), emptyList())
        assertNull(store.files(1))
        assertNotNull(store.files(2))
    }

    @Test
    fun `cleanup defers physical deletion until the active reader releases its lease`() = runBlocking {
        val store = store()
        store.reconcileSmart(manga, chapters(), emptyList())
        complete(store, 1)
        val lease = store.acquire(1)!!
        store.reconcileSmart(manga, read(chapters(), 1), emptyList())
        assertNull(store.files(1))
        assertTrue(lease.all { it.exists() })
        store.release(1)
        assertTrue(lease.none { it.exists() })
    }

    @Test
    fun `caught up selected titles survive deleting the last copy and discover new updates after restart`() = runBlocking {
        val store = store()
        store.configureSmart(2, true)
        store.configure(false, 512)
        store.reconcileSmart(manga, chapters(1), emptyList())
        complete(store, 1)
        store.reconcileSmart(manga, read(chapters(1), 1), emptyList())
        assertTrue(store.state.value.downloads.isEmpty())
        val restored = SuwayomiOfflineStore(directory)
        assertEquals(setOf(17), restored.state.value.smartTitles)
        assertEquals(2, restored.state.value.smartChapterCount)
        assertFalse(restored.state.value.wifiOnly)
        assertEquals(512, restored.state.value.limitMiB)
        restored.reconcileSmart(manga, read(chapters(4), 1), emptyList())
        assertEquals(listOf(2, 3), restored.state.value.downloads.map { it.chapterId })
    }

    @Test
    fun `pause blocks upkeep and disabling title cancels automatic queue but keeps complete copies`() = runBlocking {
        val store = store()
        store.pause(true)
        store.reconcileSmart(manga, chapters(), emptyList())
        assertTrue(store.state.value.downloads.isEmpty())
        store.pause(false)
        store.reconcileSmart(manga, chapters(), emptyList())
        complete(store, 1)
        store.selectSmart(manga, chapters(), false)
        assertTrue(store.state.value.smartTitles.isEmpty())
        assertEquals(listOf(1), store.state.value.downloads.map { it.chapterId })
        assertNotNull(store.files(1))
    }

    @Test
    fun `restored title identity disables automation and never downloads its replacement`() {
        val store = store()
        store.reconcileSmart(manga.copy(url = "replacement"), chapters(), emptyList())
        assertTrue(store.state.value.smartTitles.isEmpty())
        assertTrue(store.state.value.downloads.isEmpty())
    }

    @Test
    fun `manual removal stays removed across upkeep and errors keep their generation`() {
        val store = store()
        store.reconcileSmart(manga, chapters(), emptyList())
        store.remove(1)
        store.update(2, OfflineState.ERROR)
        val queued = store.state.value.downloads
        store.reconcileSmart(manga, chapters(), emptyList())
        assertEquals(queued, store.state.value.downloads)
    }
}
// KMK <--
