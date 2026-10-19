// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerFilter
import eu.kanade.tachiyomi.data.suwayomi.ServerSource
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.browseSource
import eu.kanade.tachiyomi.data.suwayomi.requireBrowsableSource
import eu.kanade.tachiyomi.data.suwayomi.sameFilterSchema
import eu.kanade.tachiyomi.data.suwayomi.snapshot
import eu.kanade.tachiyomi.data.suwayomi.sourceFilters
import eu.kanade.tachiyomi.data.suwayomi.toKomikkuFilters
import eu.kanade.tachiyomi.data.suwayomi.toKomikkuManga
import eu.kanade.tachiyomi.data.suwayomi.toKomikkuSource
import eu.kanade.tachiyomi.data.suwayomi.toServerChanges
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun SuwayomiBrowseTab() {
    val backend = remember { Injekt.get<SuwayomiBackend>() }
    val current by backend.session.collectAsState()
    val session = current ?: return
    val model = eu.kanade.tachiyomi.ui.browse.BrowseTab.rememberScreenModel(tag = "server-sources-${session.id}") { SuwayomiBrowseScreenModel(session.id) }
    val state by model.state.collectAsState()
    val extensionsModel = eu.kanade.tachiyomi.ui.browse.BrowseTab.rememberScreenModel(tag = "server-extensions-${session.id}") { SuwayomiExtensionsModel(session.id) }
    val extensionsState by extensionsModel.state.collectAsState()
    var extensionQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pager = androidx.compose.foundation.pager.rememberPagerState { 2 }
    BackHandler(enabled = pager.currentPage == 1 && extensionQuery != null) { extensionQuery = null }
    LaunchedEffect(extensionsState.extensions) {
        if (extensionsState.hasLoaded) model.sources()
    }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        if (state.hasLoadedSources) {
            state.error?.let { error ->
                if (snackbar.showSnackbar(context.stringResource(failureResource(error)), context.stringResource(MR.strings.action_retry)) == SnackbarResult.ActionPerformed) model.sources()
            }
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        model.sources()
        extensionsModel.refresh()
    }
    val sources = state.sources.filter { query.isNullOrBlank() || it.name.contains(query.orEmpty(), true) }
    val nativeState = SourcesScreenModel.State(
        isLoading = state.loading && !state.hasLoadedSources,
        items = sources.sortedBy { it.lang }.groupBy { it.lang }.flatMap { (lang, entries) ->
            listOf(SourceUiModel.Header(lang, false)) + entries.sortedBy { it.name }.map { SourceUiModel.Item(it.toKomikkuSource(session.id)) }
        }.toImmutableList(),
        showLatest = true,
        showPin = false,
        searchQuery = query,
    )
    TabbedScreen(
        titleRes = MR.strings.browse,
        state = pager,
        searchQuery = extensionQuery,
        onChangeSearchQuery = { extensionQuery = it },
        tabs = persistentListOf(
            TabContent(
                titleRes = MR.strings.label_sources,
                actions = persistentListOf(
                    AppBar.Action(stringResource(MR.strings.action_global_search), Icons.Outlined.TravelExplore, onClick = { navigator.push(eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen()) }),
                    AppBar.Action(stringResource(KMR.strings.suwayomi_reload), Icons.Outlined.Refresh, onClick = { model.sources() }),
                ),
            ) { padding, _ ->
                androidx.compose.foundation.layout.Box {
                    Column {
                        if (!state.hasLoadedSources) state.error?.let { ConnectionError(it, { model.sources() }) }
                        SourcesScreen(
                            state = nativeState,
                            contentPadding = padding,
                            onClickItem = { item, listing -> state.sources.firstOrNull { it.id == item.id }?.let { navigator.push(SuwayomiSourceScreen(session.id, it, listing == Listing.Latest)) } },
                            onClickPin = {},
                            onLongClickItem = { navigator.push(SuwayomiAdministrationScreen(session.id, it.id)) },
                            onChangeSearchQuery = { query = it },
                        )
                    }
                    SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                }
            },
            suwayomiExtensionsTab(session.id, extensionsModel, extensionQuery),
        ),
    )
}

private data class ServerBrowseRequest(val query: String, val type: String, val changes: List<JsonObject>, val schema: List<ServerFilter>, val revision: Int)

