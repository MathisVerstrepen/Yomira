// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.suwayomi.ChapterIntentKind
import eu.kanade.tachiyomi.data.suwayomi.ChapterPatch
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

class SuwayomiScreenModel(private val backend: SuwayomiBackend = Injekt.get()) : StateScreenModel<SuwayomiScreenModel.State>(State()) {
    private val libraryMutex = Mutex()

    init {
        screenModelScope.launch(Dispatchers.IO) {
            backend.session.collectLatest { session ->
                mutableState.value = State(session = session)
                if (session != null) reload()
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

    fun refresh() = screenModelScope.launch(Dispatchers.IO) { reload() }

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
        mutableState.update { it.copy(loading = true, error = null, mangas = emptyList(), hasNext = false, cursor = null) }
        try {
            session.flush()
            val user = session.user()
            val info = session.info()
            val page = session.library()
            backend.requireSession(session.id)
            mutableState.update { it.copy(loading = false, user = user, info = info, mangas = page.nodes, cursor = page.pageInfo.endCursor, hasNext = page.pageInfo.hasNextPage) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (state.value.session?.id == session.id) mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }

    fun nextPage() = screenModelScope.launch(Dispatchers.IO) {
        libraryMutex.withLock {
            val current = state.value
            val session = current.session ?: return@withLock
            if (!current.hasNext || current.loading) return@withLock
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val page = session.library(current.cursor)
                if (page.pageInfo.hasNextPage && (page.pageInfo.endCursor == current.cursor || page.nodes.isEmpty())) throw SuwayomiException(SuwayomiFailure.CONTRACT)
                backend.requireSession(session.id)
                mutableState.update { it.copy(loading = false, mangas = (it.mangas + page.nodes).distinctBy(ServerManga::id), cursor = page.pageInfo.endCursor, hasNext = page.pageInfo.hasNextPage) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (state.value.session?.id == session.id) mutableState.update { it.copy(loading = false, error = failure(e)) }
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
        val loading: Boolean = false,
        val error: SuwayomiFailure? = null,
    )
}

class SuwayomiMangaScreenModel(
    private val sessionId: String,
    private val mangaId: Int,
    private val backend: SuwayomiBackend = Injekt.get(),
) : StateScreenModel<SuwayomiMangaScreenModel.State>(State()) {
    private var job: Job? = null
    fun refresh(fetch: Boolean = false) {
        job?.cancel()
        job = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val session = backend.requireSession(sessionId)
                session.flush()
                if (fetch) session.refreshManga(mangaId)
                val manga = session.manga(mangaId)
                val chapters = session.chapters(mangaId)
                val user = session.user()
                mutableState.value = State(manga = manga, chapters = chapters, loading = false, canDownload = user.roles.contains("ADMIN") || user.permissions.contains("DOWNLOAD_CHAPTERS"))
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
            backend.requireSession(sessionId).enqueue(chapter.id, kind, patch)
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
        val chapters: List<ServerChapter> = emptyList(),
        val loading: Boolean = true,
        val error: SuwayomiFailure? = null,
    )
}

internal fun failure(e: Exception): SuwayomiFailure = (e as? SuwayomiException)?.failure ?: SuwayomiFailure.CONTRACT
// KMK <--
