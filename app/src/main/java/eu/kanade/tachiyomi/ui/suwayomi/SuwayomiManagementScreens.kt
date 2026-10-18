// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerCategory
import eu.kanade.tachiyomi.data.suwayomi.ServerDownloads
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.categoryMutation
import eu.kanade.tachiyomi.data.suwayomi.downloadAction
import eu.kanade.tachiyomi.data.suwayomi.mangaCategories
import eu.kanade.tachiyomi.data.suwayomi.serverCategories
import eu.kanade.tachiyomi.data.suwayomi.serverDownloads
import eu.kanade.tachiyomi.data.suwayomi.setCategoryMembership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiCategoriesModel(private val sessionId: String, private val mangaId: Int?) : StateScreenModel<SuwayomiCategoriesModel.State>(State()) {
    private val mutex = Mutex()
    private val backend = Injekt.get<SuwayomiBackend>()
    fun load() = change {}
    fun edit(action: String, id: Int? = null, name: String? = null, position: Int? = null) = change { backend.requireSession(sessionId).categoryMutation(action, id, name, position) }
    fun membership(categoryId: Int, member: Boolean) = change { backend.requireSession(sessionId).setCategoryMembership(mangaId!!, categoryId, member) }
    private fun change(action: suspend () -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                action()
                val session = backend.requireSession(sessionId)
                val categories = session.serverCategories()
                val selected = mangaId?.let { session.mangaCategories(it).map(ServerCategory::id).toSet() }.orEmpty()
                mutableState.value = State(categories, selected, loading = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    data class State(val categories: List<ServerCategory> = emptyList(), val selected: Set<Int> = emptySet(), val loading: Boolean = true, val error: SuwayomiFailure? = null)
}

class SuwayomiCategoriesScreen(private val sessionId: String, private val mangaId: Int? = null) : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiCategoriesModel(sessionId, mangaId) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var edit by remember { mutableStateOf<ServerCategory?>(null) }
        var creating by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var deleting by remember { mutableStateOf<ServerCategory?>(null) }
        LaunchedEffect(Unit) { model.load() }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(KMR.strings.suwayomi_categories)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            }, actions = {
                TextButton(onClick = {
                    name = ""
                    creating = true
                }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_create)) }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let {
                    Text(failureText(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.load() }) { Text(stringResource(KMR.strings.suwayomi_reload)) }
                }
                LazyColumn {
                    items(state.categories, key = { it.id }) { category ->
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row {
                                if (mangaId != null && category.id != 0) Checkbox(category.id in state.selected, { model.membership(category.id, it) }, enabled = !state.loading)
                                Text(category.name, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleMedium)
                            }
                            if (category.id != 0 && mangaId == null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        edit = category
                                        name = category.name
                                    }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_rename)) }
                                    TextButton(onClick = { model.edit("reorder", category.id, position = (category.order - 1).coerceAtLeast(1)) }, enabled = !state.loading && category.order > 1) { Text(stringResource(KMR.strings.suwayomi_move_up)) }
                                    TextButton(onClick = { deleting = category }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_delete)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (creating || edit != null) {
            AlertDialog(
                onDismissRequest = {
                    creating = false
                    edit = null
                },
                title = { Text(stringResource(KMR.strings.suwayomi_categories)) },
                text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(KMR.strings.suwayomi_name)) }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        model.edit(if (creating) "create" else "rename", edit?.id, name.trim())
                        creating = false
                        edit = null
                    }, enabled = name.isNotBlank()) { Text(stringResource(KMR.strings.suwayomi_save)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        creating = false
                        edit = null
                    }) { Text(stringResource(KMR.strings.suwayomi_cancel)) }
                },
            )
        }
        deleting?.let { category ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(KMR.strings.suwayomi_delete_category)) },
                text = { Text(category.name) },
                confirmButton = {
                    TextButton(onClick = {
                        model.edit("delete", category.id)
                        deleting = null
                    }) { Text(stringResource(KMR.strings.suwayomi_delete)) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
            )
        }
    }
}

class SuwayomiDownloadsModel(private val sessionId: String) : StateScreenModel<SuwayomiDownloadsModel.State>(State()) {
    private val backend = Injekt.get<SuwayomiBackend>()
    private val mutex = Mutex()
    fun load() = screenModelScope.launch(Dispatchers.IO) { refresh() }
    fun action(action: String, chapterId: Int? = null) = screenModelScope.launch(Dispatchers.IO) {
        try {
            backend.requireSession(sessionId).downloadAction(action, chapterId)
            refresh()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(error = failure(e)) }
        }
    }
    suspend fun observe() {
        backend.requireSession(sessionId).downloadEvents()
            .retryWhen { _, attempt ->
                if (attempt >= 3) {
                    false
                } else {
                    delay(2000L * (attempt + 1))
                    true
                }
            }.collect { refresh() }
    }
    private suspend fun refresh() = mutex.withLock {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val session = backend.requireSession(sessionId)
            val user = session.user()
            mutableState.value = State(session.serverDownloads(), user.roles.contains("ADMIN") || user.permissions.contains("DOWNLOAD_CHAPTERS"), loading = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }
    data class State(val downloads: ServerDownloads? = null, val canDownload: Boolean = false, val loading: Boolean = true, val error: SuwayomiFailure? = null)
}

class SuwayomiDownloadsScreen(private val sessionId: String) : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiDownloadsModel(sessionId) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(Unit) {
            model.load()
            try {
                model.observe()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                model.load()
            }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(KMR.strings.suwayomi_server_downloads)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(failureText(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(KMR.strings.suwayomi_download_notice), Modifier.padding(16.dp))
                Row {
                    TextButton(onClick = { model.load() }) { Text(stringResource(KMR.strings.suwayomi_reload)) }
                    Button(onClick = { model.action("start") }, enabled = state.canDownload) { Text(stringResource(KMR.strings.suwayomi_start_queue)) }
                    TextButton(onClick = { model.action("stop") }, enabled = state.canDownload) { Text(stringResource(KMR.strings.suwayomi_stop_queue)) }
                }
                state.downloads?.let { downloads ->
                    Text(downloads.state, Modifier.padding(16.dp))
                    LazyColumn {
                        items(downloads.queue, key = { it.chapter.id }) { item ->
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(item.manga.title, style = MaterialTheme.typography.titleMedium)
                                Text("${item.chapter.name} · ${item.state} · ${(item.progress * 100).toInt()}%")
                                TextButton(onClick = { model.action("cancel", item.chapter.id) }, enabled = state.canDownload) { Text(stringResource(KMR.strings.suwayomi_cancel_server_download)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
// KMK <--
