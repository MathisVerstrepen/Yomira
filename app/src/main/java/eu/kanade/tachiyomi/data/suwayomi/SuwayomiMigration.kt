// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

internal enum class MigrationMatchKind { NUMBER, NAME, MISSING, AMBIGUOUS }

internal data class MigrationChapterMatch(
    val original: ServerChapter,
    val replacement: ServerChapter?,
    val kind: MigrationMatchKind,
)

internal data class SourceMigrationPreview(
    val original: ServerManga,
    val replacement: ServerManga,
    val originalChapters: List<ServerChapter>,
    val replacementChapters: List<ServerChapter>,
    val matches: List<MigrationChapterMatch> = matchMigrationChapters(originalChapters, replacementChapters),
) {
    fun unmatchedSaved(options: SourceMigrationOptions): Int = matches.count {
        it.replacement == null && ((options.progress && (it.original.user.isRead || it.original.user.lastPageRead > 0)) || (options.bookmarks && it.original.user.isBookmarked))
    }
}

internal data class SourceMigrationOptions(
    val progress: Boolean = true,
    val bookmarks: Boolean = true,
    val categories: Boolean = true,
    val keepOriginal: Boolean = false,
)

/** Match exact finite numbers, then unique names for unnumbered specials. Never infer read gaps. */
internal fun matchMigrationChapters(original: List<ServerChapter>, replacement: List<ServerChapter>): List<MigrationChapterMatch> {
    // Keep decimal points: "Special 1.5" must never collapse into "Special 15".
    fun String.normalized() = lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}.]+"), " ").trim()
    fun ServerChapter.numbered() = chapterNumber.isFinite() && chapterNumber >= 0
    val byNumber = replacement.filter { it.numbered() }.groupBy { it.chapterNumber }
    val byName = replacement.filter { it.name.normalized().isNotEmpty() }.groupBy { it.name.normalized() }
    val originalNames = original.groupingBy { it.name.normalized() }.eachCount()
    return original.map { chapter ->
        val numbered = chapter.numbered()
        val candidates = if (numbered) {
            byNumber[chapter.chapterNumber].orEmpty()
        } else {
            byName[chapter.name.normalized()].orEmpty()
        }
        val named = candidates.filter { it.name.normalized() == chapter.name.normalized() }
        val sameGroup = candidates.filter { !chapter.scanlator.isNullOrBlank() && it.scanlator == chapter.scanlator }
        val repeatedSpecial = !numbered && originalNames[chapter.name.normalized()] != 1
        val selected = if (repeatedSpecial) null else candidates.singleOrNull() ?: named.singleOrNull() ?: sameGroup.singleOrNull()
        MigrationChapterMatch(
            chapter,
            selected,
            when {
                selected != null -> if (numbered) MigrationMatchKind.NUMBER else MigrationMatchKind.NAME
                candidates.isEmpty() -> MigrationMatchKind.MISSING
                else -> MigrationMatchKind.AMBIGUOUS
            },
        )
    }
}

/** A small boundary lets failure, cancellation and verification ordering be tested without Android. */
internal interface SourceMigrationGateway {
    suspend fun ready()
    suspend fun manga(id: Int): ServerManga
    suspend fun chapters(id: Int): List<ServerChapter>
    suspend fun chapter(id: Int): ServerChapter
    suspend fun fetchChapterPages(id: Int): ServerChapter
    suspend fun patch(chapter: ServerChapter, patch: ChapterPatch)
    suspend fun addCategory(mangaId: Int, categoryId: Int)
    suspend fun library(mangaId: Int, member: Boolean)
}

internal class SourceMigrationChanged : Exception()

private data class MigrationReadingState(val isRead: Boolean, val lastPageRead: Int)

