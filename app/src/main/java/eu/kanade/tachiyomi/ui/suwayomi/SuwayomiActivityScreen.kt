// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerActivityChapter
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.activityPage
import eu.kanade.tachiyomi.data.suwayomi.coverUrl
import eu.kanade.tachiyomi.data.suwayomi.updateLibraryOnServer
import eu.kanade.tachiyomi.data.suwayomi.updateRunning
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiActivityModel(private val sessionId: String, private val history: Boolean) : StateScreenModel<SuwayomiActivityModel.State>(State()) {
    private val backend = Injekt.get<SuwayomiBackend>()
    private val mutex = Mutex()
    fun refresh(next: Boolean = false, fetch: Boolean = false) = screenModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val session = backend.requireSession(sessionId)
                if (fetch) session.updateLibraryOnServer()
                val page = session.activityPage(history, if (next) state.value.cursor else null)
                val running = if (history) false else session.updateRunning()
                mutableState.update {
                    it.copy(
                        chapters = ((if (next) it.chapters else emptyList()) + page.nodes).distinctBy(ServerActivityChapter::id),
                        cursor = page.pageInfo.endCursor,
                        hasNext = page.pageInfo.hasNextPage,
                        loading = false,
                        updating = running,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    data class State(val chapters: List<ServerActivityChapter> = emptyList(), val cursor: String? = null, val hasNext: Boolean = false, val loading: Boolean = true, val updating: Boolean = false, val error: SuwayomiFailure? = null)
}

class SuwayomiActivityScreen(private val sessionId: String, private val history: Boolean) : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiActivityModel(sessionId, history) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val backend = remember { Injekt.get<SuwayomiBackend>() }
        val current by backend.session.collectAsState()
        val session = current?.takeIf { it.id == sessionId }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
        // The server lacks general chapter change events. Bound polling to a visible active update.
        LaunchedEffect(state.updating) {
            if (state.updating) {
                repeat(60) {
                    delay(5000)
                    model.refresh()
                }
            }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(if (history) KMR.strings.suwayomi_recent_reading else KMR.strings.suwayomi_updates)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (session == null) {
                    Text(stringResource(KMR.strings.suwayomi_session_changed))
                    return@Column
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(failureText(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(onClick = { model.refresh() }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_reload)) }
                    if (!history) Button(onClick = { model.refresh(fetch = true) }, enabled = !state.loading && !state.updating) { Text(stringResource(KMR.strings.suwayomi_update_library)) }
                }
                if (state.updating) Text(stringResource(KMR.strings.suwayomi_updating), Modifier.padding(16.dp))
                if (history) Text(stringResource(KMR.strings.suwayomi_history_notice), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f)) {
                    items(state.chapters, key = { it.id }) { chapter ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (backend.session.value?.id == sessionId) context.startActivity(ReaderActivity.newIntent(context, SuwayomiMode.readerId(chapter.manga.id), SuwayomiMode.readerId(chapter.id)))
                            }.padding(12.dp),
                        ) {
                            ServerCover(session, chapter.manga.coverUrl(), Modifier.size(56.dp, 84.dp))
                            Column(Modifier.padding(horizontal = 12.dp)) {
                                Text(chapter.manga.title, style = MaterialTheme.typography.titleMedium)
                                Text(chapter.name)
                                if (history) Text(stringResource(KMR.strings.suwayomi_page, chapter.user.lastPageRead + 1))
                            }
                        }
                    }
                }
                if (state.hasNext) TextButton(onClick = { model.refresh(next = true) }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_load_more)) }
            }
        }
    }
}
// KMK <--
