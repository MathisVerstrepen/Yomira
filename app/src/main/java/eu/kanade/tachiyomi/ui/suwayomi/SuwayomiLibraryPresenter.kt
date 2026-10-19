// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.imageLoader
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.downloadAction
import eu.kanade.tachiyomi.data.suwayomi.setCategoryMembership
import eu.kanade.tachiyomi.data.suwayomi.toLibraryItem
import eu.kanade.tachiyomi.data.suwayomi.updateLibraryOnServer
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.LibrarySettingsController
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The server presenter feeds Komikku's library UI; no Android library or source operation runs here. */
@Composable
internal fun SuwayomiLibraryTab() {
    val model = LibraryTab.rememberScreenModel { SuwayomiScreenModel() }
    val state by model.state.collectAsState()
    val session = state.session ?: return
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Injekt.get<LibraryPreferences>() }
    val base = remember { Injekt.get<BasePreferences>() }
    val incognito by base.incognitoMode().collectAsState()
    val settings = remember(model, session.id) { SuwayomiLibrarySettings(model, prefs, base) }
    var search by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var changeCategory by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<DownloadAction?>(null) }
    val group by remember { prefs.groupLibraryBy().asState(scope) }
    var page by rememberSaveable(session.id, group) { mutableIntStateOf(0) }
    val display = remember { prefs.displayMode().asState(scope) }
    val portrait = remember { prefs.portraitColumns().asState(scope) }
    val landscape = remember { prefs.landscapeColumns().asState(scope) }
    val sort by prefs.sortingMode().collectAsState()
    val perCategory by prefs.categorizedDisplaySettings().collectAsState()
    val showTabs by prefs.categoryTabs().collectAsState()
    val showCount by prefs.categoryNumberOfItems().collectAsState()
    val downloadBadge by prefs.downloadBadge().collectAsState()
    val unreadBadge by prefs.unreadBadge().collectAsState()
    val languageBadge by prefs.languageBadge().collectAsState()
    val languageIcon by prefs.useLangIcon().collectAsState()
    val sourceBadge by prefs.sourceBadge().collectAsState()
    val continueReading by prefs.showContinueReadingButton().collectAsState()
    val unread by prefs.filterUnread().collectAsState()
    val downloaded by prefs.filterDownloaded().collectAsState()
    val bookmarked by prefs.filterBookmarked().collectAsState()
    val started by prefs.filterStarted().collectAsState()
    val completed by prefs.filterCompleted().collectAsState()
    val filterCategories by prefs.filterCategories().collectAsState()
    val include by prefs.filterCategoriesInclude().collectAsState()
    val exclude by prefs.filterCategoriesExclude().collectAsState()
    val hasFilters = listOf(unread, downloaded, bookmarked, started, completed).any { it != TriState.DISABLED } || filterCategories
    val snack = remember { SnackbarHostState() }
    val allLabel = stringResource(MR.strings.label_library)
    val defaultLabel = stringResource(MR.strings.label_default)
    val remoteCategories = state.categories.sortedBy { it.order }.map {
        Category(it.id.toLong(), if (it.id == 0) defaultLabel else it.name, it.order.toLong(), 0, false)
    }.let { values -> if (values.any { it.id == 0L }) values else listOf(Category(0, defaultLabel, -1, 0, false)) + values }
    val categories = when (group) {
        LibraryGroup.BY_DEFAULT -> remoteCategories
        LibraryGroup.BY_SOURCE -> state.mangas.groupBy { it.sourceId }.map { (id, mangas) -> Category(id, mangas.first().source?.name.orEmpty(), 0, 0, false) }.sortedBy { it.name }
        LibraryGroup.BY_STATUS -> state.mangas.map { it.status }.distinct().sorted().mapIndexed { index, status -> Category(index.toLong() + 1, status, 0, 0, false) }
        else -> listOf(Category(-1, allLabel, 0, 0, false))
    }.ifEmpty { listOf(Category(-1, allLabel, 0, 0, false)) }.map { category ->
        val flag = state.presentation.category(category.id.toInt()).sortFlags.takeIf { perCategory && group == LibraryGroup.BY_DEFAULT } ?: sort.flag
        category.copy(flags = flag)
    }
    val activePage = page.coerceIn(categories.indices)
    val active = categories[activePage]
    val filtered = state.mangas.filter { manga ->
        val ids = manga.categories.nodes.map { it.id.toString() }.ifEmpty { listOf("0") }
        val query = search.orEmpty().trim()
        listOfNotNull(manga.title, manga.author, manga.artist, manga.description, manga.genre.joinToString(" ")).any { it.contains(query, true) } &&
            unread.matches(manga.user.unreadCount > 0) && downloaded.matches(manga.user.downloadCount > 0) && bookmarked.matches(manga.user.bookmarkCount > 0) &&
            started.matches(manga.chapters.totalCount > manga.user.unreadCount) && completed.matches(manga.status == "COMPLETED") &&
            (!filterCategories || ((include.isEmpty() || ids.any { it in include }) && ids.none { it in exclude }))
    }
    fun members(category: Category): List<ServerManga> = filtered.filter { manga ->
        when (group) {
            LibraryGroup.BY_DEFAULT -> if (category.id == 0L) manga.categories.nodes.all { it.id == 0 } else manga.categories.nodes.any { it.id.toLong() == category.id }
            LibraryGroup.BY_SOURCE -> manga.sourceId == category.id
            LibraryGroup.BY_STATUS -> manga.status == category.name
            else -> true
        }
    }.let { orderLibrary(it, LibrarySort.valueOf(category.flags)) }
    val visible = members(active)
    fun open(id: Long) {
        navigator.push(SuwayomiMangaScreen(session.id, SuwayomiMode.remoteId(id)))
    }
    fun updateLibrary() {
        scope.launch { runCatching { session.updateLibraryOnServer() }.onFailure { snack.showSnackbar(context.stringResource(failureResource(failure(it as? Exception ?: Exception())))) } }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    LaunchedEffect(Unit) {
        launch { LibraryTab.serverSearches.collect { search = it } }
        launch { LibraryTab.serverSettingsRequests.collect { sheet = true } }
    }
    LaunchedEffect(state.selected.isEmpty()) { HomeScreen.showBottomNav(state.selected.isEmpty()) }
    BackHandler(state.selected.isNotEmpty() || search != null) { if (state.selected.isNotEmpty()) model.clearSelection() else search = null }
    Scaffold(
        topBar = { behavior ->
            LibraryToolbar(
                hasActiveFilters = hasFilters, selectedCount = state.selected.size,
                title = LibraryToolbarTitle(if (showTabs || categories.size == 1) allLabel else active.name, state.total.takeIf { showCount }),
                onClickUnselectAll = model::clearSelection,
                onClickSelectAll = { model.selectVisible(visible.map { it.id }.toSet()) },
                onClickInvertSelection = { visible.forEach { model.select(it.id) } },
                onClickFilter = { sheet = true }, onClickRefresh = { updateLibrary() },
                onClickGlobalUpdate = ::updateLibrary,
                onClickOpenRandomManga = { visible.randomOrNull()?.let { open(SuwayomiMode.readerId(it.id)) } },
                onClickSyncNow = { model.refresh() }, onClickSyncExh = null, isSyncEnabled = false,
                searchQuery = search, onSearchQueryChange = { search = it }, scrollBehavior = behavior.takeIf { !showTabs },
                onInvalidateDownloadCache = {
                    context.imageLoader.memoryCache?.clear()
                    model.refresh()
                },
            )
        },
        bottomBar = {
            LibraryBottomActionMenu(
                visible = state.selected.isNotEmpty(),
                onChangeCategoryClicked = { changeCategory = true },
                onMarkAsReadClicked = { if (!incognito) model.bulk("read") }, onMarkAsUnreadClicked = { if (!incognito) model.bulk("unread") },
                onDownloadClicked = { download = it }, onDeleteClicked = { remove = true }, onMigrateClicked = null, onMergeClicked = null,
                onSelectionUpdateClicked = { model.selectionOperation { connection, manga -> connection.refreshManga(manga.id) } },
                onClickCleanTitles = null, onClickCollectRecommendations = null, onClickAddToMangaDex = null, onClickResetInfo = null,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        if (state.loading && state.mangas.isEmpty()) {
            LoadingScreen(Modifier.padding(padding))
        } else {
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                PendingChanges(session)
                state.error?.let { ConnectionError(it, { model.refresh() }, { navigator.push(SuwayomiHomeScreen) }) }
                if (state.loading) LinearProgressIndicator()
                LibraryContent(
                    categories = categories, activeCategoryIndex = activePage, currentPage = activePage, searchQuery = search,
                    selection = state.selected.map { SuwayomiMode.readerId(it) }.toSet(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                    hasActiveFilters = hasFilters, showPageTabs = showTabs, onChangeCurrentPage = { page = it }, onClickManga = ::open,
                    onContinueReadingClicked = if (continueReading) {
                        { manga ->
                            scope.launch {
                                runCatching {
                                    val chapter = session.chapters(SuwayomiMode.remoteId(manga.id)).filterNot { it.user.isRead }.maxByOrNull { it.sourceOrder }
                                    if (chapter != null) context.startActivity(ReaderActivity.newIntent(context, manga.id, SuwayomiMode.readerId(chapter.id)))
                                }.onFailure { snack.showSnackbar(context.stringResource(failureResource(failure(it as? Exception ?: Exception())))) }
                            }
                        }
                    } else {
                        null
                    },
                    onToggleSelection = { _, manga -> model.select(SuwayomiMode.remoteId(manga.id)) },
                    onToggleRangeSelection = { _, manga -> model.select(SuwayomiMode.remoteId(manga.id)) },
                    onRefresh = {
                        updateLibrary()
                        true
                    }, onGlobalSearchClicked = { scope.launch { HomeScreen.openTab(HomeScreen.Tab.Browse()) } },
                    getItemCountForCategory = { members(it).size.takeIf { showCount } }, getDisplayMode = { display },
                    getColumnsForOrientation = { if (it) landscape else portrait },
                    getItemsForCategory = { category -> members(category).map { it.toLibraryItem(session.id, downloadBadge, unreadBadge, languageBadge, languageIcon, sourceBadge) } },
                )
            }
        }
    }
    if (sheet) LibrarySettingsDialog({ sheet = false }, settings, active, remoteCategories.size > 1, remoteCategories.filterNot { it.isSystemCategory })
    if (changeCategory) {
        val selected = state.mangas.filter { it.id in state.selected }
        val selection = remoteCategories.filterNot { it.isSystemCategory }.map { category ->
            val contains = selected.count { manga -> manga.categories.nodes.any { it.id.toLong() == category.id } }
            when (contains) {
                0 -> tachiyomi.core.common.preference.CheckboxState.State.None(category)
                selected.size -> tachiyomi.core.common.preference.CheckboxState.State.Checked(category)
                else -> tachiyomi.core.common.preference.CheckboxState.TriState.Exclude(category)
            }
        }.toImmutableList()
        ChangeCategoryDialog(selection, { changeCategory = false }, { navigator.push(SuwayomiCategoriesScreen(session.id)) }) { add, removeIds ->
            model.selectionOperation { connection, manga ->
                add.forEach { connection.setCategoryMembership(manga.id, it.toInt(), true) }
                removeIds.forEach { connection.setCategoryMembership(manga.id, it.toInt(), false) }
            }
        }
    }
    if (remove || download != null) {
        AlertDialog(
            onDismissRequest = {
                remove = false
                download = null
            },
            title = { Text(stringResource(if (remove) KMR.strings.suwayomi_remove_library else KMR.strings.suwayomi_download_on_server)) },
            text = { Text(stringResource(KMR.strings.suwayomi_bulk_notice, state.selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    if (remove) {
                        model.bulk("remove")
                    } else {
                        download?.let { action ->
                            model.selectionOperation { connection, manga ->
                                downloadChapters(connection.chapters(manga.id), action).forEach { connection.downloadAction("enqueue", it.id) }
                            }
                        }
                    }
                    remove = false
                    download = null
                }) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    remove = false
                    download = null
                }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}

private class SuwayomiLibrarySettings(private val model: SuwayomiScreenModel, override val libraryPreferences: LibraryPreferences, override val preferences: BasePreferences) : LibrarySettingsController {
    override val trackersFlow = MutableStateFlow(emptyList<Tracker>())
    override val grouping get() = libraryPreferences.groupLibraryBy().get()
    override val serverBacked = true
    override val supportedSorts = setOf(LibrarySort.Type.Alphabetical, LibrarySort.Type.UnreadCount, LibrarySort.Type.LastRead, LibrarySort.Type.DateAdded, LibrarySort.Type.TotalChapters, LibrarySort.Type.Random)
    override fun toggleFilter(preference: (LibraryPreferences) -> Preference<TriState>) {
        preference(libraryPreferences).getAndSet { it.next() }
    }
    override fun toggleTracker(id: Int) = Unit
    override fun setDisplayMode(mode: LibraryDisplayMode) {
        libraryPreferences.displayMode().set(mode)
    }
    override fun setGrouping(grouping: Int) {
        libraryPreferences.groupLibraryBy().set(grouping)
    }
    override fun setSort(category: Category?, mode: LibrarySort.Type, direction: LibrarySort.Direction) {
        val sort = LibrarySort(mode, direction)
        if (category != null && grouping == LibraryGroup.BY_DEFAULT && libraryPreferences.categorizedDisplaySettings().get()) {
            val presentation = model.state.value.presentation
            model.presentation(presentation.withCategory(category.id.toInt(), presentation.category(category.id.toInt()).copy(sortFlags = sort.flag)))
        } else {
            libraryPreferences.sortingMode().set(sort)
        }
    }
}

internal fun TriState.matches(value: Boolean): Boolean = when (this) {
    TriState.DISABLED -> true
    TriState.ENABLED_IS -> value
    TriState.ENABLED_NOT -> !value
}

internal fun orderLibrary(mangas: List<ServerManga>, sort: LibrarySort): List<ServerManga> {
    val title = compareBy<ServerManga> { it.title.lowercase() }.thenBy { it.id }
    val comparator = when (sort.type) {
        LibrarySort.Type.UnreadCount -> compareBy<ServerManga> { it.user.unreadCount }.then(title)
        LibrarySort.Type.LastRead -> compareBy<ServerManga> { it.user.lastReadChapter?.user?.lastReadAt ?: 0 }.then(title)
        LibrarySort.Type.DateAdded -> compareBy<ServerManga> { it.user.inLibraryAt }.then(title)
        LibrarySort.Type.TotalChapters -> compareBy<ServerManga> { it.chapters.totalCount }.then(title)
        LibrarySort.Type.Random -> compareBy { (it.id.toLong() * 1103515245 + 12345) and 0x7fffffff }
        else -> title
    }
    return mangas.sortedWith(if (sort.isAscending) comparator else comparator.reversed())
}

internal fun downloadChapters(chapters: List<eu.kanade.tachiyomi.data.suwayomi.ServerChapter>, action: DownloadAction): List<eu.kanade.tachiyomi.data.suwayomi.ServerChapter> {
    val candidates = chapters.filter { !it.user.isDownloaded && (if (action == DownloadAction.BOOKMARKED_CHAPTERS) it.user.isBookmarked else !it.user.isRead) }.sortedByDescending { it.sourceOrder }
    return when (action) {
        DownloadAction.NEXT_1_CHAPTER -> candidates.take(1)
        DownloadAction.NEXT_5_CHAPTERS -> candidates.take(5)
        DownloadAction.NEXT_10_CHAPTERS -> candidates.take(10)
        DownloadAction.NEXT_25_CHAPTERS -> candidates.take(25)
        else -> candidates
    }
}
// KMK <--