internal class SessionMigrationGateway(private val session: SuwayomiSession, private val canWrite: () -> Boolean) : SourceMigrationGateway {
    override suspend fun ready() {
        if (!canWrite()) throw SuwayomiException(SuwayomiFailure.PERMISSION_DENIED)
        session.flush()
        if (session.pending.value.isNotEmpty()) throw SuwayomiException(session.mutationFailure.value ?: SuwayomiFailure.SERVER)
        // Also checks that this exact connection is still active.
        session.user()
    }
    override suspend fun manga(id: Int) = session.manga(id)
    override suspend fun chapters(id: Int) = session.chapters(id)
    override suspend fun chapter(id: Int) = session.chapter(id)
    override suspend fun fetchChapterPages(id: Int): ServerChapter {
        val result = session.pages(id)
        if (result.syncConflict != null) throw SourceMigrationChanged()
        if (result.chapter.id != id || result.pages.isEmpty() || result.chapter.pageCount != result.pages.size) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        return result.chapter
    }
    private fun requireWrite() {
        if (!canWrite()) throw SuwayomiException(SuwayomiFailure.PERMISSION_DENIED)
        if (session.pending.value.isNotEmpty()) throw SourceMigrationChanged()
    }
    override suspend fun patch(chapter: ServerChapter, patch: ChapterPatch) {
        requireWrite()
        session.execute(
            "mutation MigrationChapter(\$input: UpdateChapterInput!) { updateChapter(input: \$input) { chapter { id } } }",
            variables(
                "input" to buildJsonObject {
                    put("id", chapter.id)
                    put(
                        "patch",
                        buildJsonObject {
                            patch.isRead?.let { put("isRead", it) }
                            patch.lastPageRead?.let { put("lastPageRead", it) }
                            patch.isBookmarked?.let { put("isBookmarked", it) }
                        },
                    )
                },
            ),
        )
    }
    override suspend fun addCategory(mangaId: Int, categoryId: Int) {
        requireWrite()
        session.setCategoryMembership(mangaId, categoryId, true)
    }
    override suspend fun library(mangaId: Int, member: Boolean) {
        requireWrite()
        session.setLibrary(mangaId, member)
    }
}

