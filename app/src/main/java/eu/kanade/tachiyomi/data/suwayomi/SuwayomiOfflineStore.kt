// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable
internal data class OfflineTitle(val manga: ServerManga, val chapters: List<ServerChapter>)

@Serializable
internal data class OfflineDownload(
    val chapterId: Int,
    val mangaId: Int,
    val generation: String = java.util.UUID.randomUUID().toString(),
    val state: OfflineState = OfflineState.QUEUED,
    val pages: Int = 0,
    val total: Int = 0,
    val sizes: List<Long> = emptyList(),
)

@Serializable
internal enum class OfflineState { QUEUED, DOWNLOADING, WIFI, STORAGE, NETWORK, ERROR, COMPLETE }

@Serializable
internal data class OfflineSnapshot(
    val titles: Map<Int, OfflineTitle> = emptyMap(),
    val downloads: List<OfflineDownload> = emptyList(),
    val wifiOnly: Boolean = true,
    val paused: Boolean = false,
    val limitMiB: Int = 2048,
)

internal class OfflineStorageFull : Exception()
internal class OfflineWifiRequired : Exception()

/** Durable, account-scoped files. Only a committed manifest makes a chapter available offline. */
internal class SuwayomiOfflineStore(private val root: File) {
    private val manifest = File(root, "index.json")
    private val mutableState = MutableStateFlow(
        runCatching { if (manifest.isFile) Json.decodeFromString<OfflineSnapshot>(manifest.readText()) else OfflineSnapshot() }.getOrDefault(OfflineSnapshot()),
    )
    val state = mutableState.asStateFlow()
    init {
        val validated = state.value.downloads.map { item ->
            if (item.state == OfflineState.COMPLETE && item.sizes.indices.any { index -> File(root, "${item.chapterId}/$index").length() != item.sizes[index] }) item.copy(state = OfflineState.ERROR) else item
        }
        if (validated != state.value.downloads) mutableState.value = state.value.copy(downloads = validated)
        // Removed transfers/read leases may have outlived the previous process.
        if (manifest.isFile && state.value.titles.isNotEmpty()) {
            root.listFiles()?.forEach { file ->
                val chapterId = file.name.removeSuffix(".part").toIntOrNull()
                if (chapterId != null && state.value.downloads.none { it.chapterId == chapterId }) file.deleteRecursively()
            }
        }
    }
    private var closed = false
    private val readers = mutableMapOf<Int, Int>()
    private val retired = mutableSetOf<Int>()

    @Synchronized
    private fun save(value: OfflineSnapshot) {
        check(!closed)
        root.mkdirs()
        val temp = File(root, "index.tmp")
        FileOutputStream(temp).use {
            it.write(Json.encodeToString(value).encodeToByteArray())
            it.fd.sync()
        }
        check(temp.renameTo(manifest))
        mutableState.value = value
    }

    @Synchronized
    fun configure(wifiOnly: Boolean, limitMiB: Int) {
        require(limitMiB in 128..1_048_576)
        save(state.value.copy(wifiOnly = wifiOnly, limitMiB = limitMiB))
    }

    @Synchronized
    fun enqueue(manga: ServerManga, chapters: List<ServerChapter>, ids: List<Int>) {
        require(ids.all { id -> chapters.any { it.id == id && it.mangaId == manga.id } })
        require(ids.none { it in retired }) { "Chapter is still open in the reader" }
        val before = state.value
        val entries = before.downloads.toMutableList()
        ids.distinct().forEach { id ->
            val previous = entries.firstOrNull { it.chapterId == id }
            if (previous == null || previous.state != OfflineState.COMPLETE) {
                entries.removeAll { it.chapterId == id }
                entries += OfflineDownload(id, manga.id)
            }
        }
        save(before.copy(titles = before.titles + (manga.id to OfflineTitle(manga, chapters)), downloads = entries))
    }

    @Synchronized
    fun remember(manga: ServerManga, chapters: List<ServerChapter>) {
        val old = state.value.titles[manga.id] ?: return
        // IDs alone cannot identify a title after a restore/source migration.
        val valid = old.manga.sourceId == manga.sourceId && old.manga.url == manga.url
        val downloads = state.value.downloads.map { item ->
            if (item.mangaId == manga.id && (!valid || chapters.none { it.id == item.chapterId && old.chapters.any { previous -> previous.id == it.id && previous.identity() == it.identity() } })) item.copy(state = OfflineState.ERROR) else item
        }
        save(state.value.copy(titles = state.value.titles + (manga.id to OfflineTitle(manga, chapters)), downloads = downloads))
    }

    @Synchronized
    fun update(chapterId: Int, status: OfflineState, pages: Int = 0, total: Int = 0, sizes: List<Long> = emptyList()) {
        save(
            state.value.copy(
                downloads = state.value.downloads.map {
                    if (it.chapterId == chapterId) it.copy(state = status, pages = pages, total = total, sizes = sizes) else it
                },
            ),
        )
    }

    @Synchronized
    fun patch(chapterId: Int, patch: ChapterPatch) {
        val title = state.value.titles.values.firstOrNull { it.chapters.any { chapter -> chapter.id == chapterId } } ?: return
        val chapters = title.chapters.map { chapter ->
            if (chapter.id != chapterId) chapter else chapter.copy(user = chapter.user.copy(isRead = patch.isRead ?: chapter.user.isRead, lastPageRead = patch.lastPageRead ?: chapter.user.lastPageRead, isBookmarked = patch.isBookmarked ?: chapter.user.isBookmarked))
        }
        val manga = title.manga.copy(user = title.manga.user.copy(unreadCount = chapters.count { !it.user.isRead }, bookmarkCount = chapters.count { it.user.isBookmarked }))
        save(state.value.copy(titles = state.value.titles + (title.manga.id to title.copy(manga = manga, chapters = chapters))))
    }

