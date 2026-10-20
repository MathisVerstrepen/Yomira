// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiMigrationTest {
    private fun manga(id: Int, categories: List<ServerCategory> = emptyList()) = ServerManga(
        id,
        id.toLong(),
        "Title $id",
        user = ServerMangaUser(id == 1),
        url = "/manga/$id",
        categories = ServerMangaCategories(categories),
    )

    private fun chapter(id: Int, number: Double = id.toDouble(), mangaId: Int = 1, name: String = "Chapter $number", read: Boolean = false, bookmark: Boolean = false, page: Int = 0, scanlator: String? = null) = ServerChapter(
        id = id, mangaId = mangaId, name = name, chapterNumber = number, sourceOrder = id, scanlator = scanlator,
        pageCount = 20, user = ServerChapterUser(read, bookmark, page, 0, false), url = "/chapter/$id", manga = ServerChapterMangaIdentity(mangaId.toLong(), "/manga/$mangaId"),
    )

    @Test
    fun `matches decimals and chapter zero without guessing gaps`() {
        val old = listOf(chapter(1, 0.0), chapter(2, 1.5), chapter(3, 2.0, read = true))
        val new = listOf(chapter(11, 0.0, 2), chapter(12, 1.5, 2), chapter(13, 1.0, 2))
        val matches = matchMigrationChapters(old, new)
        assertEquals(listOf(11, 12, null), matches.map { it.replacement?.id })
    }

    @Test
    fun `unnumbered specials match by unique normalized name and never by unknown number`() {
        val old = listOf(chapter(1, -1.0, name = " Bonus: Story! "), chapter(2, -1.0, name = "Other"), chapter(3, Double.NaN, name = ""))
        val new = listOf(chapter(11, -1.0, 2, "bonus story"), chapter(12, -1.0, 2, ""))
        assertEquals(listOf(11, null, null), matchMigrationChapters(old, new).map { it.replacement?.id })
    }

    @Test
    fun `duplicate numbers require a unique title or scanlator`() {
        val old = chapter(1, 1.0, name = "Original", scanlator = "B")
        val new = listOf(chapter(11, 1.0, 2, "Edition A", scanlator = "A"), chapter(12, 1.0, 2, "Edition B", scanlator = "B"))
        assertEquals(12, matchMigrationChapters(listOf(old), new).single().replacement?.id)
        val ambiguous = matchMigrationChapters(listOf(old.copy(scanlator = null)), new).single()
        assertNull(ambiguous.replacement)
        assertEquals(MigrationMatchKind.AMBIGUOUS, ambiguous.kind)
    }

    @Test
    fun `special titles retain decimals and repeated original names are ambiguous`() {
        val old = listOf(chapter(1, -1.0, name = "Special 1.5"), chapter(2, -1.0, name = "Bonus"), chapter(3, -1.0, name = "Bonus"))
        val new = listOf(chapter(11, -1.0, 2, "Special 15"), chapter(12, -1.0, 2, "Bonus"))
        val matches = matchMigrationChapters(old, new)
        assertTrue(matches.all { it.replacement == null })
        assertEquals(MigrationMatchKind.MISSING, matches[0].kind)
        assertEquals(MigrationMatchKind.AMBIGUOUS, matches[1].kind)
    }

    private inner class Fixture : SourceMigrationGateway {
        val category = ServerCategory(5, "Favorites", 1)
        val otherCategory = ServerCategory(6, "Existing", 2)
        var original = manga(1, listOf(category))
        var replacement = manga(2, listOf(otherCategory))
        var originals = listOf(chapter(1, 1.0, read = true, bookmark = true, page = 5), chapter(2, 2.0, page = 3))
        var replacements = listOf(chapter(11, 1.0, 2, page = 8), chapter(12, 2.0, 2, bookmark = true), chapter(13, 0.5, 2))
        val writes = mutableListOf<String>()
        val chapterPatches = mutableListOf<ChapterPatch>()
        var failAt: String? = null
        var ignoreWrites = false
        var ignoreChapterWrites = false
        var fetchedPageCount = 40
        val pageRequests = mutableListOf<Int>()
        var fetchPages: (ServerChapter) -> ServerChapter = { it.copy(pageCount = fetchedPageCount) }
        var onReady: (() -> Unit)? = null
        var beforeRemoval: (() -> Unit)? = null
        var readyCount = 0
        val preview get() = SourceMigrationPreview(original, replacement, originals.toList(), replacements.toList())
        override suspend fun ready() {
            readyCount++
            onReady?.invoke()
            if (readyCount == 2) beforeRemoval?.invoke()
        }
        override suspend fun manga(id: Int) = if (id == original.id) original else replacement
        override suspend fun chapters(id: Int) = if (id == original.id) originals else replacements
        override suspend fun chapter(id: Int) = replacements.first { it.id == id }
        override suspend fun fetchChapterPages(id: Int): ServerChapter {
            pageRequests += id
            val result = fetchPages(chapter(id))
            replacements = replacements.map { if (it.id == id) result else it }
            return result
        }
        private fun write(operation: String) {
            if (operation == failAt) throw SuwayomiException(SuwayomiFailure.NETWORK)
            writes += operation
        }
        override suspend fun patch(chapter: ServerChapter, patch: ChapterPatch) {
            write("chapter:${chapter.id}")
            chapterPatches += patch
            if (ignoreWrites || ignoreChapterWrites) return
            replacements = replacements.map {
                if (it.id != chapter.id) it else it.copy(user = it.user.copy(isRead = patch.isRead ?: it.user.isRead, isBookmarked = patch.isBookmarked ?: it.user.isBookmarked, lastPageRead = patch.lastPageRead?.coerceIn(0, maxOf(0, it.pageCount)) ?: it.user.lastPageRead))
            }
        }
        override suspend fun addCategory(mangaId: Int, categoryId: Int) {
            write("category:$categoryId")
            if (!ignoreWrites) replacement = replacement.copy(categories = ServerMangaCategories(replacement.categories.nodes + category))
        }
        override suspend fun library(mangaId: Int, member: Boolean) {
            write("library:$mangaId:$member")
            if (ignoreWrites) return
            if (mangaId == 1) original = original.copy(user = original.user.copy(inLibrary = member)) else replacement = replacement.copy(user = replacement.user.copy(inLibrary = member))
        }
    }

    @Test
    fun `default migration merges progress bookmarks and categories then removes original last`() = runBlocking {
        val fixture = Fixture()
        migrateSource(fixture, fixture.preview, SourceMigrationOptions())
        assertEquals("library:1:false", fixture.writes.last())
        assertFalse(fixture.original.user.inLibrary)
        assertTrue(fixture.replacement.user.inLibrary)
        assertEquals(setOf(5, 6), fixture.replacement.categories.nodes.map { it.id }.toSet())
        assertTrue(fixture.replacements[0].user.isRead)
        assertTrue(fixture.replacements[0].user.isBookmarked)
        assertEquals(8, fixture.replacements[0].user.lastPageRead)
        assertTrue(fixture.replacements[1].user.isBookmarked)
        assertEquals(3, fixture.replacements[1].user.lastPageRead)
        assertFalse(fixture.replacements[2].user.isRead)
    }

    @Test
    fun `explicit keep original option respects unchecked transfer options`() = runBlocking {
        val fixture = Fixture()
        migrateSource(fixture, fixture.preview, SourceMigrationOptions(progress = false, bookmarks = false, categories = false, keepOriginal = true))
        assertEquals(listOf("library:2:true"), fixture.writes)
        assertTrue(fixture.original.user.inLibrary)
    }

    @Test
    fun `multiple original editions combine saved state without overwriting destination`() = runBlocking {
        val fixture = Fixture()
        fixture.originals = listOf(chapter(1, 1.0, read = true), chapter(2, 1.0, bookmark = true, page = 12))
        migrateSource(fixture, fixture.preview, SourceMigrationOptions())
        assertTrue(fixture.replacements[0].user.isRead)
        assertTrue(fixture.replacements[0].user.isBookmarked)
        assertEquals(8, fixture.replacements[0].user.lastPageRead)
    }

    @Test
    fun `completed chapters with unknown length transfer completion without invalid page offsets`() = runBlocking {
        for (alreadyRead in listOf(false, true)) {
            val fixture = Fixture()
            fixture.originals = listOf(chapter(1, 1.0, read = true, bookmark = true, page = 36).copy(pageCount = -1))
            fixture.replacements = listOf(chapter(11, 1.0, 2, read = alreadyRead).copy(pageCount = -1))
            migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false))
            assertTrue(fixture.replacements.single().user.isRead)
            assertTrue(fixture.replacements.single().user.isBookmarked)
            assertEquals(0, fixture.replacements.single().user.lastPageRead)
            assertTrue(fixture.chapterPatches.all { it.lastPageRead == null })
            assertTrue(fixture.pageRequests.isEmpty())
            assertEquals("library:1:false", fixture.writes.last())
        }
    }

    @Test
    fun `in progress migration loads only unknown destination lengths and caps to last page`() = runBlocking {
        val fixture = Fixture()
        fixture.originals = listOf(chapter(1, 1.0, page = 36))
        fixture.replacements = listOf(chapter(11, 1.0, 2).copy(pageCount = -1))
        fixture.fetchedPageCount = 10
        migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false))
        assertEquals(listOf(11), fixture.pageRequests)
        assertEquals(9, fixture.replacements.single().user.lastPageRead)
        assertFalse(fixture.replacements.single().user.isRead)
        assertEquals("library:1:false", fixture.writes.last())
    }

    @Test
    fun `known shorter replacement caps progress without fetching pages`() = runBlocking {
        val fixture = Fixture()
        fixture.originals = listOf(chapter(1, 1.0, page = 36))
        fixture.replacements = listOf(chapter(11, 1.0, 2).copy(pageCount = 7))
        migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false))
        assertEquals(6, fixture.replacements.single().user.lastPageRead)
        assertTrue(fixture.pageRequests.isEmpty())
        assertFalse(fixture.original.user.inLibrary)
    }

    @Test
    fun `existing replacement completion is preserved without fetching or applying partial progress`() = runBlocking {
        val fixture = Fixture()
        fixture.originals = listOf(chapter(1, 1.0, page = 36))
        fixture.replacements = listOf(chapter(11, 1.0, 2, read = true).copy(pageCount = -1))
        migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false))
        assertTrue(fixture.replacements.single().user.isRead)
        assertEquals(0, fixture.replacements.single().user.lastPageRead)
        assertTrue(fixture.pageRequests.isEmpty())
        assertFalse(fixture.writes.any { it.startsWith("chapter:") })
        assertFalse(fixture.original.user.inLibrary)
    }

    @Test
    fun `completion arriving during page preparation is kept instead of applying stale partial progress`() = runBlocking {
        val fixture = Fixture()
        fixture.originals = listOf(chapter(1, 1.0, page = 36))
        fixture.replacements = listOf(chapter(11, 1.0, 2).copy(pageCount = -1))
        fixture.fetchPages = { it.copy(pageCount = 40, user = it.user.copy(isRead = true)) }
        migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false))
        assertEquals(listOf(11), fixture.pageRequests)
        assertTrue(fixture.replacements.single().user.isRead)
        assertFalse(fixture.writes.any { it.startsWith("chapter:") })
        assertFalse(fixture.original.user.inLibrary)
    }

    @Test
    fun `unknown length is not fetched for unchecked progress or progress already ahead`() = runBlocking {
        for (options in listOf(SourceMigrationOptions(progress = false), SourceMigrationOptions())) {
            val fixture = Fixture()
            fixture.originals = listOf(chapter(1, 1.0, page = 12, bookmark = true))
            fixture.replacements = listOf(chapter(11, 1.0, 2, page = 15).copy(pageCount = -1))
            migrateSource(fixture, fixture.preview, options)
            assertTrue(fixture.pageRequests.isEmpty())
            assertEquals(15, fixture.replacements.single().user.lastPageRead)
        }
    }

    @Test
    fun `page lookup failures cancellation invalid identity and empty chapters preserve the original`() {
        val cases: List<(ServerChapter) -> ServerChapter> = listOf(
            { throw SuwayomiException(SuwayomiFailure.NETWORK) },
            { throw CancellationException() },
            { it.copy(pageCount = 0) },
            { it.copy(pageCount = 10, url = "/different") },
        )
        for (fetch in cases) {
            val fixture = Fixture()
            fixture.originals = listOf(chapter(1, 1.0, page = 36))
            fixture.replacements = listOf(chapter(11, 1.0, 2).copy(pageCount = -1))
            fixture.fetchPages = fetch
            assertTrue(runCatching { runBlocking { migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false)) } }.isFailure)
            assertTrue(fixture.original.user.inLibrary)
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test
    fun `verification rejects a server dropping representable in progress pages`() {
        val fixture = Fixture()
        fixture.originals = listOf(chapter(1, 1.0, page = 12))
        fixture.replacements = listOf(chapter(11, 1.0, 2))
        fixture.ignoreChapterWrites = true
        assertThrows(SuwayomiException::class.java) { runBlocking { migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false)) } }
        assertTrue(fixture.original.user.inLibrary)
        assertFalse("library:1:false" in fixture.writes)
    }

    @Test
    fun `gateway page preparation returns fresh identity and validates the page payload`() = runBlocking {
        val session = mockk<SuwayomiSession>()
        val chapter = chapter(11, 1.0, 2).copy(pageCount = 2)
        coEvery { session.pages(11) } returns ServerPages(listOf("/page/0", "/page/1"), chapter)
        val gateway = SessionMigrationGateway(session) { true }
        assertEquals(chapter, gateway.fetchChapterPages(11))
        coVerify(exactly = 1) { session.pages(11) }
        coVerify(exactly = 0) { session.image(any()) }
        coEvery { session.pages(11) } returns ServerPages(listOf("/page/0", "/page/1"), chapter, ServerSyncConflict("Other reader", 1))
        assertTrue(runCatching { gateway.fetchChapterPages(11) }.exceptionOrNull() is SourceMigrationChanged)
        for (invalid in listOf(ServerPages(emptyList(), chapter), ServerPages(listOf("/one"), chapter), ServerPages(listOf("/a", "/b"), chapter.copy(id = 12)))) {
            coEvery { session.pages(11) } returns invalid
            assertEquals(SuwayomiFailure.CONTRACT, (runCatching { gateway.fetchChapterPages(11) }.exceptionOrNull() as? SuwayomiException)?.failure)
        }
    }

    @Test
    fun `every transfer failure preserves original membership`() {
        listOf("chapter:11", "chapter:12", "category:5", "library:2:true").forEach { operation ->
            val fixture = Fixture().apply { failAt = operation }
            assertThrows(SuwayomiException::class.java) { runBlocking { migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false)) } }
            assertTrue(fixture.original.user.inLibrary, operation)
            assertFalse("library:1:false" in fixture.writes, operation)
        }
    }

    @Test
    fun `successful HTTP responses without persisted state cannot remove original`() {
        val fixture = Fixture().apply { ignoreWrites = true }
        assertThrows(SuwayomiException::class.java) { runBlocking { migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false)) } }
        assertTrue(fixture.original.user.inLibrary)
        assertFalse("library:1:false" in fixture.writes)
    }

    @Test
    fun `pending changes and cancellation stop before writes`() {
        val fixture = Fixture().apply { onReady = { throw CancellationException() } }
        assertThrows(CancellationException::class.java) { runBlocking { migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false)) } }
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun `unmatched saved chapters require keeping the original`() {
        val fixture = Fixture()
        fixture.originals = fixture.originals + chapter(3, 99.0, bookmark = true)
        assertEquals(1, fixture.preview.unmatchedSaved(SourceMigrationOptions()))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { migrateSource(fixture, fixture.preview, SourceMigrationOptions()) } }
        assertTrue(fixture.writes.isEmpty())
        runBlocking { migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = true)) }
        assertTrue(fixture.original.user.inLibrary)
    }

    @Test
    fun `stale matching identity or original progress rejects the preview`() {
        val fixture = Fixture()
        val preview = fixture.preview
        fixture.originals = fixture.originals.map { it.copy(user = it.user.copy(lastPageRead = 9)) }
        assertThrows(SourceMigrationChanged::class.java) { runBlocking { migrateSource(fixture, preview, SourceMigrationOptions()) } }
        assertTrue(fixture.writes.isEmpty())

        val another = Fixture()
        val anotherPreview = another.preview
        another.replacements = another.replacements.map { it.copy(url = "/different") }
        assertThrows(SourceMigrationChanged::class.java) { runBlocking { migrateSource(another, anotherPreview, SourceMigrationOptions()) } }
        assertTrue(another.writes.isEmpty())
    }

    @Test
    fun `new progress arriving during transfer keeps original`() {
        val fixture = Fixture()
        fixture.beforeRemoval = { fixture.originals = fixture.originals.map { it.copy(user = it.user.copy(lastPageRead = 19)) } }
        assertThrows(SourceMigrationChanged::class.java) { runBlocking { migrateSource(fixture, fixture.preview, SourceMigrationOptions(keepOriginal = false)) } }
        assertTrue(fixture.original.user.inLibrary)
        assertFalse("library:1:false" in fixture.writes)
    }

    @Test
    fun `retry after partial transfer preserves and verifies merged data`() = runBlocking {
        val fixture = Fixture().apply { failAt = "category:5" }
        val preview = fixture.preview
        try {
            migrateSource(fixture, preview, SourceMigrationOptions(keepOriginal = false))
        } catch (_: SuwayomiException) { }
        fixture.failAt = null
        migrateSource(fixture, preview, SourceMigrationOptions(keepOriginal = false))
        assertFalse(fixture.original.user.inLibrary)
        assertEquals(1, fixture.writes.count { it == "chapter:11" })
        assertEquals(1, fixture.writes.count { it == "chapter:12" })
    }

    @Test
    fun `session gateway rejects unresolved queued state and incognito before mutations`() = runBlocking {
        val session = mockk<SuwayomiSession>()
        coEvery { session.flush() } returns Unit
        every { session.pending } returns MutableStateFlow(listOf(ChapterIntent(chapterId = 1, kind = ChapterIntentKind.MARK_READ, patch = ChapterPatch(isRead = true))))
        every { session.mutationFailure } returns MutableStateFlow(SuwayomiFailure.NETWORK)
        assertThrows(SuwayomiException::class.java) { runBlocking { SessionMigrationGateway(session) { true }.ready() } }
        assertThrows(SuwayomiException::class.java) { runBlocking { SessionMigrationGateway(session) { false }.patch(chapter(1), ChapterPatch(isRead = true)) } }
        coVerify(exactly = 0) { session.execute(any(), any()) }
        coVerify(exactly = 0) { session.setLibrary(any(), any()) }
    }

    @Test
    fun `preview fetches only replacement provider and includes all cached original chapters`() = runBlocking {
        val session = mockk<SuwayomiSession>()
        val fixture = Fixture()
        coEvery { session.flush() } returns Unit
        every { session.pending } returns MutableStateFlow(emptyList())
        coEvery { session.manga(1) } returns fixture.original
        coEvery { session.manga(2) } returns fixture.replacement
        coEvery { session.refreshManga(2) } returns Unit
        coEvery { session.chapters(1) } returns fixture.originals
        coEvery { session.chapters(2) } returns fixture.replacements
        val preview = session.previewMigration(1, 2)
        assertEquals(fixture.originals, preview.originalChapters)
        coVerify(exactly = 1) { session.refreshManga(2) }
        coVerify(exactly = 0) { session.refreshManga(1) }
    }

    @Test
    fun `gateway chapter mutation uses exact IDs and only selected patch fields`() = runBlocking {
        val session = mockk<SuwayomiSession>()
        every { session.pending } returns MutableStateFlow(emptyList())
        coEvery { session.execute(any(), any()) } returns JsonObject(emptyMap())
        SessionMigrationGateway(session) { true }.patch(chapter(11, mangaId = 2), ChapterPatch(lastPageRead = 8, isBookmarked = true))
        coVerify(exactly = 1) {
            session.execute(
                match { it.contains("UpdateChapterInput!") && it.contains("updateChapter(") },
                match {
                    val input = it.getValue("input").jsonObject
                    val patch = input.getValue("patch").jsonObject
                    input["id"] == JsonPrimitive(11) && patch["lastPageRead"] == JsonPrimitive(8) && patch["isBookmarked"] == JsonPrimitive(true) && "isRead" !in patch
                },
            )
        }
    }
}
// KMK <--