/** Android paging delegates every source request to Suwayomi and retains only view projections. */
private class SuwayomiSourceModel(private val sessionId: String, val source: ServerSource, latest: Boolean, initialQuery: String?) : ScreenModel {
    private val backend = Injekt.get<SuwayomiBackend>()
    private val request = MutableStateFlow(
        ServerBrowseRequest(
            initialQuery.orEmpty(),
            if (initialQuery != null) {
                "SEARCH"
            } else if (latest) {
                "LATEST"
            } else {
                "POPULAR"
            },
            emptyList(),
            emptyList(),
            0,
        ),
    )
    val schema = MutableStateFlow(emptyList<ServerFilter>())
    val filterVersion = MutableStateFlow(0)
    val error = MutableStateFlow<SuwayomiFailure?>(null)
    val working = MutableStateFlow(false)
    var filters = FilterList()
        private set
    private val items = mutableMapOf<Int, MutableStateFlow<Pair<Manga, RaisedSearchMetadata?>>>()
    val pages = request.flatMapLatest { args ->
        Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false)) {
            object : PagingSource<Int, StateFlow<Pair<Manga, RaisedSearchMetadata?>>>() {
                override fun getRefreshKey(state: PagingState<Int, StateFlow<Pair<Manga, RaisedSearchMetadata?>>>): Int? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, StateFlow<Pair<Manga, RaisedSearchMetadata?>>> = try {
                    val page = params.key ?: 1
                    val session = backend.requireSession(sessionId)
                    session.requireBrowsableSource(source.id)
                    if (page == 1 && args.changes.isNotEmpty() && !args.schema.sameFilterSchema(session.sourceFilters(source.id))) {
                        resetFilters()
                        throw SuwayomiException(SuwayomiFailure.CONTRACT)
                    }
                    val result = session.browseSource(source.id, page, args.query, args.type, args.changes)
                    backend.requireSession(sessionId)
                    if (result.hasNextPage && result.mangas.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
                    val data = result.mangas.map { remote ->
                        val projection = remote.toKomikkuManga(sessionId)
                        synchronized(items) { items.getOrPut(remote.id) { MutableStateFlow(projection to null) }.also { it.value = projection to null } }
                    }
                    LoadResult.Page(data, null, if (result.hasNextPage) page + 1 else null)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    LoadResult.Error(e)
                }
            }
        }.flow
    }.cachedIn(screenModelScope)

    init {
        resetFilters()
    }
    fun resetFilters() = screenModelScope.launch(Dispatchers.IO) {
        try {
            schema.value = backend.requireSession(sessionId).sourceFilters(source.id)
            filters = schema.value.toKomikkuFilters()
            filterVersion.value++
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error.value = failure(e)
        }
    }
    fun filterChanged() {
        filters = filters.snapshot(schema.value).toKomikkuFilters()
        filterVersion.value++
    }
    fun search(query: String, type: String, filtered: Boolean = false) {
        request.value = ServerBrowseRequest(query, type, if (filtered) filters.toServerChanges() else emptyList(), schema.value, request.value.revision + 1)
    }
    fun favorite(mangas: List<Manga>, favorite: Boolean) = screenModelScope.launch(Dispatchers.IO) {
        if (working.value) return@launch
        working.value = true
        error.value = null
        try {
            for (manga in mangas) {
                val session = backend.requireSession(sessionId)
                val id = SuwayomiMode.remoteId(manga.id)
                val remote = session.manga(id)
                if (remote.sourceId != source.id) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                session.setLibrary(id, favorite)
                val updated = session.manga(id).toKomikkuManga(sessionId)
                synchronized(items) { items[id]?.value = updated to null }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error.value = failure(e)
        } finally {
            working.value = false
        }
    }
}

