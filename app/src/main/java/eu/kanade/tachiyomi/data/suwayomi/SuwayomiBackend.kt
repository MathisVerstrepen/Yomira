// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Single connection authority; late responses from replaced sessions cannot affect the new one. */
class SuwayomiBackend(private val context: Context) {
    private val storage = SuwayomiStorage(context)
    private val sessionLock = Any()
    private val connectionMutex = Mutex()
    private val mutableSession = MutableStateFlow<SuwayomiSession?>(null)
    val session = mutableSession.asStateFlow()

    init {
        File(context.cacheDir, "suwayomi").deleteRecursively()
        storage.read()?.let { stored -> mutableSession.value = createSession(stored) }
    }

    private fun createSession(stored: StoredSession): SuwayomiSession {
        lateinit var client: SuwayomiClient
        client = SuwayomiClient(stored) { updated ->
            synchronized(sessionLock) {
                if (mutableSession.value?.client === client) storage.write(updated)
            }
        }
        return SuwayomiSession(context, stored.id, client) { requireClient(stored.id, client) }
    }

    suspend fun connect(baseUrl: String, mode: SuwayomiAuthMode, username: String, password: String) = connectionMutex.withLock {
        val stored = StoredSession(baseUrl = SuwayomiUrl(baseUrl).base.toString(), mode = mode, username = username, password = password)
        val latest = AtomicReference(stored)
        lateinit var candidate: SuwayomiClient
        candidate = SuwayomiClient(stored) { updated ->
            latest.set(updated)
            synchronized(sessionLock) {
                if (mutableSession.value?.client === candidate) storage.write(updated)
            }
        }
        try {
            candidate.login()
            candidate.negotiateContract()
            val user = candidate.field<ServerUser>("query { user { id username permissions roles } }", "user")
            candidate.attachUser(user.id)
            candidate.field<ServerInfo>("query { aboutServer { name version } }", "aboutServer")
            // Contract probe. A version string alone does not guarantee the per-user API exists.
            candidate.graphql("query { mangas(first: 1, condition: {inLibrary: true}) { nodes { id sourceId user { inLibrary unreadCount } } pageInfo { hasNextPage endCursor } } }")
            synchronized(sessionLock) {
                storage.write(latest.get())
                val old = mutableSession.value
                SuwayomiNotificationWorker.cancel(context)
                mutableSession.value = SuwayomiSession(context, stored.id, candidate) { requireClient(stored.id, candidate) }
                old?.close(clear = true)
                SuwayomiNotificationWorker.schedule(context, stored.id)
            }
        } catch (e: Exception) {
            candidate.close()
            throw e
        }
    }

