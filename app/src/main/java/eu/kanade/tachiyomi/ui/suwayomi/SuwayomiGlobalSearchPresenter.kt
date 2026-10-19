// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.GlobalSearchContent
import eu.kanade.presentation.browse.components.GlobalSearchToolbar
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.tachiyomi.data.suwayomi.ServerSource
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.browsableServerSources
import eu.kanade.tachiyomi.data.suwayomi.browseSource
import eu.kanade.tachiyomi.data.suwayomi.searchServerSources
import eu.kanade.tachiyomi.data.suwayomi.toKomikkuManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class SuwayomiGlobalSearchModel(private val sessionId: String, initialQuery: String) : StateScreenModel<SuwayomiGlobalSearchModel.State>(State(query = initialQuery)) {
    private val backend = Injekt.get<SuwayomiBackend>()
    private val prefs = Injekt.get<SourcePreferences>()
    private var searchJob: Job? = null
    private val revision = java.util.concurrent.atomic.AtomicInteger()
    init {
        loadSources()
    }
    fun loadSources() = search(state.value.query)
    fun query(value: String?) {
        mutableState.update { it.copy(query = value.orEmpty()) }
    }
    fun search(value: String) {
        searchJob?.cancel()
        val ticket = revision.incrementAndGet()
        val query = value.trim()
        mutableState.update { it.copy(query = value, submittedQuery = query, sources = emptyList(), loadingSources = true, results = emptyMap(), selected = emptyList(), selectionMode = false, confirmation = null, error = null, identities = emptyMap()) }
        searchJob = screenModelScope.launch(Dispatchers.IO) {
            try {
                val sources = backend.requireSession(sessionId).browsableServerSources()
                ensureActive()
                backend.requireSession(sessionId)
                mutableState.update { if (ticket != revision.get()) it else it.copy(sources = sources, loadingSources = false, results = if (query.isEmpty()) emptyMap() else sources.associateBy({ source -> source.id }, { SearchItemResult.Loading })) }
                if (query.isEmpty() || ticket != revision.get()) return@launch
                searchServerSources(sources, query, fetch = { source, text ->
                    backend.requireSession(sessionId).browseSource(source.id, 1, text, "SEARCH", emptyList()).also { backend.requireSession(sessionId) }
                }, emit = { source, result ->
                    ensureActive()
                    if (ticket == revision.get() && backend.session.value?.id == sessionId) {
                        val item = result.fold(
                            onSuccess = { SearchItemResult.Success(it.mangas.map { manga -> manga.toKomikkuManga(sessionId) }) },
                            onFailure = { SearchItemResult.Error(it) },
                        )
                        mutableState.update {
                            if (ticket != revision.get() || backend.session.value?.id != sessionId) it else it.copy(results = it.results + (source.id to item), identities = it.identities + result.getOrNull()?.mangas.orEmpty().associate { manga -> manga.id to (manga.sourceId to manga.url) })
                        }
                    }
                })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { if (ticket != revision.get()) it else it.copy(loadingSources = false, error = failure(e)) }
            }
        }
    }
    fun toggleSelection() {
        mutableState.update { it.copy(selectionMode = !it.selectionMode, selected = emptyList()) }
    }
    fun select(manga: Manga) {
        mutableState.update { value -> value.copy(selected = if (value.selected.any { it.id == manga.id }) value.selected.filterNot { it.id == manga.id } else value.selected + manga) }
    }
    fun selectAll(invert: Boolean) {
        mutableState.update { it.copy(selected = it.results.values.filterIsInstance<SearchItemResult.Success>().flatMap { result -> result.result }.distinctBy(Manga::id).filter { manga -> !invert || it.selected.none { selected -> selected.id == manga.id } }) }
    }
    fun confirm(manga: Manga? = null) {
        mutableState.update { it.copy(selected = manga?.let(::listOf) ?: it.selected, confirmation = manga?.let { value -> !value.favorite } ?: true) }
    }
    fun dismiss() {
        mutableState.update { it.copy(confirmation = null) }
    }
    fun toggleResults() {
        prefs.globalSearchFilterState().set(!prefs.globalSearchFilterState().get())
    }
    fun favorite() = screenModelScope.launch(Dispatchers.IO) {
        val chosen = state.value
        val favorite = chosen.confirmation ?: return@launch
        if (chosen.working || !mutableState.compareAndSet(chosen, chosen.copy(working = true, confirmation = null, error = null))) return@launch
        val expected = chosen.selected
        val identities = chosen.identities
        try {
            for (manga in expected) {
                val session = backend.requireSession(sessionId)
                val id = SuwayomiMode.remoteId(manga.id)
                val before = session.manga(id)
                if ((before.sourceId to before.url) != identities[id]) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                session.setLibrary(id, favorite)
                val updated = session.manga(id).toKomikkuManga(sessionId)
                mutableState.update { state -> state.copy(results = state.results.mapValues { (_, result) -> if (result is SearchItemResult.Success) SearchItemResult.Success(result.result.map { if (it.id == updated.id) updated else it }) else result }, selected = state.selected.filterNot { it.id == manga.id }) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(error = failure(e)) }
        } finally {
            mutableState.update { it.copy(working = false) }
        }
    }
    data class State(
        val query: String = "",
        val submittedQuery: String = "",
        val sources: List<ServerSource> = emptyList(),
        val results: Map<Long, SearchItemResult> = emptyMap(),
        val identities: Map<Int, Pair<Long, String>> = emptyMap(),
        val loadingSources: Boolean = true,
        val error: SuwayomiFailure? = null,
        val selectionMode: Boolean = false,
        val selected: List<Manga> = emptyList(),
        val confirmation: Boolean? = null,
        val working: Boolean = false,
    )
}

@Composable
internal fun SuwayomiGlobalSearchPresenter(initialQuery: String) {
    val backend = remember { Injekt.get<SuwayomiBackend>() }
    val current by backend.session.collectAsState()
    val expected by rememberSaveable { mutableStateOf(current?.id) }
    val session = current?.takeIf { it.id == expected }
    val navigator = LocalNavigator.currentOrThrow
    if (session == null) {
        ConnectionError(SuwayomiFailure.SESSION_CHANGED, { navigator.pop() })
        return
    }
    val model = navigator.lastItem.rememberScreenModel { SuwayomiGlobalSearchModel(session.id, initialQuery) }
    val state by model.state.collectAsState()
    val prefs = remember { Injekt.get<SourcePreferences>() }
    val onlyResults by prefs.globalSearchFilterState().changes().collectAsState(prefs.globalSearchFilterState().get())
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(context.stringResource(failureResource(it))) } }
    BackHandler(state.selectionMode) { model.toggleSelection() }
    val uiState = SearchScreenModel.State(
        searchQuery = state.query,
        sourceFilter = SourceFilter.All,
        onlyShowHasResults = onlyResults,
        items = state.sources.mapNotNull { source ->
            state.results[source.id]?.let { result ->
                val display = if (result is SearchItemResult.Error) SearchItemResult.Error(Exception(context.stringResource(failureResource(failure(result.throwable as? Exception ?: Exception()))))) else result
                StubSource(source.id, source.lang, source.name) as eu.kanade.tachiyomi.source.Source to display
            }
        }.toMap().toPersistentMap(),
    )
    Scaffold(
        topBar = { behavior ->
            if (state.selectionMode) {
                BulkSelectionToolbar(selectedCount = state.selected.size, isRunning = state.working, onClickClearSelection = model::toggleSelection, onChangeCategoryClick = { model.confirm() }, onSelectAll = { model.selectAll(false) }, onReverseSelection = { model.selectAll(true) })
            } else {
                GlobalSearchToolbar(searchQuery = state.query, progress = uiState.progress, total = uiState.total, navigateUp = { navigator.pop() }, onChangeSearchQuery = model::query, onSearch = model::search, hideSourceFilter = false, sourceFilter = SourceFilter.All, onChangeSearchFilter = {}, onlyShowHasResults = onlyResults, onToggleResults = model::toggleResults, scrollBehavior = behavior, toggleSelectionMode = model::toggleSelection, isRunning = state.working, hasPinnedSources = false)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loadingSources) {
            LoadingScreen()
        } else if (state.sources.isEmpty() && state.error != null) {
            ConnectionError(state.error!!, { model.loadSources() })
        } else {
            GlobalSearchContent(
                items = uiState.filteredItems,
                contentPadding = padding,
                getManga = { rememberUpdatedState(it) },
                onClickSource = { source -> state.sources.firstOrNull { it.id == source.id }?.let { navigator.push(SuwayomiSourceScreen(session.id, it, initialQuery = state.submittedQuery)) } },
                onClickItem = { if (state.selectionMode) model.select(it) else navigator.push(serverMangaScreen(session.id, SuwayomiMode.remoteId(it.id))) },
                onLongClickItem = { if (state.selectionMode) navigator.push(serverMangaScreen(session.id, SuwayomiMode.remoteId(it.id))) else model.confirm(it) },
                selection = state.selected,
                serverBacked = true,
            )
        }
    }
    state.confirmation?.let { favorite ->
        AlertDialog(onDismissRequest = model::dismiss, title = { Text(stringResource(if (favorite) KMR.strings.suwayomi_add_library else KMR.strings.suwayomi_remove_library)) }, text = { Text(stringResource(KMR.strings.suwayomi_bulk_notice, state.selected.size)) }, confirmButton = { TextButton(onClick = { model.favorite() }) { Text(stringResource(MR.strings.action_ok)) } }, dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(MR.strings.action_cancel)) } })
    }
}
// KMK <--
