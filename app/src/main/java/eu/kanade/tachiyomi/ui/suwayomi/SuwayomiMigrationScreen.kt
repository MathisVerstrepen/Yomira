// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiMigrationScreen(private val sessionId: String, private val originalId: Int) : Screen() {
    @OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SuwayomiMigrationScreenModel(sessionId, originalId) }
        val state by model.state.collectAsState()
        val counts by model.chapterCounts.state.collectAsState()
        val latestState by rememberUpdatedState(state)
        val incognito by remember { Injekt.get<BasePreferences>().incognitoMode() }.collectAsState()
        val focus = LocalFocusManager.current
        val preview = state.preview
        val keyboardVisible = WindowInsets.isImeVisible
        val step = when {
            preview != null -> 2
            state.source != null || state.allProviders -> 1
            else -> 0
        }
        var expandedProviders by rememberSaveable(state.searchedQuery, state.allProviders) { mutableStateOf(emptyList<Long>()) }
        var sourceQuery by rememberSaveable { mutableStateOf("") }
        var chapterQuery by rememberSaveable(preview?.replacement?.id) { mutableStateOf("") }
        var chapterFilter by rememberSaveable(preview?.replacement?.id) { mutableStateOf(ChapterReviewFilter.ALL) }
        var optionsOpen by rememberSaveable { mutableStateOf(false) }
        val providerList = rememberLazyListState()
        val resultList = rememberLazyListState()
        val chapterList = rememberLazyListState()
        LaunchedEffect(model, resultList) {
            try {
                snapshotFlow {
                    val current = latestState
                    if (current.preview != null || current.completed || current.operation in listOf(MigrationOperation.LOAD, MigrationOperation.PREVIEW, MigrationOperation.TRANSFER) || (current.source == null && !current.allProviders)) {
                        emptyList()
                    } else {
                        val layout = resultList.layoutInfo
                        val ids = layout.visibleItemsInfo.filter { it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
                            .mapNotNull { (it.key as? String)?.removePrefix("manga-")?.toIntOrNull() }.toSet()
                        val candidates = if (current.allProviders) current.providerResults.flatMap { it.mangas } else current.results
                        candidates.filter { it.id in ids }
                    }
                }.distinctUntilChanged().collect { model.visibleTitles(it.map { manga -> manga.id }.toSet()) }
            } finally {
                model.visibleTitles(emptySet())
            }
        }
        val needsReload = state.error != null || state.changed || state.stopped
        LaunchedEffect(state.source?.id, state.allProviders, state.searchedQuery) { resultList.scrollToItem(0) }
        LaunchedEffect(preview?.replacement?.id) { chapterList.scrollToItem(0) }
        LaunchedEffect(sourceQuery) { providerList.scrollToItem(0) }
        LaunchedEffect(chapterFilter, keyboardVisible) {
            if (preview != null && (keyboardVisible || chapterFilter != ChapterReviewFilter.ALL)) {
                val filtersIndex = 2 + (if (needsReload) 1 else 0) + (if (state.loading) 1 else 0)
                chapterList.scrollToItem(filtersIndex)
            }
        }
        fun back() {
            focus.clearFocus()
            if (!model.back()) navigator.pop()
        }
        fun search() {
            focus.clearFocus()
            model.search()
        }
        BackHandler { back() }
        val sources = remember(state.sources, sourceQuery) {
            state.sources.filter { it.name.contains(sourceQuery, true) || it.lang.contains(sourceQuery, true) }
        }
        val matches = remember(preview, chapterFilter, chapterQuery) {
            preview?.matches.orEmpty().filter { chapterFilter.includes(it) && (it.original.name.contains(chapterQuery, true) || it.replacement?.name?.contains(chapterQuery, true) == true) }
        }
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                Column {
                    TopAppBar(title = {
                        Column {
                            Text(stringResource(KMR.strings.suwayomi_migration_title))
                            state.original?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }, navigationIcon = {
                        IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
                    })
                    if (!state.completed && !state.migrating) MigrationSteps(step)
                }
            },
            bottomBar = {
                if (preview != null && !state.migrating && !keyboardVisible) {
                    MigrationFooter(
                        label = stringResource(if (state.completed) KMR.strings.suwayomi_migration_open_replacement else KMR.strings.suwayomi_migration_continue),
                        enabled = state.completed || (!state.loading && !needsReload),
                        supporting = stringResource(if (state.completed) KMR.strings.suwayomi_migration_success else KMR.strings.suwayomi_migration_safe_until_done),
                        onClick = {
                            focus.clearFocus()
                            if (state.completed) navigator.replace(serverMangaScreen(sessionId, preview.replacement.id)) else optionsOpen = true
                        },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    state.completed && preview != null -> MigrationCompleted(preview.replacement.title, state.options.keepOriginal)
                    state.migrating -> MigrationTransferProgress(state.progress, model::cancel)
                    else -> LazyColumn(
                        modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        state = when (step) {
                            0 -> providerList
                            1 -> resultList
                            else -> chapterList
                        },
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (needsReload) {
                            item(key = "notice") {
                                MigrationNotice(
                                    text = when {
                                        state.changed -> stringResource(KMR.strings.suwayomi_migration_changed)
                                        state.stopped -> stringResource(KMR.strings.suwayomi_migration_stopped)
                                        else -> failureText(state.error!!)
                                    },
                                    retryLabel = stringResource(if (preview != null) KMR.strings.suwayomi_migration_reload_preview else KMR.strings.suwayomi_retry),
                                    onRetry = model::retry,
                                )
                            }
                        }
                        if (state.loading && state.operation != MigrationOperation.MORE && !(state.allProviders && state.operation == MigrationOperation.SEARCH)) {
                            item(key = "loading") {
                                MigrationLoading(state.operation, state.candidate?.title) {
                                    if (state.operation == MigrationOperation.LOAD) back() else model.cancel()
                                }
                            }
                        }
                        when {
                            preview != null -> {
                                item(key = "comparison") { MigrationComparison(preview, state.sources.find { it.id == preview.replacement.sourceId }?.name, !state.loading, model::back) }
                                item(key = "summary") { MigrationMatchSummary(preview) }
                                stickyHeader(key = "filters") {
                                    Surface(color = MaterialTheme.colorScheme.background) {
                                        Column(Modifier.padding(bottom = 8.dp)) {
                                            MigrationChapterFilters(preview, chapterFilter, {
                                                focus.clearFocus()
                                                chapterFilter = it
                                            })
                                            MigrationSearchField(chapterQuery, { chapterQuery = it }, stringResource(KMR.strings.suwayomi_migration_search_chapters), !state.loading)
                                        }
                                    }
                                }
                                if (matches.isEmpty()) {
                                    item(key = "empty") {
                                        MigrationEmpty(Icons.Outlined.SearchOff, stringResource(KMR.strings.suwayomi_migration_no_filtered_chapters), stringResource(KMR.strings.suwayomi_migration_adjust_filters))
                                    }
                                }
                                items(matches, key = { "chapter-${it.original.id}" }) { MigrationChapterRow(it) }
                            }
                            state.allProviders -> migrationAllProviderResults(
                                sessionId = sessionId,
                                state = state,
                                counts = counts,
                                expandedProviders = expandedProviders,
                                onToggleProvider = { id -> expandedProviders = if (id in expandedProviders) expandedProviders - id else expandedProviders + id },
                                model = model,
                                onSearch = ::search,
                                onPreview = { manga ->
                                    focus.clearFocus()
                                    model.preview(manga)
                                },
                            )
                            state.source != null -> {
                                item(key = "search") {
                                    MigrationProviderHeading(state.source!!, !state.loading, model::back)
                                    MigrationTitleSearch(state.query, model::query, !state.loading, ::search)
                                    if (state.searched) Text(stringResource(KMR.strings.suwayomi_migration_result_count, state.results.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                                }
                                if (state.searched && state.results.isEmpty() && !state.loading) {
                                    item(key = "empty") {
                                        MigrationEmpty(Icons.Outlined.SearchOff, stringResource(KMR.strings.suwayomi_migration_no_results_title), stringResource(KMR.strings.suwayomi_migration_no_results))
                                    }
                                }
                                items(state.results, key = { "manga-${it.id}" }) { manga ->
                                    MigrationTitleRow(sessionId, manga, !state.loading, chapterCount = counts[manga.migrationCountKey()], onRetryCount = { model.chapterCounts.retry(manga) }) {
                                        focus.clearFocus()
                                        model.preview(manga)
                                    }
                                }
                                if (state.operation == MigrationOperation.MORE) item(key = "more-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                if (state.hasNext) {
                                    item(key = "more") {
                                        TextButton(onClick = {
                                            focus.clearFocus()
                                            model.search(next = true)
                                        }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(KMR.strings.suwayomi_load_more)) }
                                    }
                                }
                            }
                            state.original != null -> {
                                item(key = "search") {
                                    Text(stringResource(KMR.strings.suwayomi_migration_provider_heading), style = MaterialTheme.typography.titleLarge)
                                    Text(stringResource(KMR.strings.suwayomi_migration_provider_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                    if (state.sources.isNotEmpty()) {
                                        MigrationAllProvidersEntry(!state.loading) {
                                            focus.clearFocus()
                                            model.selectAll()
                                        }
                                    }
                                    MigrationSearchField(sourceQuery, { sourceQuery = it }, stringResource(KMR.strings.suwayomi_migration_search_providers), !state.loading)
                                }
                                if (sources.isEmpty() && !state.loading) {
                                    item(key = "empty") {
                                        MigrationEmpty(Icons.Outlined.SearchOff, stringResource(KMR.strings.suwayomi_migration_no_providers_title), stringResource(if (state.sources.isEmpty()) KMR.strings.suwayomi_migration_no_sources else KMR.strings.suwayomi_migration_provider_filter_empty))
                                    }
                                }
                                items(sources, key = { "source-${it.id}" }) { source ->
                                    MigrationProviderRow(sessionId, source, !state.loading) {
                                        focus.clearFocus()
                                        model.select(source)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (optionsOpen && preview != null && !state.completed) {
            MigrationOptionsSheet(preview, state.options, incognito, model::options, { optionsOpen = false }) {
                optionsOpen = false
                model.migrate()
            }
        }
    }
}
// KMK <--