    suspend fun reauthenticate(username: String, password: String) = connectionMutex.withLock {
        val old = requireSession()
        val saved = storage.read() ?: throw SuwayomiException(SuwayomiFailure.CONNECTION_REQUIRED)
        if (saved.userId == null) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        val latest = AtomicReference(saved.copy(username = username, password = password, accessToken = "", refreshToken = "", cookies = emptyList()))
        lateinit var client: SuwayomiClient
        client = SuwayomiClient(latest.get()) { updated ->
            latest.set(updated)
            synchronized(sessionLock) { if (mutableSession.value?.client === client) storage.write(updated) }
        }
        try {
            client.login()
            client.negotiateContract()
            val user = client.field<ServerUser>("query { user {id username permissions roles} }", "user")
            if (user.id != saved.userId) throw SuwayomiException(SuwayomiFailure.PERMISSION_DENIED)
            client.attachUser(user.id)
            synchronized(sessionLock) {
                storage.write(latest.get())
                old.close(clear = false)
                mutableSession.value = SuwayomiSession(context, saved.id, client) { requireClient(saved.id, client) }
            }
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }

    private fun requireClient(id: String, client: SuwayomiClient) {
        if (requireSession(id).client !== client) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
    }

    suspend fun disconnect() = connectionMutex.withLock {
        synchronized(sessionLock) {
            val old = mutableSession.value
            mutableSession.value = null
            storage.clear()
            old?.close(clear = true)
            SuwayomiNotificationWorker.cancel(context)
        }
    }

    internal fun <T> inSession(id: String, block: (SuwayomiSession) -> T): T = synchronized(sessionLock) {
        block(requireSession(id))
    }

    fun requireSession(id: String? = null): SuwayomiSession {
        val current = mutableSession.value ?: throw SuwayomiException(SuwayomiFailure.CONNECTION_REQUIRED)
        if (id != null && current.id != id) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
        return current
    }
}

class SuwayomiSession internal constructor(
    context: Context,
    val id: String,
    internal val client: SuwayomiClient,
    private val assertActive: () -> Unit,
) {
    val baseUrl: String get() = client.url.base.toString()
    val sharedLibrary: Boolean get() = client.apiContract == SuwayomiApiContract.SHARED_LIBRARY

    @Volatile private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()
    private var scheduledFlush: Job? = null
    private val outboxFile = AtomicFile(File(context.noBackupFilesDir, "suwayomi-outbox-$id"))
    internal val pageCache = File(context.cacheDir, "suwayomi/$id/${java.util.UUID.randomUUID()}")
    private val notificationFile = AtomicFile(File(context.noBackupFilesDir, "suwayomi-notifications-$id"))
    private val libraryFile = AtomicFile(File(context.noBackupFilesDir, "suwayomi-library-$id"))
    private val flagsFile = AtomicFile(File(context.noBackupFilesDir, "suwayomi-reader-$id"))
    private val outbox = ChapterOutbox(if (outboxFile.baseFile.exists()) outboxFile.readFully().decodeToString() else null) {
        if (!closed) writeAtomic(outboxFile, it)
    }
    private val mutablePending = MutableStateFlow(outbox.operations())
    val pending = mutablePending.asStateFlow()
    private val mutableFailure = MutableStateFlow<SuwayomiFailure?>(null)
    val mutationFailure = mutableFailure.asStateFlow()

    @Synchronized
    internal fun notificationCheckpoint(): String? {
        assertActive()
        return if (notificationFile.baseFile.exists()) notificationFile.readFully().decodeToString() else null
    }

    @Synchronized
    internal fun saveNotificationCheckpoint(value: String) {
        assertActive()
        writeAtomic(notificationFile, value)
    }

    @Synchronized
    fun libraryPresentation(): LibraryPresentation {
        assertActive()
        return if (libraryFile.baseFile.exists()) {
            runCatching { kotlinx.serialization.json.Json.decodeFromString<LibraryPresentation>(libraryFile.readFully().decodeToString()) }.getOrDefault(LibraryPresentation())
        } else {
            LibraryPresentation()
        }
    }

    @Synchronized
    fun saveLibraryPresentation(value: LibraryPresentation) {
        assertActive()
        writeAtomic(libraryFile, kotlinx.serialization.json.Json.encodeToString(LibraryPresentation.serializer(), value))
    }

    @Synchronized
    fun readerFlags(mangaId: Int): Long {
        assertActive()
        if (!flagsFile.baseFile.exists()) return 0
        val flags = kotlinx.serialization.json.Json.parseToJsonElement(flagsFile.readFully().decodeToString()).jsonObject
        return flags[mangaId.toString()]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
    }

    @Synchronized
    fun setReaderFlags(mangaId: Int, flags: Long) {
        assertActive()
        val current = if (flagsFile.baseFile.exists()) kotlinx.serialization.json.Json.parseToJsonElement(flagsFile.readFully().decodeToString()).jsonObject else emptyMap()
        writeAtomic(flagsFile, kotlinx.serialization.json.JsonObject(current + (mangaId.toString() to JsonPrimitive(flags))).toString())
    }

    suspend fun user(): ServerUser = checked { client.field("query { user { id username permissions roles } }", "user") }
    suspend fun info(): ServerInfo = checked { client.field("query { aboutServer { name version } }", "aboutServer") }

    suspend fun library(after: String? = null): ServerConnection<ServerManga> = checked {
        client.field(
            "query Library(\$after: Cursor) { mangas(first: 60, after: \$after, condition: {inLibrary: true}, order: [{by: ID, byType: ASC}]) { nodes { $MANGA_FIELDS } pageInfo { hasNextPage endCursor } totalCount } }",
            "mangas",
            variables("after" to (after?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)),
        )
    }

    suspend fun manga(mangaId: Int): ServerManga = checked {
        client.field("query Manga(\$id: Int!) { manga(id: \$id) { $MANGA_FIELDS } }", "manga", variables("id" to JsonPrimitive(mangaId)))
    }

    suspend fun chapters(mangaId: Int): List<ServerChapter> = checked {
        val result = linkedMapOf<Int, ServerChapter>()
        var cursor: String? = null
        do {
            val page = client.field<ServerConnection<ServerChapter>>(
                "query Chapters(\$id: Int!, \$after: Cursor) { chapters(first: 100, after: \$after, condition: {mangaId: \$id}, order: [{by: ID, byType: ASC}]) { nodes { $CHAPTER_FIELDS } pageInfo { hasNextPage endCursor } totalCount } }",
                "chapters",
                variables("id" to JsonPrimitive(mangaId), "after" to (cursor?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)),
            )
            page.nodes.forEach { result[it.id] = it }
            if (!page.pageInfo.hasNextPage) break
            val next = page.pageInfo.endCursor
            if (next == null || next == cursor || page.nodes.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
            cursor = next
        } while (true)
        result.values.sortedWith(compareBy({ it.sourceOrder }, { it.id }))
    }

    suspend fun chapter(chapterId: Int): ServerChapter = checked {
        client.field("query Chapter(\$id: Int!) { chapter(id: \$id) { $CHAPTER_FIELDS } }", "chapter", variables("id" to JsonPrimitive(chapterId)))
    }

    suspend fun pages(chapterId: Int): ServerPages = checked {
        client.field(
            "mutation Pages(\$input: FetchChapterPagesInput!) { fetchChapterPages(input: \$input) { pages chapter { $CHAPTER_FIELDS } syncConflict { deviceName remotePage } } }",
            "fetchChapterPages",
            variables("input" to buildJsonObject { put("chapterId", chapterId) }),
        )
    }

    suspend fun image(path: String): ByteArray = checked { client.image(path) }
    internal suspend fun imageFile(path: String, file: File) = checked { client.imageFile(path, file) }

    suspend fun setLibrary(mangaId: Int, inLibrary: Boolean) = checked {
        client.graphql(
            "mutation Membership(\$input: UpdateMangaInput!) { updateManga(input: \$input) { manga { id user { inLibrary } } } }",
            variables(
                "input" to buildJsonObject {
                    put("id", mangaId)
                    put("patch", buildJsonObject { put("inLibrary", inLibrary) })
                },
            ),
        )
        Unit
    }

    suspend fun refreshManga(mangaId: Int) = checked {
        client.graphql(
            "mutation RefreshManga(\$input: FetchMangaAndChaptersInput!) { fetchMangaAndChapters(input: \$input) { manga { id } } }",
            variables(
                "input" to buildJsonObject {
                    put("id", mangaId)
                    put("fetchManga", true)
                    put("fetchChapters", true)
                },
            ),
        )
        Unit
    }

    fun readingTicket(chapters: List<ServerChapter>): ReadingTicket {
        assertActive()
        return outbox.ticket(chapters.map { it.id }, chapters.associate { it.id to it.identity() })
    }

    fun enqueue(chapterId: Int, kind: ChapterIntentKind, patch: ChapterPatch, ticket: ReadingTicket? = null, identity: ChapterIdentity? = null) {
        assertActive()
        if (incognito()) return
        if (outbox.enqueue(chapterId, kind, patch, ticket, identity)) {
            mutablePending.value = outbox.operations()
            scheduledFlush?.cancel()
            scheduledFlush = scope.launch {
                if (kind == ChapterIntentKind.PROGRESS && patch.isRead != true) delay(1200)
                scope.launch { flush() }
            }
        }
    }

    /** A timeout is reconciled by exact identity and values, never blindly reissued. */
    suspend fun flush() = drainMutex.withLock {
        assertActive()
        if (incognito()) return@withLock
        mutableFailure.value = null
        for (intent in outbox.operations()) {
            if (incognito()) break
            if (outbox.operations().none { it.id == intent.id }) continue
            try {
                val remote = chapter(intent.chapterId)
                if (intent.identity == null || remote.identity() != intent.identity) {
                    outbox.setStatus(intent.id, ChapterIntentStatus.FAILED)
                    mutableFailure.value = SuwayomiFailure.SESSION_CHANGED
                    break
                }
                if (intent.status == ChapterIntentStatus.AMBIGUOUS || intent.status == ChapterIntentStatus.SENDING) {
                    if (matches(remote, intent.patch)) {
                        updateServerTracking(intent)
                        outbox.acknowledge(intent.id)
                        mutablePending.value = outbox.operations()
                        continue
                    }
                    mutableFailure.value = SuwayomiFailure.SERVER
                    break
                }
                if (intent.status == ChapterIntentStatus.FAILED) {
                    mutableFailure.value = SuwayomiFailure.SERVER
                    break
                }
                if (incognito()) break
                outbox.setStatus(intent.id, ChapterIntentStatus.SENDING)
                mutablePending.value = outbox.operations()
                checked {
                    client.graphql(
                        "mutation ChapterState(\$input: UpdateChapterInput!) { updateChapter(input: \$input) { chapter { id user { isRead isBookmarked lastPageRead } } } }",
                        variables(
                            "input" to buildJsonObject {
                                put("id", intent.chapterId)
                                put("clientMutationId", intent.id)
                                put(
                                    "patch",
                                    buildJsonObject {
                                        intent.patch.isRead?.let { put("isRead", it) }
                                        intent.patch.lastPageRead?.let { put("lastPageRead", it) }
                                        intent.patch.isBookmarked?.let { put("isBookmarked", it) }
                                    },
                                )
                            },
                        ),
                    )
                }
                updateServerTracking(intent)
                outbox.acknowledge(intent.id)
            } catch (e: CancellationException) {
                // Persist uncertainty even when the process/activity or HTTP call was cancelled.
                outbox.setStatus(intent.id, ChapterIntentStatus.AMBIGUOUS)
                throw e
            } catch (e: Exception) {
                val failure = (e as? SuwayomiException)?.failure ?: SuwayomiFailure.SERVER
                outbox.setStatus(intent.id, if (failure == SuwayomiFailure.NETWORK || failure == SuwayomiFailure.SERVER) ChapterIntentStatus.AMBIGUOUS else ChapterIntentStatus.FAILED)
                mutableFailure.value = failure
                break
            } finally {
                mutablePending.value = outbox.operations()
            }
        }
    }

    suspend fun retryPending() {
        drainMutex.withLock {
            assertActive()
            outbox.retry()
            mutablePending.value = outbox.operations()
        }
        flush()
    }

    suspend fun discardPending() = drainMutex.withLock {
        assertActive()
        outbox.discard()
        mutablePending.value = outbox.operations()
        mutableFailure.value = null
    }

    fun flushSoon() {
        scope.launch { flush() }
    }

    private suspend fun updateServerTracking(intent: ChapterIntent) {
        if (intent.patch.isRead != true || incognito()) return
        val mangaId = chapter(intent.chapterId).mangaId
        execute(
            "mutation(\$input:TrackProgressInput!) { trackProgress(input:\$input) {clientMutationId} }",
            variables("input" to buildJsonObject { put("mangaId", mangaId) }),
        )
    }

    private fun incognito(): Boolean = uy.kohesive.injekt.Injekt.get<eu.kanade.domain.base.BasePreferences>().incognitoMode().get()

    private fun matches(chapter: ServerChapter, patch: ChapterPatch): Boolean =
        (patch.isRead == null || patch.isRead == chapter.user.isRead) &&
            (patch.lastPageRead == null || patch.lastPageRead == chapter.user.lastPageRead) &&
            (patch.isBookmarked == null || patch.isBookmarked == chapter.user.isBookmarked)

    internal fun downloadEvents() = kotlinx.coroutines.flow.flow {
        user()
        client.subscribe("subscription { downloadChanged { state } }").collect {
            assertActive()
            emit(it)
        }
    }

    internal suspend fun execute(query: String, variables: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())) = checked {
        client.graphql(query, variables)
    }

    private suspend fun <T> checked(block: suspend () -> T): T {
        assertActive()
        client.negotiateContract()
        val value = block()
        assertActive()
        return value
    }

    @Synchronized
    internal fun close(clear: Boolean) {
        closed = true
        scope.coroutineContext[Job]?.cancel()
        client.close()
        pageCache.deleteRecursively()
        if (clear) {
            outboxFile.delete()
            flagsFile.delete()
            libraryFile.delete()
            notificationFile.delete()
        }
    }

    private fun writeAtomic(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try {
            stream.write(text.encodeToByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    companion object {
        internal const val MANGA_FIELDS = "id url sourceId source {name lang iconUrl} chapters {totalCount} title thumbnailUrl thumbnailUrlLastFetched author artist description genre status categories {nodes {id name order}} user { inLibrary inLibraryAt unreadCount downloadCount bookmarkCount lastReadChapter {user {lastReadAt}} } meta { key value }"
        internal const val CHAPTER_FIELDS = "id mangaId url manga {sourceId url} name chapterNumber sourceOrder scanlator uploadDate pageCount user { isRead isBookmarked lastPageRead lastReadAt isDownloaded }"
    }
}
// KMK <--
