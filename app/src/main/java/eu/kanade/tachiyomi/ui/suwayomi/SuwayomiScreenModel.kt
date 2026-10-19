// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.suwayomi.ChapterIntentKind
import eu.kanade.tachiyomi.data.suwayomi.ChapterPatch
import eu.kanade.tachiyomi.data.suwayomi.LibraryPresentation
import eu.kanade.tachiyomi.data.suwayomi.ServerCategory
import eu.kanade.tachiyomi.data.suwayomi.ServerChapter
import eu.kanade.tachiyomi.data.suwayomi.ServerInfo
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerUser
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiAuthMode
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import eu.kanade.tachiyomi.data.suwayomi.downloadAction
import eu.kanade.tachiyomi.data.suwayomi.identity
import eu.kanade.tachiyomi.data.suwayomi.serverCategories
import eu.kanade.tachiyomi.data.suwayomi.setCategoryMembership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiScreenModel(private val backend: SuwayomiBackend = Injekt.get(), private val loadLibrary: Boolean = true) : StateScreenModel<SuwayomiScreenModel.State>(State()) {
    private val libraryMutex = Mutex()
    private val refreshTask = SuwayomiRefreshTask()

    init {
        screenModelScope.launch(Dispatchers.IO) {
            backend.session.collectLatest { session ->
                refreshTask.cancel()
                mutableState.value = State(session = session, presentation = session?.libraryPresentation() ?: LibraryPresentation())
                if (session != null) refresh().join()
            }
        }
    }

    fun connect(url: String, mode: SuwayomiAuthMode, username: String, password: String) = screenModelScope.launch(Dispatchers.IO) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            backend.connect(url, mode, username, password)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }

    fun reauthenticate(username: String, password: String) = screenModelScope.launch(Dispatchers.IO) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            backend.reauthenticate(username, password)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }

    fun disconnect() = screenModelScope.launch(Dispatchers.IO) { backend.disconnect() }

    fun refresh() = refreshTask.launch(screenModelScope) { reload() }

    fun readingPreferences(upload: Boolean) = screenModelScope.launch(Dispatchers.IO) {
        val session = state.value.session ?: return@launch
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val sync = eu.kanade.tachiyomi.data.suwayomi.SuwayomiReadingPreferences(Injekt.get())
            if (upload) sync.upload(session) else sync.apply(session)
            mutableState.update { it.copy(loading = false) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }

    private suspend fun reload() = libraryMutex.withLock {
        val session = state.value.session ?: return@withLock
        mutableState.update { it.copy(loading = !it.hasLoaded, error = null) }
        try {
            session.flush()
            mutableState.update { it.copy(presentation = session.libraryPresentation()) }
            val user = session.user()
            val info = session.info()
            if (!loadLibrary) {
                mutableState.update { it.copy(user = user, info = info, loading = false, hasLoaded = true) }
                return@withLock
            }
            val categories = session.serverCategories()
            val snapshot = readServerWindow(keyOf = ServerManga::id) { cursor ->
                session.library(cursor).also { backend.requireSession(session.id) }
            }
            mutableState.update {
                if (it.session !== session) it else it.copy(user = user, info = info, categories = categories, mangas = snapshot.items, total = snapshot.total, selected = it.selected.intersect(snapshot.items.map(ServerManga::id).toSet()), loading = false, hasLoaded = true)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (state.value.session?.id == session.id) mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }

    fun presentation(value: LibraryPresentation) {
        try {
            val saved = state.value.session?.updateLibraryPresentation { current -> current.copy(group = value.group, categories = value.categories) } ?: value
            mutableState.update { it.copy(presentation = saved) }
        } catch (e: Exception) {
            mutableState.update { it.copy(error = failure(e)) }
        }
    }

    fun select(id: Int) = mutableState.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectVisible(ids: Set<Int>) = mutableState.update { it.copy(selected = it.selected + ids) }
    fun clearSelection() = mutableState.update { it.copy(selected = emptySet()) }

    /** Each selected manga is checked before applying an explicit operation; failures retain unprocessed selections. */
    fun bulk(action: String, categoryId: Int? = null) = screenModelScope.launch(Dispatchers.IO) {
        libraryMutex.withLock {
            val current = state.value
            val session = current.session ?: return@withLock
            if (current.loading) return@withLock
            if (action in listOf("read", "unread") && Injekt.get<eu.kanade.domain.base.BasePreferences>().incognitoMode().get()) return@withLock
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                for (manga in current.mangas.filter { it.id in current.selected }) {
                    val remote = session.manga(manga.id)
                    if (remote.sourceId != manga.sourceId || remote.url != manga.url) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                    when (action) {
                        "remove" -> session.setLibrary(manga.id, false)
                        "categoryAdd", "categoryRemove" -> session.setCategoryMembership(manga.id, requireNotNull(categoryId), action == "categoryAdd")
                        "read", "unread" -> {
                            val kind = if (action == "read") ChapterIntentKind.MARK_READ else ChapterIntentKind.MARK_UNREAD
                            val patch = if (action == "read") ChapterPatch(isRead = true) else ChapterPatch(isRead = false, lastPageRead = 0)
                            session.chapters(manga.id).forEach { session.enqueue(it.id, kind, patch, identity = it.identity()) }
                            session.flush()
                            mutableState.update { it.copy(presentation = session.libraryPresentation()) }
                        }
                        else -> error("Unsupported library action")
                    }
                    val updated = session.manga(manga.id)
                    mutableState.update { state -> state.copy(selected = state.selected - manga.id, mangas = state.mangas.map { if (it.id == manga.id) updated else it }.filter { it.user.inLibrary }) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(error = failure(e)) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun selectionOperation(action: suspend (SuwayomiSession, ServerManga) -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        libraryMutex.withLock {
            val current = state.value
            val session = current.session ?: return@withLock
            if (current.loading) return@withLock
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                for (manga in current.mangas.filter { it.id in current.selected }) {
                    val remote = session.manga(manga.id)
                    if (remote.sourceId != manga.sourceId || remote.url != manga.url) throw eu.kanade.tachiyomi.data.suwayomi.SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                    action(session, remote)
                    val updated = session.manga(manga.id)
                    backend.requireSession(session.id)
                    mutableState.update { value -> value.copy(selected = value.selected - manga.id, mangas = value.mangas.map { if (it.id == manga.id) updated else it }.filter { it.user.inLibrary }) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (state.value.session?.id == session.id) mutableState.update { it.copy(error = failure(e)) }
            } finally {
                if (state.value.session?.id == session.id) mutableState.update { it.copy(loading = false) }
            }
        }
    }

    data class State(
        val session: SuwayomiSession? = null,
        val user: ServerUser? = null,
        val info: ServerInfo? = null,
        val mangas: List<ServerManga> = emptyList(),
        val cursor: String? = null,
        val hasNext: Boolean = false,
        val total: Int = 0,
        val categories: List<ServerCategory> = emptyList(),
        val presentation: LibraryPresentation = LibraryPresentation(),
        val selected: Set<Int> = emptySet(),
        val loading: Boolean = false,
        val hasLoaded: Boolean = false,
        val error: SuwayomiFailure? = null,
    )
}

class SuwayomiMangaScreenModel(
    private val sessionId: String,
    private val mangaId: Int,
    private val backend: SuwayomiBackend = Injekt.get(),
) : StateScreenModel<SuwayomiMangaScreenModel.State>(State()) {
    private val refreshTask = SuwayomiRefreshTask()
    fun refresh(fetch: Boolean = false, force: Boolean = false) = refreshTask.launch(screenModelScope, fetch to force) {
        actionMutex.withLock {
            mutableState.update { it.copy(loading = it.manga == null || fetch, refreshing = fetch, error = null) }
            try {
                val session = backend.requireSession(sessionId)
                session.flush()
                if (fetch) session.refreshManga(mangaId)
                var manga = session.manga(mangaId)
                if (!fetch && !manga.initialized) {
                    session.refreshManga(mangaId)
                    manga = session.manga(mangaId)
                }
                val chapters = session.chapters(mangaId)
                val user = session.user()
                val categories = session.serverCategories()
                backend.requireSession(sessionId)
                mutableState.value = State(manga = manga, chapters = chapters, loading = false, canDownload = user.roles.contains("ADMIN") || user.permissions.contains("DOWNLOAD_CHAPTERS"), categories = categories)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, refreshing = false, error = failure(e)) }
            }
        }
    }

    private val actionMutex = Mutex()
    fun perform(action: suspend SuwayomiSession.() -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        actionMutex.withLock {
            val expected = state.value.manga ?: return@withLock
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val session = backend.requireSession(sessionId)
                val current = session.manga(mangaId)
                if (current.sourceId != expected.sourceId || current.url != expected.url) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                session.action()
                backend.requireSession(sessionId)
                refresh(force = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    fun setLibrary(inLibrary: Boolean) = screenModelScope.launch(Dispatchers.IO) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            backend.requireSession(sessionId).setLibrary(mangaId, inLibrary)
            refresh()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }

    fun download(action: String, chapterId: Int) = screenModelScope.launch(Dispatchers.IO) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            backend.requireSession(sessionId).downloadAction(action, chapterId)
            refresh()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }

    fun chapterAction(chapter: ServerChapter, kind: ChapterIntentKind) {
        if (Injekt.get<eu.kanade.domain.base.BasePreferences>().incognitoMode().get()) return
        try {
            val patch = when (kind) {
                ChapterIntentKind.MARK_READ -> ChapterPatch(isRead = true)
                ChapterIntentKind.MARK_UNREAD -> ChapterPatch(isRead = false, lastPageRead = 0)
                ChapterIntentKind.BOOKMARK -> ChapterPatch(isBookmarked = !chapter.user.isBookmarked)
                else -> return
            }
            backend.requireSession(sessionId).enqueue(chapter.id, kind, patch, identity = chapter.identity())
            // The pending banner remains until confirmation; refresh only after a deliberate action.
            mutableState.update { current ->
                current.copy(
                    chapters = current.chapters.map {
                        if (it.id == chapter.id) {
                            it.copy(
                                user = it.user.copy(
                                    isRead = patch.isRead ?: it.user.isRead,
                                    lastPageRead = patch.lastPageRead ?: it.user.lastPageRead,
                                    isBookmarked = patch.isBookmarked ?: it.user.isBookmarked,
                                ),
                            )
                        } else {
                            it
                        }
                    },
                )
            }
        } catch (e: Exception) {
            mutableState.update { it.copy(error = failure(e)) }
        }
    }

    data class State(
        val manga: ServerManga? = null,
        val canDownload: Boolean = false,
        val categories: List<ServerCategory> = emptyList(),
        val chapters: List<ServerChapter> = emptyList(),
        val refreshing: Boolean = false,
        val loading: Boolean = true,
        val error: SuwayomiFailure? = null,
    )
}

internal fun failure(e: Exception): SuwayomiFailure = (e as? SuwayomiException)?.failure ?: SuwayomiFailure.CONTRACT
// KMK <--
