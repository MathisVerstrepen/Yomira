// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.MigrationLibrarySource
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerSource
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiImages
import eu.kanade.tachiyomi.data.suwayomi.coverUrl
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun suwayomiMigrationTab(sessionId: String, model: SuwayomiMigrationLibraryModel, query: String?): TabContent {
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    var byCount by rememberSaveable(sessionId) { mutableStateOf(false) }
    var descending by rememberSaveable(sessionId) { mutableStateOf(false) }
    val unavailable = stringResource(KMR.strings.suwayomi_migration_unavailable_provider)
    val sources = remember(state.sources, query, byCount, descending, unavailable) {
        val comparator = if (byCount) compareBy<MigrationLibrarySource> { it.mangaCount }.thenBy { it.source?.name.orEmpty().lowercase() } else compareBy { source: MigrationLibrarySource -> source.source?.name?.lowercase() ?: unavailable.lowercase() }
        state.sources.filter { source ->
            query.isNullOrBlank() || listOf(source.source?.name ?: unavailable, source.source?.lang.orEmpty(), source.id.toString()).any { it.contains(query.trim(), true) }
        }.sortedWith(if (descending) comparator.reversed() else comparator)
    }
    return TabContent(
        titleRes = KMR.strings.suwayomi_migration_tab,
        searchEnabled = true,
        actions = persistentListOf(AppBar.Action(stringResource(KMR.strings.suwayomi_reload), Icons.Outlined.Refresh, onClick = { model.refresh() })),
    ) { padding, _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (state.loading && !state.hasLoaded) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.widthIn(max = 700.dp).fillMaxWidth(), contentPadding = padding) {
                    item(key = "intro") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(KMR.strings.suwayomi_migration_library_heading), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(KMR.strings.suwayomi_migration_library_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = !byCount, onClick = { byCount = false }, label = { Text(stringResource(KMR.strings.suwayomi_migration_sort_name)) })
                                FilterChip(selected = byCount, onClick = { byCount = true }, label = { Text(stringResource(KMR.strings.suwayomi_migration_sort_count)) })
                                IconButton(onClick = { descending = !descending }) {
                                    Icon(if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, stringResource(if (descending) KMR.strings.suwayomi_migration_sort_descending else KMR.strings.suwayomi_migration_sort_ascending))
                                }
                            }
                        }
                    }
                    if (state.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.error?.let { error ->
                        item(key = "error") { MigrationNotice(failureText(error), stringResource(KMR.strings.suwayomi_retry), onRetry = { model.refresh() }) }
                    }
                    if (state.hasLoaded && sources.isEmpty()) {
                        item(key = "empty") {
                            if (state.sources.isEmpty()) {
                                MigrationEmpty(Icons.Outlined.SwapHoriz, stringResource(KMR.strings.suwayomi_migration_library_empty), stringResource(KMR.strings.suwayomi_migration_library_empty_hint))
                            } else {
                                MigrationEmpty(Icons.Outlined.SearchOff, stringResource(KMR.strings.suwayomi_migration_no_results_title), stringResource(KMR.strings.suwayomi_migration_provider_filter_empty))
                            }
                        }
                    }
                    items(sources, key = { it.id }) { source ->
                        val name = source.source?.name ?: unavailable
                        Row(
                            Modifier.fillMaxWidth().clickable { navigator.push(SuwayomiMigrationTitlesScreen(sessionId, source.id, name)) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            MigrationProviderIcon(sessionId, ServerSource(source.id, name, source.source?.lang.orEmpty(), source.source?.iconUrl.orEmpty(), false), Modifier.size(40.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(source.source?.lang?.uppercase() ?: source.id.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(pluralStringResource(KMR.plurals.suwayomi_migration_library_titles, source.mangaCount, source.mangaCount), style = MaterialTheme.typography.bodyMedium)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

internal class SuwayomiMigrationTitlesScreen(private val sessionId: String, private val sourceId: Long, private val sourceName: String) : Screen() {
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SuwayomiMigrationLibraryModel(sessionId, sourceId) }
        val state by model.state.collectAsState()
        var query by rememberSaveable { mutableStateOf("") }
        val focus = LocalFocusManager.current
        val keyboardVisible = WindowInsets.isImeVisible
        val mangas = remember(state.mangas, query) { state.mangas.filter { it.title.contains(query.trim(), true) || it.author?.contains(query.trim(), true) == true } }
        val ids = remember(mangas) { mangas.map { it.id }.toSet() }
        val enabled = !state.loading && state.error == null
        LaunchedEffect(model) { model.refresh() }
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(KMR.strings.suwayomi_migration_select_titles))
                            Text(sourceName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) } },
                    actions = { IconButton(onClick = { model.refresh() }, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, stringResource(KMR.strings.suwayomi_reload)) } },
                )
            },
            bottomBar = {
                if (!keyboardVisible) {
                    MigrationFooter(
                        stringResource(KMR.strings.suwayomi_migration_continue_selected, state.selected.size),
                        enabled && state.selected.isNotEmpty(),
                        stringResource(KMR.strings.suwayomi_migration_queue_hint),
                    ) {
                        focus.clearFocus()
                        val queue = model.queue()
                        if (queue.isNotEmpty()) navigator.push(SuwayomiMigrationScreen(sessionId, queue.first(), queue.drop(1), batchSize = queue.size))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                    MigrationSearchField(query, { query = it }, stringResource(KMR.strings.suwayomi_migration_filter_library_titles), true)
                    if (state.hasLoaded && mangas.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().toggleable(value = state.selected.containsAll(ids), enabled = enabled, role = Role.Checkbox, onValueChange = { model.selectAll(ids) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(state.selected.containsAll(ids), null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
                            Text(stringResource(if (query.isBlank()) KMR.strings.suwayomi_migration_select_all else KMR.strings.suwayomi_migration_select_matches), modifier = Modifier.weight(1f))
                            Text(stringResource(KMR.strings.suwayomi_migration_selected_count, state.selected.size, state.mangas.size), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.widthIn(max = 700.dp).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    state.error?.let { error -> item(key = "error") { MigrationNotice(failureText(error), stringResource(KMR.strings.suwayomi_retry), onRetry = { model.refresh() }) } }
                    if (state.hasLoaded && mangas.isEmpty()) {
                        item(key = "empty") {
                            MigrationEmpty(Icons.Outlined.SearchOff, stringResource(KMR.strings.suwayomi_migration_no_results_title), stringResource(if (state.mangas.isEmpty()) KMR.strings.suwayomi_migration_provider_library_empty else KMR.strings.suwayomi_migration_adjust_filters))
                        }
                    }
                    items(mangas, key = { it.id }) { manga -> MigrationLibraryTitleRow(sessionId, manga, manga.id in state.selected, enabled) { model.toggle(manga.id) } }
                }
            }
        }
    }
}

@Composable
private fun MigrationLibraryTitleRow(sessionId: String, manga: ServerManga, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(manga.coverUrl()?.let { SuwayomiImages.url(sessionId, it) }, null, Modifier.width(44.dp).height(64.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(KMR.plurals.suwayomi_migration_provider_chapters, manga.chapters.totalCount, manga.chapters.totalCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(selected, null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
    }
}
// KMK <--
