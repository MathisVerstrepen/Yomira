// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.suwayomi.LibraryFilter
import eu.kanade.tachiyomi.data.suwayomi.LibraryGroup
import eu.kanade.tachiyomi.data.suwayomi.LibrarySort
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.libraryMatches
import eu.kanade.tachiyomi.data.suwayomi.libraryOrder
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun SuwayomiLibraryContent(state: SuwayomiScreenModel.State, model: SuwayomiScreenModel, navigator: Navigator, incognito: Boolean, modifier: Modifier = Modifier) {
    val session = state.session ?: return
    var search by remember(session.id) { mutableStateOf("") }
    var categoryId by remember(session.id) { mutableStateOf<Int?>(null) }
    var filter by remember(session.id) { mutableStateOf(LibraryFilter.ALL) }
    var sortMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var bulkMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val presentation = state.presentation.category(categoryId)
    val mangas = libraryOrder(state.mangas.filter { libraryMatches(it, search, categoryId, filter) }, presentation)
    val allLabel = stringResource(KMR.strings.suwayomi_all)
    val defaultLabel = stringResource(KMR.strings.suwayomi_uncategorized)
    val missingLabel = stringResource(KMR.strings.suwayomi_missing_source)
    val groups: List<Pair<String, List<ServerManga>>> = when (state.presentation.group) {
        LibraryGroup.NONE -> listOf("" to mangas)
        LibraryGroup.SOURCE -> mangas.groupBy { it.source?.let { source -> "${source.name} (${source.lang})" } ?: missingLabel }.toList()
        LibraryGroup.STATUS -> mangas.groupBy { it.status }.toList()
        LibraryGroup.CATEGORY -> buildList {
            state.categories.filter { showHidden || !state.presentation.category(it.id).hidden }.forEach { category ->
                val members = mangas.filter { libraryMatches(it, "", category.id, LibraryFilter.ALL) }
                if (members.isNotEmpty()) add((if (category.id == 0) defaultLabel else category.name) to members)
            }
        }
    }
    BackHandler(state.selected.isNotEmpty()) { model.clearSelection() }
    Column(modifier) {
        OutlinedTextField(search, { search = it }, label = { Text(stringResource(KMR.strings.suwayomi_search_library)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(categoryId != null, { categoryMenu = true }, label = { Text(state.categories.firstOrNull { it.id == categoryId }?.let { if (it.id == 0) defaultLabel else it.name } ?: allLabel) })
            DropdownMenu(categoryMenu, { categoryMenu = false }) {
                DropdownMenuItem(text = { Text(allLabel) }, onClick = {
                    categoryId = null
                    categoryMenu = false
                })
                state.categories.filter { showHidden || !state.presentation.category(it.id).hidden }.forEach { category ->
                    DropdownMenuItem(text = { Text(if (category.id == 0) defaultLabel else category.name) }, onClick = {
                        categoryId = category.id
                        categoryMenu = false
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_show_hidden)) }, trailingIcon = { Checkbox(showHidden, null) }, onClick = { showHidden = !showHidden })
            }
            LibraryFilter.entries.forEach { value -> FilterChip(filter == value, { filter = value }, label = { Text(filterLabel(value)) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { sortMenu = true }) { Text(sortLabel(presentation.sort)) }
            DropdownMenu(sortMenu, { sortMenu = false }) {
                LibrarySort.entries.forEach { sort ->
                    DropdownMenuItem(text = { Text(sortLabel(sort)) }, onClick = {
                        model.presentation(state.presentation.withCategory(categoryId, presentation.copy(sort = sort)))
                        sortMenu = false
                    })
                }
            }
            FilterChip(presentation.reverse, { model.presentation(state.presentation.withCategory(categoryId, presentation.copy(reverse = !presentation.reverse))) }, label = { Text(stringResource(KMR.strings.suwayomi_reverse)) })
            TextButton(onClick = { groupMenu = true }) { Text(groupLabel(state.presentation.group)) }
            DropdownMenu(groupMenu, { groupMenu = false }) {
                LibraryGroup.entries.forEach { group ->
                    DropdownMenuItem(text = { Text(groupLabel(group)) }, onClick = {
                        model.presentation(state.presentation.copy(group = group))
                        groupMenu = false
                    })
                }
            }
            FilterChip(presentation.grid, { model.presentation(state.presentation.withCategory(categoryId, presentation.copy(grid = !presentation.grid))) }, label = { Text(stringResource(KMR.strings.suwayomi_grid)) })
            if (categoryId != null) FilterChip(presentation.hidden, { model.presentation(state.presentation.withCategory(categoryId, presentation.copy(hidden = !presentation.hidden))) }, label = { Text(stringResource(KMR.strings.suwayomi_hide_category)) })
        }
        if (state.selected.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(KMR.strings.suwayomi_selected_count, state.selected.size), Modifier.padding(8.dp))
                TextButton(onClick = { model.selectVisible(mangas.map { it.id }.toSet()) }) { Text(stringResource(KMR.strings.suwayomi_select_visible)) }
                TextButton(onClick = { bulkMenu = true }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_actions)) }
                DropdownMenu(bulkMenu, { bulkMenu = false }) {
                    listOf("read", "unread", "remove").forEach { action ->
                        DropdownMenuItem(text = { Text(bulkLabel(action)) }, enabled = !incognito || action == "remove", onClick = {
                            confirmation = action
                            bulkMenu = false
                        })
                    }
                    state.categories.filter { it.id != 0 }.forEach { category ->
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_add_category_named, category.name)) }, onClick = {
                            model.bulk("categoryAdd", category.id)
                            bulkMenu = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_remove_category_named, category.name)) }, onClick = {
                            model.bulk("categoryRemove", category.id)
                            bulkMenu = false
                        })
                    }
                }
                TextButton(onClick = model::clearSelection) { Text(stringResource(KMR.strings.suwayomi_cancel)) }
            }
        } else {
            Text(stringResource(KMR.strings.suwayomi_library_count, mangas.size, state.total), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        }
        if (mangas.isEmpty() && !state.loading) Text(stringResource(KMR.strings.suwayomi_empty_library), Modifier.padding(24.dp))
        fun open(manga: ServerManga) {
            if (state.selected.isEmpty()) navigator.push(SuwayomiMangaScreen(session.id, manga.id)) else model.select(manga.id)
        }
        if (presentation.grid) {
            LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.forEachIndexed { groupIndex, (name, items) ->
                    if (name.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
                    items(items, key = { "$groupIndex:${it.id}" }) { manga ->
                        Card(Modifier.padding(horizontal = 6.dp).combinedClickable(onClick = { open(manga) }, onLongClick = { model.select(manga.id) })) {
                            ServerCover(session, manga.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.selected.isNotEmpty()) Checkbox(manga.id in state.selected, { model.select(manga.id) })
                                Text(manga.title, Modifier.weight(1f).padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(stringResource(KMR.strings.suwayomi_unread_count, manga.user.unreadCount), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
                            if (manga.user.downloadCount > 0) Text(stringResource(KMR.strings.suwayomi_server_download_count, manga.user.downloadCount), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                groups.forEachIndexed { groupIndex, (name, items) ->
                    if (name.isNotEmpty()) item { Text(name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
                    items(items, key = { "$groupIndex:${it.id}" }) { manga ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { open(manga) }, onLongClick = { model.select(manga.id) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.selected.isNotEmpty()) Checkbox(manga.id in state.selected, { model.select(manga.id) })
                            ServerCover(session, manga.thumbnailUrl, Modifier.width(48.dp).height(72.dp))
                            Text(manga.title, Modifier.weight(1f).padding(12.dp), maxLines = 2)
                            Text(manga.user.unreadCount.toString())
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(bulkLabel(action)) }, text = { Text(stringResource(KMR.strings.suwayomi_bulk_notice, state.selected.size)) }, confirmButton = {
            TextButton(onClick = {
                model.bulk(action)
                confirmation = null
            }) { Text(stringResource(KMR.strings.suwayomi_save)) }
        }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } })
    }
}

@Composable
private fun bulkLabel(action: String): String = stringResource(
    when (action) {
        "read" -> KMR.strings.suwayomi_bulk_read
        "unread" -> KMR.strings.suwayomi_bulk_unread
        else -> KMR.strings.suwayomi_remove_library
    },
)

@Composable
private fun filterLabel(value: LibraryFilter): String = stringResource(
    when (value) {
        LibraryFilter.ALL -> KMR.strings.suwayomi_all
        LibraryFilter.UNREAD -> KMR.strings.suwayomi_unread
        LibraryFilter.READ -> KMR.strings.suwayomi_read
        LibraryFilter.DOWNLOADED -> KMR.strings.suwayomi_server_downloads
        LibraryFilter.BOOKMARKED -> KMR.strings.suwayomi_bookmarked
    },
)

@Composable
private fun sortLabel(value: LibrarySort): String = stringResource(
    when (value) {
        LibrarySort.TITLE -> KMR.strings.suwayomi_sort_title
        LibrarySort.UNREAD -> KMR.strings.suwayomi_unread
        LibrarySort.DOWNLOADED -> KMR.strings.suwayomi_server_downloads
        LibrarySort.ADDED -> KMR.strings.suwayomi_sort_added
        LibrarySort.LAST_READ -> KMR.strings.suwayomi_recent_reading
    },
)

@Composable
private fun groupLabel(value: LibraryGroup): String = stringResource(
    when (value) {
        LibraryGroup.NONE -> KMR.strings.suwayomi_ungrouped
        LibraryGroup.CATEGORY -> KMR.strings.suwayomi_categories
        LibraryGroup.SOURCE -> KMR.strings.suwayomi_sources
        LibraryGroup.STATUS -> KMR.strings.suwayomi_group_status
    },
)
// KMK <--
