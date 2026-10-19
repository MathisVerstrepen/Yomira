// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.palette.graphics.Palette
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.chrisbanes.haze.HazeState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.suwayomi.ChapterIntentKind
import eu.kanade.tachiyomi.data.suwayomi.ChapterPatch
import eu.kanade.tachiyomi.data.suwayomi.OfflineState
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiLink
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.downloadAction
import eu.kanade.tachiyomi.data.suwayomi.identity
import eu.kanade.tachiyomi.data.suwayomi.serverSources
import eu.kanade.tachiyomi.data.suwayomi.setCategoryMembership
import eu.kanade.tachiyomi.data.suwayomi.toKomikkuManga
import eu.kanade.tachiyomi.data.suwayomi.toReaderChapter
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.manga.PagePreviewState
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.presentation.manga.MangaScreen as MangaContent

/** Server data and commands drive Komikku's existing phone/tablet details and chapter UI. */
@Composable
internal fun SuwayomiMangaPresenter(presentationId: Long, expectedSession: String?, fromSource: Boolean) {
    val navigator = LocalNavigator.currentOrThrow
    val owner = navigator.lastItem
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backend = remember { Injekt.get<SuwayomiBackend>() }
    val current by backend.session.collectAsState()
    val sessionId by rememberSaveable { mutableStateOf(expectedSession ?: current?.id) }
    val session = current?.takeIf { it.id == sessionId }
    val mangaId = remember(presentationId) { runCatching { SuwayomiMode.remoteId(presentationId) }.getOrNull() }
    if (session == null || mangaId == null) {
        Scaffold(topBar = { AppBar(title = stringResource(KMR.strings.suwayomi_manga), navigateUp = { navigator.pop() }) }) { padding ->
            Column(Modifier.padding(padding)) { ConnectionError(SuwayomiFailure.SESSION_CHANGED, { navigator.pop() }) }
        }
        return
    }
    val model = owner.rememberScreenModel(tag = "server-manga-$sessionId-$mangaId") { SuwayomiMangaScreenModel(session.id, mangaId) }
    val state by model.state.collectAsState()
    val offline by session.offline.state.collectAsState()
    val prefs = remember { Injekt.get<LibraryPreferences>() }
    val base = remember { Injekt.get<BasePreferences>() }
    val incognito by base.incognitoMode().collectAsState()
    val ui = remember { Injekt.get<UiPreferences>() }
    val coverTheme by ui.themeCoverBased().collectAsState()
    var seedColor by remember { mutableStateOf<Color?>(null) }
    val snack = remember { SnackbarHostState() }
    var flags by rememberSaveable(session.id, mangaId) { mutableLongStateOf(session.libraryPresentation().chapterFlags[mangaId] ?: chapterDefaults(prefs)) }
    var excluded by remember(session.id, mangaId) { mutableStateOf(session.libraryPresentation().excludedScanlators[mangaId].orEmpty()) }
    var selected by remember(session.id, mangaId) { mutableStateOf(emptySet<Long>()) }
    var selectionAnchor by remember { mutableStateOf<Long?>(null) }
    var settings by remember { mutableStateOf(false) }
    var categoryDialog by remember { mutableStateOf(false) }
    var scanlators by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<Pair<List<Int>, ChapterDownloadAction>?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    LaunchedEffect(state.error) {
        state.error?.let { error ->
            if (error == SuwayomiFailure.NETWORK && state.manga != null && offline.titles.containsKey(mangaId)) return@let
            if (snack.showSnackbar(context.stringResource(failureResource(error)), context.stringResource(MR.strings.action_retry)) == SnackbarResult.ActionPerformed) model.refresh()
        }
    }
    val remote = state.manga
    if (remote == null) {
        if (state.error == null) {
            LoadingScreen()
        } else {
            Scaffold(topBar = { AppBar(title = stringResource(KMR.strings.suwayomi_manga), navigateUp = { navigator.pop() }) }) { padding ->
                Column(Modifier.padding(padding)) { ConnectionError(state.error!!, { model.refresh() }, { navigator.push(SuwayomiHomeScreen) }) }
            }
        }
        return
    }
    val manga = remote.toKomikkuManga(session.id).copy(chapterFlags = flags)
    val native = MangaScreenModel.State.Success(
        manga = manga, source = StubSource(remote.sourceId, remote.source?.lang.orEmpty(), remote.source?.name ?: "Suwayomi"), isFromSource = fromSource,
        chapters = state.chapters.filterNot { it.scanlator in excluded }.map {
            ChapterList.Item(
                it.toReaderChapter(),
                when (offline.downloads.firstOrNull { download -> download.chapterId == it.id }?.state) {
                    OfflineState.COMPLETE -> Download.State.DOWNLOADED
                    OfflineState.DOWNLOADING -> Download.State.DOWNLOADING
                    OfflineState.ERROR -> Download.State.ERROR
                    null -> Download.State.NOT_DOWNLOADED
                    else -> Download.State.QUEUE
                },
                0,
                selected = SuwayomiMode.readerId(it.id) in selected,
                sourceName = null,
                showScanlator = true,
                serverDownloaded = it.user.isDownloaded,
            )
        },
        availableScanlators = state.chapters.mapNotNull { it.scanlator }.toImmutableSet(), excludedScanlators = excluded.toImmutableSet(),
        isRefreshingData = state.refreshing, hideMissingChapters = prefs.hideMissingChapters().get(), trackingCount = 0, hasLoggedInTrackers = true,
        meta = null, mergedData = null, showRecommendationsInOverflow = false, showMergeInOverflow = false, showMergeWithAnother = false,
        pagePreviewsState = PagePreviewState.Unused, alwaysShowReadingProgress = true, previewsRowCount = 0,
        serverBacked = true, seedColor = seedColor,
    )
    fun saveFlags(value: Long) {
        try {
            backend.requireSession(session.id).updateLibraryPresentation { it.copy(chapterFlags = it.chapterFlags + (mangaId to value)) }
            flags = value
        } catch (e: Exception) {
            scope.launch { snack.showSnackbar(context.stringResource(failureResource(failure(e)))) }
        }
    }
    fun setFlag(value: Long, mask: Long) {
        saveFlags(flags and mask.inv() or (value and mask))
    }
    fun open(chapter: Chapter) {
        if (backend.session.value?.id == session.id) context.startActivity(ReaderActivity.newIntent(context, manga.id, chapter.id))
    }
    fun personal(chapters: List<Chapter>, kind: ChapterIntentKind, patch: ChapterPatch) {
        if (incognito) {
            scope.launch { snack.showSnackbar(context.stringResource(KMR.strings.suwayomi_incognito_notice)) }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val active = backend.requireSession(session.id)
                for (item in chapters) {
                    val chapter = state.chapters.first { it.id == SuwayomiMode.remoteId(item.id) }
                    if (chapter.mangaId != mangaId || chapter.manga.sourceId != remote.sourceId || chapter.manga.url != remote.url) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                    active.enqueue(chapter.id, kind, patch, identity = chapter.identity())
                }
                active.flushSoon()
                model.refresh(force = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snack.showSnackbar(context.stringResource(failureResource(failure(e))))
            }
        }
        selected = emptySet()
    }
    fun source(query: String? = null) {
        scope.launch {
            try {
                val descriptor = session.serverSources().firstOrNull { it.id == remote.sourceId } ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)
                backend.requireSession(session.id)
                navigator.push(SuwayomiSourceScreen(session.id, descriptor, initialQuery = query))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                snack.showSnackbar(context.stringResource(failureResource(failure(e))))
            }
        }
    }
    fun librarySearch(query: String) {
        scope.launch {
            navigator.popUntil { it is HomeScreen }
            HomeScreen.search(query)
        }
    }
    fun share() {
        val link = SuwayomiLink(session.baseUrl, remote.id, remote.sourceId, remote.url).encode()
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
    }
    val swipeStart by prefs.swipeToEndAction().collectAsState()
    val swipeEnd by prefs.swipeToStartAction().collectAsState()
    TachiyomiTheme(seedColor = seedColor.takeIf { coverTheme }) {
        MangaContent(
            state = native, snackbarHostState = snack, nextUpdate = null, isTabletUi = isTabletUi(),
            chapterSwipeStartAction = swipeStart, chapterSwipeEndAction = swipeEnd, navigateUp = { navigator.pop() }, onChapterClicked = ::open,
            onDownloadChapter = { items, action -> download = items.map { SuwayomiMode.remoteId(it.id) } to action },
            onAddToLibraryClicked = { model.perform { setLibrary(mangaId, !remote.user.inLibrary) } },
            onWebViewClicked = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(session.baseUrl))) }, onWebViewLongClicked = null,
            onTrackingClicked = { navigator.push(SuwayomiTrackingScreen(session.id, mangaId)) }, onTagSearch = { source(it) },
            onFilterButtonClicked = { settings = true }, onRefresh = { model.refresh(fetch = true) },
            onContinueReading = { native.processedChapters.map { it.chapter }.filterNot { it.read }.sortedWith(getChapterSort(manga, false)).firstOrNull()?.let(::open) },
            onSearch = { query, _ -> source(query) }, onCoverClicked = { cover = true }, onShareClicked = ::share,
            onDownloadActionClicked = { action -> download = downloadChapters(state.chapters.map { chapter -> chapter.copy(user = chapter.user.copy(isDownloaded = offline.downloads.any { it.chapterId == chapter.id && it.state == OfflineState.COMPLETE })) }, action).map { it.id } to ChapterDownloadAction.START },
            onEditCategoryClicked = { categoryDialog = true }, onEditFetchIntervalClicked = null, onMigrateClicked = null,
            onEditNotesClicked = { navigator.push(SuwayomiCompatibilityScreen()) }, onMetadataViewerClicked = { navigator.push(SuwayomiCompatibilityScreen()) },
            onEditInfoClicked = { navigator.push(SuwayomiCompatibilityScreen()) }, onRecommendClicked = { navigator.push(SuwayomiCompatibilityScreen()) },
            onMergedSettingsClicked = { navigator.push(SuwayomiCompatibilityScreen()) }, onMergeClicked = { navigator.push(SuwayomiCompatibilityScreen()) }, onMergeWithAnotherClicked = { navigator.push(SuwayomiCompatibilityScreen()) },
            onOpenPagePreview = {}, onMorePreviewsClicked = {}, previewsRowCount = 0,
            onMultiBookmarkClicked = { chapters, value -> personal(chapters, ChapterIntentKind.BOOKMARK, ChapterPatch(isBookmarked = value)) },
            onMultiMarkAsReadClicked = { chapters, value -> personal(chapters, if (value) ChapterIntentKind.MARK_READ else ChapterIntentKind.MARK_UNREAD, ChapterPatch(isRead = value, lastPageRead = if (value) null else 0)) },
            onMarkPreviousAsReadClicked = { target ->
                val older = native.processedChapters.map { it.chapter }.sortedWith(getChapterSort(manga, false)).takeWhile { it.id != target.id }
                personal(older, ChapterIntentKind.MARK_READ, ChapterPatch(isRead = true))
            },
            onMultiDeleteClicked = { download = it.map { chapter -> SuwayomiMode.remoteId(chapter.id) } to ChapterDownloadAction.DELETE },
            onChapterSwipe = { item, action ->
                when (action) {
                    LibraryPreferences.ChapterSwipeAction.ToggleRead -> personal(listOf(item.chapter), if (item.chapter.read) ChapterIntentKind.MARK_UNREAD else ChapterIntentKind.MARK_READ, ChapterPatch(isRead = !item.chapter.read, lastPageRead = if (item.chapter.read) 0 else null))
                    LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> personal(listOf(item.chapter), ChapterIntentKind.BOOKMARK, ChapterPatch(isBookmarked = !item.chapter.bookmark))
                    LibraryPreferences.ChapterSwipeAction.Download -> download = listOf(SuwayomiMode.remoteId(item.id)) to if (item.isDownloaded) ChapterDownloadAction.DELETE else ChapterDownloadAction.START
                    LibraryPreferences.ChapterSwipeAction.Disabled -> Unit
                }
            },
            onChapterSelected = { item, value, range ->
                val ids = native.processedChapters.map { it.id }
                val start = ids.indexOf(selectionAnchor)
                val end = ids.indexOf(item.id)
                val targets = if (range && start >= 0 && end >= 0) ids.subList(minOf(start, end), maxOf(start, end) + 1).toSet() else setOf(item.id)
                selected = if (value) selected + targets else selected - targets
                selectionAnchor = item.id
            },
            onAllChapterSelected = { selected = if (it) native.processedChapters.map { item -> item.id }.toSet() else emptySet() },
            onInvertSelection = { selected = native.processedChapters.map { it.id }.toSet() - selected },
            getMangaState = { remember(it) { mutableStateOf(it) } }, onClickSourceSettingsClicked = { navigator.push(SuwayomiSourcePreferencesScreen(session.id, remote.sourceId, remote.source?.name, remote.source?.lang)) },
            onClearManga = { navigator.push(SuwayomiCompatibilityScreen()) }, onOpenMangaFolder = null, onRelatedMangasScreenClick = { navigator.push(SuwayomiCompatibilityScreen()) },
            onRelatedMangaClick = { navigator.push(serverMangaScreen(session.id, SuwayomiMode.remoteId(it.id))) }, onRelatedMangaLongClick = {},
            librarySearch = ::librarySearch, onSourceClick = { source() },
            onCoverLoaded = { data ->
                scope.launch {
                    val result = context.imageLoader.execute(ImageRequest.Builder(context).data(data).allowHardware(false).build()) as? SuccessResult
                    val bitmap = result?.image?.asDrawable(context.resources)?.toBitmap()
                    val color = bitmap?.let { withContext(Dispatchers.Default) { Palette.from(it).generate().getBestColor() } }
                    if (backend.session.value?.id == session.id && color != null) seedColor = Color(color)
                }
            },
            coverRatio = remember { mutableFloatStateOf(1f) }, onPaletteScreenClick = {}, hazeState = remember { HazeState() },
        )
    }
    if (settings) {
        ChapterSettingsDialog(
            onDismissRequest = { settings = false }, manga = manga,
            onDownloadFilterChanged = { setFlag(filterFlag(it, Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED), Manga.CHAPTER_DOWNLOADED_MASK) },
            onUnreadFilterChanged = { setFlag(filterFlag(it, Manga.CHAPTER_SHOW_UNREAD, Manga.CHAPTER_SHOW_READ), Manga.CHAPTER_UNREAD_MASK) },
            onBookmarkedFilterChanged = { setFlag(filterFlag(it, Manga.CHAPTER_SHOW_BOOKMARKED, Manga.CHAPTER_SHOW_NOT_BOOKMARKED), Manga.CHAPTER_BOOKMARKED_MASK) },
            scanlatorFilterActive = excluded.isNotEmpty(), onScanlatorFilterClicked = { scanlators = true },
            onSortModeChanged = { value -> if (manga.sorting == value) setFlag(if (manga.sortDescending()) Manga.CHAPTER_SORT_ASC else Manga.CHAPTER_SORT_DESC, Manga.CHAPTER_SORT_DIR_MASK) else setFlag(value, Manga.CHAPTER_SORTING_MASK) },
            onDisplayModeChanged = { setFlag(it, Manga.CHAPTER_DISPLAY_MASK) }, onResetToDefault = { saveFlags(chapterDefaults(prefs)) },
            onSetAsDefault = { existing ->
                prefs.setChapterSettingsDefault(manga)
                if (existing) {
                    model.perform {
                        val ids = libraryPresentation().chapterFlags.keys.toMutableSet()
                        var cursor: String? = null
                        do {
                            val page = library(cursor)
                            ids += page.nodes.map { it.id }
                            if (!page.pageInfo.hasNextPage) break
                            val next = page.pageInfo.endCursor
                            if (next == null || next == cursor || page.nodes.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
                            cursor = next
                        } while (true)
                        updateLibraryPresentation { it.copy(chapterFlags = ids.associateWith { flags }) }
                    }
                }
            },
        )
    }
    if (categoryDialog) {
        eu.kanade.presentation.category.components.ChangeCategoryDialog(
            initialSelection = state.categories.filter { it.id != 0 }.map { category ->
                val value = tachiyomi.domain.category.model.Category(category.id.toLong(), category.name, category.order.toLong(), 0, false)
                if (remote.categories.nodes.any { it.id == category.id }) {
                    tachiyomi.core.common.preference.CheckboxState.State.Checked(value)
                } else {
                    tachiyomi.core.common.preference.CheckboxState.State.None(value)
                }
            }.toImmutableList(),
            onDismissRequest = { categoryDialog = false },
            onEditCategories = { navigator.push(eu.kanade.tachiyomi.ui.category.CategoryScreen(serverSessionId = session.id)) },
            onConfirm = { add, remove ->
                model.perform {
                    add.forEach { setCategoryMembership(mangaId, it.toInt(), true) }
                    remove.forEach { setCategoryMembership(mangaId, it.toInt(), false) }
                }
            },
        )
    }
    if (scanlators) {
        ScanlatorFilterDialog(native.availableScanlators, excluded.toImmutableSet(), { scanlators = false }) { value ->
            try {
                backend.requireSession(session.id).updateLibraryPresentation { it.copy(excludedScanlators = it.excludedScanlators + (mangaId to value)) }
                excluded = value
            } catch (e: Exception) {
                scope.launch { snack.showSnackbar(context.stringResource(failureResource(failure(e)))) }
            }
        }
    }
    if (cover) {
        val coverModel = owner.rememberScreenModel(tag = "server-cover-${session.id}-$mangaId-${manga.coverLastModified}") {
            eu.kanade.tachiyomi.ui.manga.MangaCoverScreenModel(manga.id, projection = kotlinx.coroutines.flow.flowOf(manga), assertActive = { backend.requireSession(session.id) })
        }
        val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> if (granted) coverModel.saveCover(context) }
        MangaCoverDialog(manga, false, coverModel.snackbarHostState, { coverModel.shareCover(context) }, {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_DENIED) {
                permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                coverModel.saveCover(context)
            }
        }, null, { cover = false })
    }
    download?.let { (ids, action) ->
        val phoneCopies = offline.downloads.filter { it.chapterId in ids }
        val phoneComplete = phoneCopies.filter { it.state == OfflineState.COMPLETE }.map { it.chapterId }.toSet()
        val serverComplete = state.chapters.filter { it.id in ids && it.user.isDownloaded }.map { it.id }.toSet()
        val removePhone = action == ChapterDownloadAction.DELETE || action == ChapterDownloadAction.CANCEL || ids.all { it in phoneComplete }
        val removeServer = action != ChapterDownloadAction.CANCEL && (action == ChapterDownloadAction.DELETE || ids.all { it in serverComplete })
        val phoneIds = if (removePhone) phoneCopies.map { it.chapterId } else ids.filterNot { it in phoneComplete }
        val serverIds = if (removeServer) ids.filter { it in serverComplete } else ids.filterNot { it in serverComplete }
        AlertDialog(
            onDismissRequest = { download = null },
            title = { Text(stringResource(KMR.strings.suwayomi_chapter_downloads)) },
            text = {
                Column {
                    if (ids.isNotEmpty() && ids.all { it in phoneComplete }) Text(stringResource(KMR.strings.suwayomi_downloaded_on_phone))
                    if (ids.isNotEmpty() && ids.all { it in serverComplete }) Text(stringResource(KMR.strings.suwayomi_downloaded_on_server))
                    Text(stringResource(KMR.strings.suwayomi_offline_download_notice))
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val active = backend.requireSession(session.id)
                                if (removePhone) {
                                    phoneIds.forEach { active.removeOffline(it) }
                                } else {
                                    active.enqueueOffline(remote, state.chapters, phoneIds)
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                snack.showSnackbar(context.stringResource(KMR.strings.suwayomi_offline_error))
                            }
                        }
                        download = null
                        selected = emptySet()
                    }, enabled = phoneIds.isNotEmpty()) { Text(stringResource(if (removePhone) KMR.strings.suwayomi_offline_delete else KMR.strings.suwayomi_download_to_phone)) }
                    if (state.canDownload) {
                        TextButton(onClick = {
                            model.perform {
                                serverIds.forEach { id ->
                                    val currentChapter = chapter(id)
                                    if (currentChapter.mangaId != mangaId) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                                    downloadAction(
                                        when {
                                            removeServer -> "delete"
                                            action == ChapterDownloadAction.CANCEL -> "cancel"
                                            else -> "enqueue"
                                        },
                                        id,
                                    )
                                    if (action == ChapterDownloadAction.START_NOW) downloadAction("reorder", id, 0)
                                }
                            }
                            download = null
                            selected = emptySet()
                        }, enabled = serverIds.isNotEmpty()) {
                            Text(
                                stringResource(
                                    when {
                                        removeServer -> KMR.strings.suwayomi_delete_server_download
                                        action == ChapterDownloadAction.CANCEL -> KMR.strings.suwayomi_cancel_server_download
                                        else -> KMR.strings.suwayomi_download_on_server
                                    },
                                ),
                            )
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { download = null }) { Text(stringResource(MR.strings.action_cancel)) } },
        )
    }
}

private fun filterFlag(state: TriState, included: Long, excluded: Long): Long = when (state) {
    TriState.ENABLED_IS -> included
    TriState.ENABLED_NOT -> excluded
    TriState.DISABLED -> 0
}
private fun chapterDefaults(preferences: LibraryPreferences) = preferences.filterChapterByRead().get() or preferences.filterChapterByDownloaded().get() or preferences.filterChapterByBookmarked().get() or preferences.sortChapterBySourceOrNumber().get() or preferences.displayChapterByNameOrNumber().get() or preferences.sortChapterByAscendingOrDescending().get()
// KMK <--