internal class SuwayomiSourceScreen(private val sessionId: String, private val source: ServerSource, private val latest: Boolean = false, private val initialQuery: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { SuwayomiSourceModel(sessionId, source, latest, initialQuery) }
        val pages = model.pages.collectAsLazyPagingItems()
        val version by model.filterVersion.collectAsState()
        val error by model.error.collectAsState()
        val working by model.working.collectAsState()
        val schema by model.schema.collectAsState()
        val nativeSource = remember(source) { StubSource(source.id, source.lang, source.name) }
        val prefs = remember { Injekt.get<LibraryPreferences>() }
        val sourcePrefs = remember { Injekt.get<SourcePreferences>() }
        val display by sourcePrefs.sourceDisplayMode().collectAsState()
        val portrait by prefs.portraitColumns().collectAsState()
        val landscape by prefs.landscapeColumns().collectAsState()
        val columns = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) landscape else portrait
        val base = remember { Injekt.get<BasePreferences>() }
        val incognito by base.incognitoMode().collectAsState()
        var query by remember { mutableStateOf<String?>(initialQuery) }
        var type by remember {
            mutableStateOf(
                if (initialQuery != null) {
                    "SEARCH"
                } else if (latest) {
                    "LATEST"
                } else {
                    "POPULAR"
                },
            )
        }
        var filter by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(emptyList<Manga>()) }
        var desiredFavorite by remember { mutableStateOf<Boolean?>(null) }
        var selectionMode by remember { mutableStateOf(false) }
        BackHandler(selectionMode) {
            selectionMode = false
            selected = emptyList()
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(error) { error?.let { snackbar.showSnackbar(context.stringResource(failureResource(it))) } }
        Scaffold(
            topBar = { behavior ->
                if (selectionMode) {
                    BulkSelectionToolbar(
                        selectedCount = selected.size,
                        isRunning = working,
                        onClickClearSelection = {
                            selected = emptyList()
                            selectionMode = false
                        },
                        onChangeCategoryClick = { if (selected.isNotEmpty()) desiredFavorite = true },
                        onSelectAll = { selected = pages.itemSnapshotList.items.map { it.value.first } },
                        onReverseSelection = {
                            val ids = selected.map { it.id }.toSet()
                            selected = pages.itemSnapshotList.items.map { it.value.first }.filterNot { it.id in ids }
                        },
                    )
                } else {
                    BrowseSourceToolbar(
                        source = nativeSource, searchQuery = query, onSearchQueryChange = { query = it },
                        onSearch = {
                            type = "SEARCH"
                            model.search(it, type)
                        }, displayMode = display, onDisplayModeChange = { sourcePrefs.sourceDisplayMode().set(it) },
                        navigateUp = { navigator.pop() }, onWebViewClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Injekt.get<SuwayomiBackend>().requireSession(sessionId).baseUrl))) },
                        onHelpClick = { navigator.push(SuwayomiCompatibilityScreen()) }, onToggleIncognito = { base.incognitoMode().set(!incognito) },
                        onSettingsClick = { navigator.push(SuwayomiAdministrationScreen(sessionId, source.id)) }, scrollBehavior = behavior,
                        toggleSelectionMode = {
                            selectionMode = true
                            selected = emptyList()
                        }, isRunning = working,
                    )
                }
            },
            bottomBar = {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == "POPULAR", {
                        type = "POPULAR"
                        query = null
                        model.search("", type)
                    }, label = { Text(stringResource(MR.strings.popular)) })
                    if (source.supportsLatest) {
                        FilterChip(type == "LATEST", {
                            type = "LATEST"
                            query = null
                            model.search("", type)
                        }, label = { Text(stringResource(MR.strings.latest)) })
                    }
                    if (schema.isNotEmpty()) FilterChip(type == "SEARCH", { filter = true }, label = { Text(stringResource(MR.strings.action_filter)) })
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            BrowseSourceContent(
                source = nativeSource, mangaList = pages, columns = if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns),
                ehentaiBrowseDisplayMode = false, displayMode = display, snackbarHostState = snackbar, contentPadding = padding,
                onWebViewClick = null, onHelpClick = null, onLocalSourceHelpClick = null, selection = selected,
                onMangaClick = { manga ->
                    if (selectionMode) {
                        selected = if (selected.any { it.id == manga.id }) selected.filterNot { it.id == manga.id } else selected + manga
                    } else {
                        navigator.push(serverMangaScreen(sessionId, SuwayomiMode.remoteId(manga.id)))
                    }
                },
                onMangaLongClick = { manga ->
                    if (selectionMode) {
                        navigator.push(serverMangaScreen(sessionId, SuwayomiMode.remoteId(manga.id)))
                    } else {
                        selected = listOf(manga)
                        desiredFavorite = !manga.favorite
                    }
                },
            )
        }
        if (filter) {
            // Reading version keeps the original mutable filter controls observable.
            remember(version) { model.filters }
            SourceFilterDialog(
                onDismissRequest = { filter = false }, filters = model.filters,
                onReset = { model.resetFilters() }, onFilter = {
                    type = "SEARCH"
                    model.search(query.orEmpty(), type, true)
                }, onUpdate = { model.filterChanged() },
                startExpanded = true, savedSearches = persistentListOf(), onSave = {}, onSavedSearch = {}, onSavedSearchPress = {},
                onSavedSearchPressDesc = "", shouldShowSavingButton = false, openMangaDexRandom = null, openMangaDexFollows = null,
            )
        }
        desiredFavorite?.let { value ->
            AlertDialog(
                onDismissRequest = {
                    desiredFavorite = null
                    selected = emptyList()
                },
                title = { Text(stringResource(if (!value) KMR.strings.suwayomi_remove_library else KMR.strings.suwayomi_add_library)) },
                text = { Text(stringResource(KMR.strings.suwayomi_bulk_notice, selected.size)) },
                confirmButton = {
                    TextButton(onClick = {
                        model.favorite(selected, value)
                        desiredFavorite = null
                        selected = emptyList()
                    }) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        desiredFavorite = null
                        selected = emptyList()
                    }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}
// KMK <--
