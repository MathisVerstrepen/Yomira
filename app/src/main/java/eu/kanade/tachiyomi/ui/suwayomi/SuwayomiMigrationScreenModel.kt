// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerSource
import eu.kanade.tachiyomi.data.suwayomi.SessionMigrationGateway
import eu.kanade.tachiyomi.data.suwayomi.SourceMigrationChanged
import eu.kanade.tachiyomi.data.suwayomi.SourceMigrationOptions
import eu.kanade.tachiyomi.data.suwayomi.SourceMigrationPreview
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.browsableServerSources
import eu.kanade.tachiyomi.data.suwayomi.browseSource
import eu.kanade.tachiyomi.data.suwayomi.migrateSource
import eu.kanade.tachiyomi.data.suwayomi.migrationChapterCount
import eu.kanade.tachiyomi.data.suwayomi.previewMigration
import eu.kanade.tachiyomi.data.suwayomi.requireBrowsableSource
import eu.kanade.tachiyomi.data.suwayomi.searchServerSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal enum class MigrationOperation { LOAD, SEARCH, MORE, PREVIEW, TRANSFER }

internal data class MigrationProviderResults(
    val source: ServerSource,
    val mangas: List<ServerManga> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val error: SuwayomiFailure? = null,
)

internal class SuwayomiMigrationScreenModel(
    private val sessionId: String,
    private val originalId: Int,
    private val backend: SuwayomiBackend = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
) : StateScreenModel<SuwayomiMigrationScreenModel.State>(State()) {
    private var task: Job? = null
    val chapterCounts = MigrationChapterCounts(screenModelScope) { manga -> backend.requireSession(sessionId).migrationChapterCount(manga) }

    fun visibleTitles(ids: Set<Int>) {
        val state = state.value
        val candidates = if (state.preview == null && !state.completed && state.operation !in listOf(MigrationOperation.LOAD, MigrationOperation.PREVIEW, MigrationOperation.TRANSFER)) {
            if (state.allProviders) {
                state.providerResults.flatMap { it.mangas }
            } else if (state.source != null) {
                state.results
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        chapterCounts.visible(candidates.filter { it.id in ids })
    }

    init {
        load()
    }

    fun load() = work(MigrationOperation.LOAD) {
        val session = backend.requireSession(sessionId)
        val original = session.manga(originalId)
        if (!original.user.inLibrary) throw SourceMigrationChanged()
        val sources = session.browsableServerSources().filter { it.id != original.sourceId && it.id != 0L }.sortedWith(compareBy({ it.lang }, { it.name }))
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(original = original, sources = sources, query = original.title) }
    }

    fun query(value: String) = mutableState.update { it.copy(query = value) }

    fun select(source: ServerSource) {
        mutableState.update { it.copy(allProviders = false, providerResults = emptyList(), source = source, results = emptyList(), page = 0, hasNext = false, searched = false, preview = null, candidate = null) }
        search()
    }

    fun search(next: Boolean = false) {
        val state = state.value
        if (state.allProviders) {
            if (!next) searchAll()
            return
        }
        val source = state.source ?: return
        val query = if (next) state.searchedQuery else state.query.trim()
        if (query.isBlank() || (next && !state.hasNext)) return
        val page = if (next) state.page + 1 else 1
        if (!next) {
            chapterCounts.visible(emptyList())
            mutableState.update { it.copy(results = emptyList(), hasNext = false, searched = false, searchedQuery = query) }
        }
        work(if (next) MigrationOperation.MORE else MigrationOperation.SEARCH) {
            val session = backend.requireSession(sessionId)
            session.requireBrowsableSource(source.id)
            val result = session.browseSource(source.id, page, query, "SEARCH", emptyList())
            currentCoroutineContext().ensureActive()
            if (result.mangas.any { it.sourceId != source.id } || (result.hasNextPage && result.mangas.isEmpty())) throw SuwayomiException(SuwayomiFailure.CONTRACT)
            mutableState.update { it.copy(results = ((if (next) it.results else emptyList()) + result.mangas).distinctBy(ServerManga::id), page = page, hasNext = result.hasNextPage, searched = true) }
        }
    }

    fun selectAll() {
        mutableState.update { it.copy(allProviders = true, source = null, preview = null, candidate = null) }
        searchAll()
    }

    private fun searchAll() {
        val query = state.value.query.trim()
        if (query.isBlank()) return
        chapterCounts.visible(emptyList())
        mutableState.update { it.copy(providerResults = emptyList(), searchedQuery = query, searched = false) }
        work(MigrationOperation.SEARCH) {
            val session = backend.requireSession(sessionId)
            // Resolve the enabled provider policy once for the batch, rather than once per request.
            val sources = session.browsableServerSources()
                .filter { it.id != state.value.original?.sourceId && it.id != 0L }
                .sortedWith(compareBy({ it.lang }, { it.name }))
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(sources = sources, providerResults = sources.map(::MigrationProviderResults), searchedQuery = query, searched = false) }
            searchProviders(sources.map(::MigrationProviderResults), query, sources.map { it.id }.toSet())
        }
    }

    /** Retry only unfinished providers; keep successful first pages and their pagination. */
    fun resumeAll() {
        val state = state.value
        val pending = state.providerResults.filter { it.page == 0 || it.error != null }
        if (state.loading || pending.isEmpty()) return
        work(MigrationOperation.SEARCH) { searchProviders(pending, state.searchedQuery) }
    }

    fun providerPage(sourceId: Long) {
        val state = state.value
        val provider = state.providerResults.find { it.source.id == sourceId } ?: return
        if (state.loading || (!provider.hasNext && provider.page > 0 && provider.error == null)) return
        mutableState.update { it.copy(pagingSourceId = sourceId) }
        work(MigrationOperation.MORE) { searchProviders(listOf(provider), state.searchedQuery) }
    }

    private suspend fun searchProviders(providers: List<MigrationProviderResults>, query: String, allowedIds: Set<Long>? = null) {
        val session = backend.requireSession(sessionId)
        val allowed = allowedIds ?: session.browsableServerSources().map { it.id }.toSet()
        val pages = providers.associateBy { it.source.id }
        searchServerSources(
            sources = providers.map { it.source },
            query = query,
            fetch = { source, title ->
                if (source.id !in allowed) throw SuwayomiException(SuwayomiFailure.PERMISSION_DENIED)
                session.browseSource(source.id, pages.getValue(source.id).page + 1, title, "SEARCH", emptyList())
            },
            emit = { source, result ->
                backend.requireSession(sessionId)
                currentCoroutineContext().ensureActive()
                mutableState.update { state ->
                    state.copy(
                        searched = true,
                        providerResults = state.providerResults.map { provider ->
                            if (provider.source.id != source.id) {
                                provider
                            } else {
                                result.fold(
                                    onSuccess = { page -> provider.copy(mangas = (provider.mangas + page.mangas).distinctBy(ServerManga::id), page = provider.page + 1, hasNext = page.hasNextPage, error = null) },
                                    onFailure = { provider.copy(error = failure(it as? Exception ?: Exception(it))) },
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    fun preview(replacement: ServerManga) {
        val options = state.value.options.takeIf { state.value.preview?.replacement?.id == replacement.id } ?: SourceMigrationOptions()
        mutableState.update { it.copy(candidate = replacement) }
        work(MigrationOperation.PREVIEW) {
            val session = backend.requireSession(sessionId)
            session.requireBrowsableSource(replacement.sourceId)
            val preview = session.previewMigration(originalId, replacement.id)
            currentCoroutineContext().ensureActive()
            if (preview.replacement.sourceId != replacement.sourceId || preview.replacement.url != replacement.url) throw SourceMigrationChanged()
            withContext(Dispatchers.Main.immediate) { chapterCounts.record(preview.replacement, preview.replacementChapters.size) }
            mutableState.update { it.copy(preview = preview, options = options, changed = false) }
        }
    }

    fun options(value: SourceMigrationOptions) {
        if (!state.value.loading) mutableState.update { it.copy(options = value) }
    }

    fun migrate() {
        val state = state.value
        val preview = state.preview ?: return
        if (state.loading || state.changed || (!state.options.keepOriginal && preview.unmatchedSaved(state.options) > 0)) return
        work(MigrationOperation.TRANSFER) {
            val session = backend.requireSession(sessionId)
            session.migrationMutex.withLock {
                session.requireBrowsableSource(preview.replacement.sourceId)
                migrateSource(SessionMigrationGateway(session) { !basePreferences.incognitoMode().get() }, preview, state.options) { done, total ->
                    mutableState.update { it.copy(progress = done to total) }
                }
            }
            mutableState.update { it.copy(completed = true) }
        }
    }

    /** Reading retries reload the failed request; a transfer retry always obtains a fresh preview. */
    fun retry() {
        val state = state.value
        when {
            state.allProviders && state.failedOperation == MigrationOperation.MORE && state.pagingSourceId != null -> providerPage(state.pagingSourceId)
            state.failedOperation == MigrationOperation.MORE -> search(next = true)
            state.candidate != null && state.failedOperation in listOf(MigrationOperation.PREVIEW, MigrationOperation.TRANSFER) -> preview(state.candidate)
            state.allProviders && state.providerResults.isEmpty() -> searchAll()
            state.allProviders -> resumeAll()
            state.source != null -> search()
            else -> load()
        }
    }

    fun cancel() {
        task?.cancel()
        mutableState.update { it.copy(loading = false, migrating = false, operation = null, stopped = it.migrating, failedOperation = it.operation) }
    }

    fun back(): Boolean {
        chapterCounts.visible(emptyList())
        if (state.value.loading) {
            val stay = state.value.operation in listOf(MigrationOperation.PREVIEW, MigrationOperation.TRANSFER)
            cancel()
            if (stay) return true
        }
        if (state.value.preview != null && !state.value.completed) {
            mutableState.update { it.copy(preview = null, candidate = null, error = null, changed = false, stopped = false, failedOperation = null) }
            return true
        }
        if ((state.value.source != null || state.value.allProviders) && !state.value.completed) {
            mutableState.update { it.copy(allProviders = false, source = null, candidate = null, error = null, changed = false, stopped = false, failedOperation = null) }
            return true
        }
        return false
    }

    private fun work(operation: MigrationOperation, action: suspend () -> Unit) {
        if (operation in listOf(MigrationOperation.LOAD, MigrationOperation.PREVIEW, MigrationOperation.TRANSFER)) chapterCounts.visible(emptyList())
        task?.cancel()
        mutableState.update { it.copy(loading = true, migrating = operation == MigrationOperation.TRANSFER, operation = operation, failedOperation = null, error = null, changed = false, stopped = false, progress = null) }
        task = screenModelScope.launch(Dispatchers.IO) {
            try {
                action()
                currentCoroutineContext().ensureActive()
                backend.requireSession(sessionId)
                mutableState.update { it.copy(loading = false, migrating = false, operation = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SourceMigrationChanged) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(loading = false, migrating = false, operation = null, failedOperation = operation, changed = true) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(loading = false, migrating = false, operation = null, failedOperation = operation, error = failure(e)) }
            }
        }
    }

    data class State(
        val original: ServerManga? = null,
        val sources: List<ServerSource> = emptyList(),
        val source: ServerSource? = null,
        val allProviders: Boolean = false,
        val providerResults: List<MigrationProviderResults> = emptyList(),
        val pagingSourceId: Long? = null,
        val query: String = "",
        val searchedQuery: String = "",
        val results: List<ServerManga> = emptyList(),
        val page: Int = 0,
        val hasNext: Boolean = false,
        val searched: Boolean = false,
        val preview: SourceMigrationPreview? = null,
        val candidate: ServerManga? = null,
        val options: SourceMigrationOptions = SourceMigrationOptions(),
        val loading: Boolean = true,
        val migrating: Boolean = false,
        val error: SuwayomiFailure? = null,
        val changed: Boolean = false,
        val stopped: Boolean = false,
        val completed: Boolean = false,
        val progress: Pair<Int, Int>? = null,
        val operation: MigrationOperation? = MigrationOperation.LOAD,
        val failedOperation: MigrationOperation? = null,
    )
}
// KMK <--