internal suspend fun SuwayomiSession.previewMigration(originalId: Int, replacementId: Int): SourceMigrationPreview {
    require(originalId != replacementId)
    flush()
    if (pending.value.isNotEmpty()) throw SuwayomiException(mutationFailure.value ?: SuwayomiFailure.SERVER)
    val original = manga(originalId)
    val replacement = manga(replacementId)
    if (!original.user.inLibrary || original.sourceId == replacement.sourceId) throw SourceMigrationChanged()
    // Fetch only the replacement: an unavailable original provider must not prevent migration.
    refreshManga(replacementId)
    val refreshed = manga(replacementId)
    if (!replacement.sameMigrationIdentity(refreshed)) throw SourceMigrationChanged()
    val oldChapters = chapters(originalId)
    val newChapters = chapters(replacementId)
    if (newChapters.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
    validateMigrationChapters(original, oldChapters)
    validateMigrationChapters(refreshed, newChapters)
    return SourceMigrationPreview(original, refreshed, oldChapters, newChapters)
}

private fun ServerManga.sameMigrationIdentity(other: ServerManga) = id == other.id && sourceId == other.sourceId && url == other.url

private fun validateMigrationChapters(manga: ServerManga, chapters: List<ServerChapter>) {
    if (chapters.map { it.id }.distinct().size != chapters.size || chapters.any { it.mangaId != manga.id || it.manga.sourceId != manga.sourceId || it.manga.url != manga.url }) throw SourceMigrationChanged()
}

private fun ServerChapter.sameMigrationChapter(other: ServerChapter) = id == other.id && identity() == other.identity() &&
    name == other.name && chapterNumber == other.chapterNumber && scanlator == other.scanlator

private fun List<ServerChapter>.sameMigrationChapters(other: List<ServerChapter>): Boolean {
    val indexed = other.associateBy { it.id }
    return size == other.size && all { chapter -> indexed[chapter.id]?.let { chapter.sameMigrationChapter(it) } == true }
}

private fun SourceMigrationPreview.originalUnchanged(manga: ServerManga, chapters: List<ServerChapter>): Boolean {
    val users = chapters.associate { it.id to it.user }
    return original.sameMigrationIdentity(manga) && manga.user.inLibrary &&
        original.categories.nodes.map { it.id }.toSet() == manga.categories.nodes.map { it.id }.toSet() &&
        originalChapters.sameMigrationChapters(chapters) && originalChapters.all {
            val user = users[it.id]
            user != null && user.isRead == it.user.isRead && user.lastPageRead == it.user.lastPageRead && user.isBookmarked == it.user.isBookmarked
        }
}

/** Additive and retryable. The original membership is the last write, after remote verification. */
internal suspend fun migrateSource(
    gateway: SourceMigrationGateway,
    preview: SourceMigrationPreview,
    options: SourceMigrationOptions,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
) {
    require(preview.original.id != preview.replacement.id && preview.original.sourceId != preview.replacement.sourceId)
    require(options.keepOriginal || preview.unmatchedSaved(options) == 0)
    gateway.ready()
    val original = gateway.manga(preview.original.id)
    val originalChapters = gateway.chapters(original.id)
    val replacement = gateway.manga(preview.replacement.id)
    val replacementChapters = gateway.chapters(replacement.id)
    validateMigrationChapters(original, originalChapters)
    validateMigrationChapters(replacement, replacementChapters)
    if (!preview.originalUnchanged(original, originalChapters) || !preview.replacement.sameMigrationIdentity(replacement) ||
        !preview.replacementChapters.sameMigrationChapters(replacementChapters)
    ) {
        throw SourceMigrationChanged()
    }

    val groups = preview.matches.filter { it.replacement != null }.groupBy { it.replacement!!.id }
    val transfers = groups.filterValues { matches ->
        matches.any { (options.progress && (it.original.user.isRead || it.original.user.lastPageRead > 0)) || (options.bookmarks && it.original.user.isBookmarked) }
    }
    val categories = if (options.categories) original.categories.nodes.filter { it.id != 0 } else emptyList()
    val expectedCategories = (replacement.categories.nodes + categories).filter { it.id != 0 }.map { it.id }.toSet()
    val total = transfers.size + categories.size + 1
    var completed = 0
    onProgress(completed, total)
    val expectedProgress = mutableMapOf<Int, MigrationReadingState>()
    for ((id, matches) in transfers) {
        val expected = matches.first().replacement!!
        // Read immediately before merging; avoid one request for every unread chapter in a large title.
        var chapter = gateway.chapter(id)
        if (!expected.sameMigrationChapter(chapter)) throw SourceMigrationChanged()
        val originals = matches.map { it.original.user }
        val read = originals.any { it.isRead }
        val sourcePage = originals.maxOf { it.lastPageRead }
        // Completed chapters carry completion, not a provider-specific final page index.
        // Suwayomi clamps page offsets to pageCount (and older servers throw for -1).
        if (options.progress && !read && !chapter.user.isRead && sourcePage > chapter.user.lastPageRead && chapter.pageCount <= 0) {
            chapter = gateway.fetchChapterPages(id)
            if (!expected.sameMigrationChapter(chapter)) throw SourceMigrationChanged()
            if (chapter.pageCount <= 0) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        }
        val page = if (options.progress && !read && !chapter.user.isRead && sourcePage > chapter.user.lastPageRead) {
            maxOf(chapter.user.lastPageRead, sourcePage.coerceAtMost(chapter.pageCount - 1))
        } else {
            chapter.user.lastPageRead
        }
        if (options.progress) expectedProgress[id] = MigrationReadingState(read || chapter.user.isRead, page)
        val patch = ChapterPatch(
            isRead = true.takeIf { options.progress && read && !chapter.user.isRead },
            lastPageRead = page.takeIf { options.progress && it > chapter.user.lastPageRead },
            isBookmarked = true.takeIf { options.bookmarks && originals.any { it.isBookmarked } && !chapter.user.isBookmarked },
        )
        if (patch != ChapterPatch()) gateway.patch(chapter, patch)
        onProgress(++completed, total)
    }
    for (category in categories) {
        if (replacement.categories.nodes.none { it.id == category.id }) gateway.addCategory(replacement.id, category.id)
        onProgress(++completed, total)
    }
    gateway.library(replacement.id, true)
    onProgress(++completed, total)

    // Verify all selected state, including values that were already present before the transfer.
    val verified = gateway.manga(replacement.id)
    if (!replacement.sameMigrationIdentity(verified) || !verified.user.inLibrary ||
        !verified.categories.nodes.map { it.id }.containsAll(expectedCategories)
    ) {
        throw SuwayomiException(SuwayomiFailure.SERVER)
    }
    val verifiedChapters = gateway.chapters(replacement.id).associateBy { it.id }
    for ((id, matches) in groups) {
        val chapter = verifiedChapters[id] ?: throw SourceMigrationChanged()
        if (!matches.first().replacement!!.sameMigrationChapter(chapter)) throw SourceMigrationChanged()
        val progress = expectedProgress[id]
        val missingProgress = progress != null && !chapter.user.isRead && (progress.isRead || chapter.user.lastPageRead < progress.lastPageRead)
        if (missingProgress ||
            matches.any { options.bookmarks && it.original.user.isBookmarked && !chapter.user.isBookmarked }
        ) {
            throw SuwayomiException(SuwayomiFailure.SERVER)
        }
    }
    if (!options.keepOriginal) {
        gateway.ready()
        if (!preview.originalUnchanged(gateway.manga(original.id), gateway.chapters(original.id))) throw SourceMigrationChanged()
        gateway.library(original.id, false)
    }
}
// KMK <--
