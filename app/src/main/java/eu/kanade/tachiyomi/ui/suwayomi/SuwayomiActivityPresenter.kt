// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.history.HistoryScreen
import eu.kanade.presentation.history.components.HistoryFilterDialog
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.UpdateScreen
import eu.kanade.presentation.updates.UpdatesFilterDialog
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.suwayomi.ChapterIntentKind
import eu.kanade.tachiyomi.data.suwayomi.ChapterPatch
import eu.kanade.tachiyomi.data.suwayomi.ServerActivityChapter
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.downloadAction
import eu.kanade.tachiyomi.data.suwayomi.identity
import eu.kanade.tachiyomi.data.suwayomi.serverEpochMillis
import eu.kanade.tachiyomi.data.suwayomi.toKomikkuManga
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import eu.kanade.tachiyomi.ui.history.HistorySettingsScreenModel
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsScreenModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

@Composable
internal fun SuwayomiHistoryTab() = SuwayomiActivityPresenter(true)

@Composable
internal fun SuwayomiUpdatesTab() = SuwayomiActivityPresenter(false)

/** Reuses the existing history/updates screens, selection menus, row layouts, and preference sheets. */
@Composable
internal fun SuwayomiActivityPresenter(history: Boolean) {
    val backend = remember { Injekt.get<SuwayomiBackend>() }
    val connection by backend.session.collectAsState()
    val session = connection ?: return
    val owner = if (history) eu.kanade.tachiyomi.ui.history.HistoryTab else eu.kanade.tachiyomi.ui.updates.UpdatesTab
    val model = owner.rememberScreenModel(tag = "server-activity-$history-${session.id}") { SuwayomiActivityModel(session.id, history) }
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val base = remember { Injekt.get<BasePreferences>() }
    val incognito by base.incognitoMode().collectAsState()
    var search by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(false) }
    var selected by remember(session.id) { mutableStateOf(emptySet<Int>()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var deletion by remember { mutableStateOf<List<Int>?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    LaunchedEffect(state.error) {
        state.error?.let { error ->
            if (snack.showSnackbar(context.stringResource(failureResource(error)), context.stringResource(MR.strings.action_retry)) == SnackbarResult.ActionPerformed) model.refresh()
        }
    }
    LaunchedEffect(state.updating) {
        if (state.updating) {
            repeat(60) {
                delay(5000)
                model.refresh()
            }
        }
    }
    LaunchedEffect(selected.isEmpty()) { HomeScreen.showBottomNav(selected.isEmpty()) }
    fun open(mangaId: Long, chapterId: Long) {
        if (backend.session.value?.id == session.id) context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
    }
    fun chapters(ids: List<Int>, kind: ChapterIntentKind, patch: ChapterPatch) {
        if (incognito) return
        model.act { active ->
            ids.forEach { id ->
                val chapter = active.chapter(id)
                active.enqueue(id, kind, patch, identity = chapter.identity())
            }
            active.flush()
        }
        selected = emptySet()
    }
    fun download(ids: List<Int>, action: ChapterDownloadAction) {
        if (action == ChapterDownloadAction.DELETE) {
            deletion = ids
            return
        }
        model.act { active ->
            ids.forEach { id ->
                active.chapter(id)
                active.downloadAction(if (action == ChapterDownloadAction.CANCEL) "cancel" else "enqueue", id)
                if (action == ChapterDownloadAction.START_NOW) active.downloadAction("reorder", id, 0)
            }
        }
        selected = emptySet()
    }
    if (history) {
        val settings = owner.rememberScreenModel { HistorySettingsScreenModel() }
        val prefs = settings.historyPreferences
        val unfinishedManga by prefs.filterUnfinishedManga().collectAsState()
        val unfinishedChapter by prefs.filterUnfinishedChapter().collectAsState()
        val nonLibrary by prefs.filterNonLibraryManga().collectAsState()
        val panorama by prefs.usePanoramaCover().collectAsState()
        val entries = state.chapters.distinctBy { it.manga.id }.filter {
            it.manga.title.contains(search.orEmpty(), true) && unfinishedManga.matches(it.manga.user.unreadCount > 0) && unfinishedChapter.matches(!it.user.isRead) && nonLibrary.matches(!it.manga.user.inLibrary)
        }
        LaunchedEffect(state.loading, entries.isEmpty(), state.hasNext) { if (!state.loading && state.error == null && entries.isEmpty() && state.hasNext) model.refresh(next = true) }
        val nativeState = HistoryScreenModel.State(
            searchQuery = search,
            isLoading = state.loading && state.chapters.isEmpty(),
            list = entries.map { chapter ->
                val manga = chapter.manga.toKomikkuManga(session.id)
                HistoryWithRelations(
                    id = SuwayomiMode.readerId(chapter.id), chapterId = SuwayomiMode.readerId(chapter.id), mangaId = manga.id,
                    ogTitle = manga.title, chapterNumber = chapter.chapterNumber, read = chapter.user.isRead,
                    lastPageRead = chapter.user.lastPageRead.toLong(), totalCountCalculated = chapter.manga.chapters.totalCount.toLong(),
                    readCountCalculated = (chapter.manga.chapters.totalCount - chapter.manga.user.unreadCount).coerceAtLeast(0).toLong(),
                    readAt = Date(serverEpochMillis(chapter.user.lastReadAt)), readDuration = 0, coverData = manga.asMangaCover(),
                )
            }.toImmutableList(),
        )
        HistoryScreen(
            state = nativeState, snackbarHostState = snack, onSearchQueryChange = { search = it },
            onClickCover = { navigator.push(serverMangaScreen(session.id, SuwayomiMode.remoteId(it))) }, onClickResume = ::open,
            onClickFavorite = { id -> model.act { it.setLibrary(SuwayomiMode.remoteId(id), true) } },
            onDialogChange = {}, toggleSelectionMode = {}, onSelectAll = {}, onInvertSelection = {}, onHistorySelected = { _, _ -> },
            onFilterClicked = { filter = true }, hasActiveFilters = listOf(unfinishedManga, unfinishedChapter, nonLibrary).any { it != TriState.DISABLED },
            usePanoramaCover = panorama, canDeleteHistory = false,
            onLoadMore = if (state.hasNext && !state.loading && state.error == null) {
                { model.refresh(next = true) }
            } else {
                null
            },
        )
        if (filter) HistoryFilterDialog({ filter = false }, settings)
    } else {
        val settings = owner.rememberScreenModel { UpdatesSettingsScreenModel() }
        val prefs = settings.updatesPreferences
        val unread by prefs.filterUnread().collectAsState()
        val downloaded by prefs.filterDownloaded().collectAsState()
        val started by prefs.filterStarted().collectAsState()
        val bookmarked by prefs.filterBookmarked().collectAsState()
        val panorama by prefs.usePanoramaCover().collectAsState()
        val entries = state.chapters.filter { unread.matches(!it.user.isRead) && downloaded.matches(it.user.isDownloaded) && started.matches(it.user.lastPageRead > 0 && !it.user.isRead) && bookmarked.matches(it.user.isBookmarked) }
        LaunchedEffect(state.loading, entries.isEmpty(), state.hasNext) { if (!state.loading && state.error == null && entries.isEmpty() && state.hasNext) model.refresh(next = true) }
        val nativeState = UpdatesScreenModel.State(
            isLoading = state.loading && state.chapters.isEmpty(),
            expandedState = expanded,
            items = entries.map { chapter ->
                val manga = chapter.manga.toKomikkuManga(session.id)
                UpdatesItem(
                    UpdatesWithRelations(
                        mangaId = manga.id, ogMangaTitle = manga.title, chapterId = SuwayomiMode.readerId(chapter.id), chapterName = chapter.name,
                        scanlator = null, chapterUrl = "suwayomi:chapter:${chapter.id}", read = chapter.user.isRead, bookmark = chapter.user.isBookmarked,
                        lastPageRead = chapter.user.lastPageRead.toLong(), sourceId = manga.source, dateFetch = serverEpochMillis(chapter.fetchedAt), coverData = manga.asMangaCover(),
                    ),
                    downloadStateProvider = { if (chapter.user.isDownloaded) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED },
                    downloadProgressProvider = { 0 },
                    selected = chapter.id in selected,
                )
            }.toPersistentList(),
        )
        val libraryPrefs = remember { Injekt.get<LibraryPreferences>() }
        val start by libraryPrefs.swipeToStartAction().collectAsState()
        val end by libraryPrefs.swipeToEndAction().collectAsState()
        fun ids(items: List<UpdatesItem>) = items.map { SuwayomiMode.remoteId(it.update.chapterId) }
        UpdateScreen(
            state = nativeState, snackbarHostState = snack, lastUpdated = 0, preserveReadingPosition = true,
            onClickCover = { navigator.push(serverMangaScreen(session.id, SuwayomiMode.remoteId(it.update.mangaId))) },
            onSelectAll = { selected = if (it) entries.map { chapter -> chapter.id }.toSet() else emptySet() },
            onInvertSelection = { selected = entries.map { it.id }.toSet() - selected }, onCalendarClicked = {}, showCalendar = false,
            onUpdateLibrary = {
                model.refresh(fetch = true)
                true
            },
            onDownloadChapter = { items, action -> download(ids(items), action) },
            onMultiBookmarkClicked = { items, value -> chapters(ids(items), ChapterIntentKind.BOOKMARK, ChapterPatch(isBookmarked = value)) },
            onMultiMarkAsReadClicked = { items, value -> chapters(ids(items), if (value) ChapterIntentKind.MARK_READ else ChapterIntentKind.MARK_UNREAD, ChapterPatch(isRead = value, lastPageRead = if (value) null else 0)) },
            onMultiDeleteClicked = { deletion = ids(it) }, updateSwipeStartAction = if (incognito) LibraryPreferences.ChapterSwipeAction.Disabled else start,
            updateSwipeEndAction = if (incognito) LibraryPreferences.ChapterSwipeAction.Disabled else end,
            onUpdateSwipe = { item, action ->
                val id = SuwayomiMode.remoteId(item.update.chapterId)
                when (action) {
                    LibraryPreferences.ChapterSwipeAction.ToggleRead -> chapters(listOf(id), if (item.update.read) ChapterIntentKind.MARK_UNREAD else ChapterIntentKind.MARK_READ, ChapterPatch(isRead = !item.update.read, lastPageRead = if (item.update.read) 0 else null))
                    LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> chapters(listOf(id), ChapterIntentKind.BOOKMARK, ChapterPatch(isBookmarked = !item.update.bookmark))
                    LibraryPreferences.ChapterSwipeAction.Download -> download(listOf(id), if (item.downloadStateProvider() == Download.State.DOWNLOADED) ChapterDownloadAction.DELETE else ChapterDownloadAction.START)
                    LibraryPreferences.ChapterSwipeAction.Disabled -> Unit
                }
            },
            onUpdateSelected = { item, options ->
                val id = SuwayomiMode.remoteId(item.update.chapterId)
                selected = if (options.selected) selected + id else selected - id
            },
            onOpenChapter = { open(it.update.mangaId, it.update.chapterId) }, onFilterClicked = { filter = true },
            hasActiveFilters = listOf(unread, downloaded, started, bookmarked).any { it != TriState.DISABLED }, usePanoramaCover = panorama,
            collapseToggle = { expanded = if (it in expanded) expanded - it else expanded + it },
            onLoadMore = if (state.hasNext && !state.loading && state.error == null) {
                { model.refresh(next = true) }
            } else {
                null
            },
        )
        if (filter) UpdatesFilterDialog({ filter = false }, settings)
    }
    deletion?.let { ids ->
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text(stringResource(KMR.strings.suwayomi_delete_server_download)) },
            text = { Text(stringResource(KMR.strings.suwayomi_download_notice)) },
            confirmButton = {
                TextButton(onClick = {
                    model.act { active -> ids.forEach { active.downloadAction("delete", it) } }
                    deletion = null
                    selected = emptySet()
                }) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text(stringResource(MR.strings.action_cancel)) } },
        )
    }
}
// KMK <--
