// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerFilter
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.sourceFilterChange
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiBrowseScreen(private val sessionId: String) : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiBrowseScreenModel(sessionId) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val backend = remember { Injekt.get<SuwayomiBackend>() }
        val connection by backend.session.collectAsState()
        val session = connection?.takeIf { it.id == sessionId }
        var query by remember { mutableStateOf("") }
        var filtersVisible by remember { mutableStateOf(false) }
        val changes = remember(state.source?.id, state.filters) { mutableStateMapOf<List<Int>, JsonObject>() }
        LaunchedEffect(Unit) { model.sources() }
        Scaffold(topBar = {
            TopAppBar(title = { Text(state.source?.name ?: stringResource(KMR.strings.suwayomi_sources)) }, navigationIcon = {
                IconButton(onClick = { if (state.source != null) model.sources() else navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (session == null) {
                    Text(stringResource(KMR.strings.suwayomi_session_changed))
                    return@Column
                }
                state.error?.let { Text(failureText(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val source = state.source
                if (source == null) {
                    TextButton(onClick = { model.sources() }) { Text(stringResource(KMR.strings.suwayomi_reload)) }
                    LazyColumn {
                        items(state.sources.sortedWith(compareBy({ it.lang }, { it.name })), key = { it.id }) { source ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    query = ""
                                    model.select(source)
                                }.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ServerCover(session, source.iconUrl, Modifier.size(48.dp))
                                Column {
                                    Text(source.name)
                                    Text(source.lang, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(KMR.strings.suwayomi_source_search)) }, modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true)
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.search(query, "SEARCH", changes.values.toList()) }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_search)) }
                        TextButton(onClick = { model.search("", "POPULAR", emptyList()) }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_popular)) }
                        if (source.supportsLatest) TextButton(onClick = { model.search("", "LATEST", emptyList()) }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_latest)) }
                    }
                    if (state.filters.isNotEmpty()) TextButton(onClick = { filtersVisible = true }) { Text(stringResource(KMR.strings.suwayomi_filters)) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.mangas, key = { it.id }) { manga ->
                            Row(Modifier.fillMaxWidth().clickable { navigator.push(SuwayomiMangaScreen(sessionId, manga.id)) }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                ServerCover(session, manga.thumbnailUrl, Modifier.size(64.dp, 96.dp))
                                Text(manga.title)
                            }
                        }
                    }
                    if (state.hasNext) TextButton(onClick = { model.next() }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_load_more)) }
                }
            }
        }
        if (filtersVisible) {
            AlertDialog(
                onDismissRequest = { filtersVisible = false },
                title = { Text(stringResource(KMR.strings.suwayomi_filters)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SourceFilters(state.filters, drafts = changes) { path, field, value -> changes[path] = sourceFilterChange(path, field, value) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        filtersVisible = false
                        model.search(query, "SEARCH", changes.values.toList())
                    }) { Text(stringResource(KMR.strings.suwayomi_search)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        filtersVisible = false
                        state.source?.let(model::select)
                    }) { Text(stringResource(KMR.strings.suwayomi_reset_filters)) }
                },
            )
        }
    }
}

@Composable
private fun SourceFilters(filters: List<ServerFilter>, parent: List<Int> = emptyList(), drafts: Map<List<Int>, JsonObject>, change: (List<Int>, String, JsonElement) -> Unit) {
    filters.forEachIndexed { index, filter ->
        val path = parent + index
        val draft = drafts[path]?.let { root ->
            var leaf = root
            while (leaf["groupChange"] != null) leaf = leaf.getValue("groupChange").jsonObject
            leaf
        }
        when (filter.type) {
            "TextFilter" -> {
                var value by remember(filter, draft) { mutableStateOf(draft?.get("textState")?.jsonPrimitive?.content ?: filter.text.orEmpty()) }
                OutlinedTextField(value, {
                    value = it
                    change(path, "textState", JsonPrimitive(it))
                }, label = { Text(filter.name) }, modifier = Modifier.fillMaxWidth())
            }
            "CheckBoxFilter" -> {
                var checked by remember(filter, draft) { mutableStateOf(draft?.get("checkBoxState")?.jsonPrimitive?.boolean ?: filter.checked ?: false) }
                Row {
                    Checkbox(checked, {
                        checked = it
                        change(path, "checkBoxState", JsonPrimitive(it))
                    })
                    Text(filter.name)
                }
            }
            "SelectFilter", "TriStateFilter", "SortFilter" -> {
                var expanded by remember { mutableStateOf(false) }
                val triStates = listOf("IGNORE", "INCLUDE", "EXCLUDE")
                val labels = if (filter.type == "TriStateFilter") listOf(stringResource(KMR.strings.suwayomi_filter_any), stringResource(KMR.strings.suwayomi_filter_include), stringResource(KMR.strings.suwayomi_filter_exclude)) else filter.values
                var selected by remember(filter, draft) {
                    mutableStateOf(
                        draft?.get("selectState")?.jsonPrimitive?.int
                            ?: draft?.get("sortState")?.jsonObject?.get("index")?.jsonPrimitive?.int
                            ?: draft?.get("triState")?.jsonPrimitive?.content?.let(triStates::indexOf)
                            ?: filter.selected ?: filter.sort?.index ?: triStates.indexOf(filter.triState).coerceAtLeast(0),
                    )
                }
                var ascending by remember(filter, draft) { mutableStateOf(draft?.get("sortState")?.jsonObject?.get("ascending")?.jsonPrimitive?.boolean ?: filter.sort?.ascending ?: true) }
                fun update() {
                    val (field, value) = when (filter.type) {
                        "TriStateFilter" -> "triState" to JsonPrimitive(triStates[selected])
                        "SortFilter" -> "sortState" to buildJsonObject {
                            put("index", selected)
                            put("ascending", ascending)
                        }
                        else -> "selectState" to JsonPrimitive(selected)
                    }
                    change(path, field, value)
                }
                Box {
                    TextButton(onClick = { expanded = true }) { Text("${filter.name}: ${labels.getOrNull(selected).orEmpty()}") }
                    DropdownMenu(expanded, { expanded = false }) {
                        labels.forEachIndexed { i, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                selected = i
                                expanded = false
                                update()
                            })
                        }
                    }
                }
                if (filter.type == "SortFilter") {
                    Row {
                        Checkbox(ascending, {
                            ascending = it
                            update()
                        })
                        Text(stringResource(KMR.strings.suwayomi_sort_ascending))
                    }
                }
            }
            "GroupFilter" -> {
                Text(filter.name, style = MaterialTheme.typography.titleSmall)
                if (filter.filters.isEmpty()) Text(stringResource(KMR.strings.suwayomi_filter_unavailable)) else SourceFilters(filter.filters, path, drafts, change)
            }
            "HeaderFilter" -> Text(filter.name, style = MaterialTheme.typography.titleMedium)
            "SeparatorFilter" -> HorizontalDivider()
            else -> Text(stringResource(KMR.strings.suwayomi_filter_unavailable))
        }
    }
}
// KMK <--