    @Synchronized
    fun next(): OfflineDownload? = if (state.value.paused) null else state.value.downloads.firstOrNull { it.state != OfflineState.COMPLETE && it.state != OfflineState.ERROR }

    @Synchronized
    fun pause(value: Boolean) = save(state.value.copy(paused = value))

    @Synchronized
    fun retry() = save(state.value.copy(downloads = state.value.downloads.map { if (it.state == OfflineState.ERROR) it.copy(state = OfflineState.QUEUED) else it }))

    @Synchronized
    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile && it.name != "index.tmp" }.sumOf { it.length() }

    @Synchronized
    fun begin(chapterId: Int, identity: ChapterIdentity, paths: List<String>): Pair<File, List<Long>> {
        check(!closed && state.value.downloads.any { it.chapterId == chapterId })
        val directory = File(root, "$chapterId.part")
        val committed = File(root, chapterId.toString())
        if (!directory.exists() && committed.isDirectory) check(committed.renameTo(directory))
        val checkpoint = runCatching { Json.decodeFromString<OfflineTransferCheckpoint>(File(directory, "pages.json").readText()) }.getOrNull()
        val sizes = if (checkpoint?.identity == identity && checkpoint.paths == paths && checkpoint.sizes.indices.all { File(directory, "$it").length() == checkpoint.sizes[it] && checkpoint.sizes[it] > 0 }) checkpoint.sizes else emptyList()
        if (sizes.isEmpty()) directory.deleteRecursively()
        directory.mkdirs()
        // A partially written image was never checkpointed and is fetched again.
        directory.listFiles()?.filter { it.name.toIntOrNull()?.let { index -> index >= sizes.size } == true }?.forEach { it.delete() }
        return directory to sizes
    }

    @Synchronized
    fun checkpoint(directory: File, identity: ChapterIdentity, paths: List<String>, sizes: List<Long>) {
        check(!closed)
        val temporary = File(directory, "pages.tmp")
        FileOutputStream(temporary).use {
            it.write(Json.encodeToString(OfflineTransferCheckpoint(identity, paths, sizes)).encodeToByteArray())
            it.fd.sync()
        }
        check(temporary.renameTo(File(directory, "pages.json")))
    }

    @Synchronized
    fun commit(chapterId: Int, staging: File, sizes: List<Long>) {
        check(!closed && state.value.downloads.any { it.chapterId == chapterId })
        require(sizes.isNotEmpty() && sizes.all { it > 0 })
        require(sizes.indices.all { File(staging, "$it").length() == sizes[it] })
        val destination = File(root, chapterId.toString())
        destination.deleteRecursively()
        check(staging.renameTo(destination))
        update(chapterId, OfflineState.COMPLETE, sizes.size, sizes.size, sizes)
    }

    @Synchronized
    fun files(chapterId: Int): List<File>? {
        if (closed) return null
        val download = state.value.downloads.firstOrNull { it.chapterId == chapterId && it.state == OfflineState.COMPLETE } ?: return null
        val files = download.sizes.indices.map { File(root, "$chapterId/$it") }
        return files.takeIf { it.isNotEmpty() && it.indices.all { i -> files[i].isFile && files[i].length() == download.sizes[i] } }
    }

    @Synchronized
    fun acquire(chapterId: Int): List<File>? = files(chapterId)?.also { readers[chapterId] = (readers[chapterId] ?: 0) + 1 }

    @Synchronized
    fun release(chapterId: Int) {
        val count = (readers[chapterId] ?: 1) - 1
        if (count > 0) {
            readers[chapterId] = count
        } else {
            readers.remove(chapterId)
            if (retired.remove(chapterId)) File(root, chapterId.toString()).deleteRecursively()
        }
    }

    @Synchronized
    fun remove(chapterId: Int) {
        check(!closed)
        if ((readers[chapterId] ?: 0) > 0) retired += chapterId else File(root, chapterId.toString()).deleteRecursively()
        // The worker drops this staging directory after observing cancellation.
        val downloads = state.value.downloads.filterNot { it.chapterId == chapterId }
        state.value.titles.keys.filter { id -> downloads.none { it.mangaId == id } }.forEach { File(root, "cover-$it").delete() }
        save(state.value.copy(downloads = downloads, titles = state.value.titles.filterKeys { id -> downloads.any { it.mangaId == id } }))
    }

    @Synchronized
    fun discardPartial(chapterId: Int) {
        File(root, "$chapterId.part").deleteRecursively()
    }

    @Synchronized
    fun cover(mangaId: Int): File? = File(root, "cover-$mangaId").takeIf { !closed && it.isFile }

    @Synchronized
    fun coverDestination(mangaId: Int): File {
        check(!closed)
        return File(root, "cover-$mangaId.part")
    }

    @Synchronized
    fun commitCover(mangaId: Int, file: File) {
        check(!closed)
        check(file.renameTo(File(root, "cover-$mangaId")))
    }

    @Synchronized
    fun close(clear: Boolean) {
        closed = true
        if (clear) root.deleteRecursively()
    }
}
// KMK <--

// KMK -->
@Serializable
internal data class OfflineTransferCheckpoint(val identity: ChapterIdentity, val paths: List<String>, val sizes: List<Long>)
// KMK <--
