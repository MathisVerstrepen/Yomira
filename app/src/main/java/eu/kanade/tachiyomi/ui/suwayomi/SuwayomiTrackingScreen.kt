// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerTrackRecord
import eu.kanade.tachiyomi.data.suwayomi.ServerTrackSearch
import eu.kanade.tachiyomi.data.suwayomi.ServerTracker
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.bindTrack
import eu.kanade.tachiyomi.data.suwayomi.mangaTracks
import eu.kanade.tachiyomi.data.suwayomi.searchTracker
import eu.kanade.tachiyomi.data.suwayomi.serverTrackers
import eu.kanade.tachiyomi.data.suwayomi.trackerLogin
import eu.kanade.tachiyomi.data.suwayomi.unbindTrack
import eu.kanade.tachiyomi.data.suwayomi.updateTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiTrackingModel(private val sessionId: String, private val mangaId: Int?) : StateScreenModel<SuwayomiTrackingModel.State>(State()) {
    private val backend = Injekt.get<SuwayomiBackend>()
    private val mutex = Mutex()
    private val refreshTask = SuwayomiRefreshTask()
    fun load() = refreshTask.launch(screenModelScope) { change(visible = false) {}.join() }
    fun login(tracker: ServerTracker, username: String, password: String, callback: String?) = change(writes = true) { backend.requireSession(sessionId).trackerLogin(tracker.id, username, password, callback) }
    fun search(tracker: ServerTracker, query: String) = change {
        val results = backend.requireSession(sessionId).searchTracker(tracker.id, query)
        mutableState.update { it.copy(searches = results, tracker = tracker) }
    }
    fun bind(result: ServerTrackSearch) = change(writes = true) {
        backend.requireSession(sessionId).bindTrack(mangaId!!, state.value.tracker!!.id, result.remoteId)
        mutableState.update { it.copy(searches = emptyList()) }
    }
    fun unbind(record: ServerTrackRecord) = change(writes = true) { backend.requireSession(sessionId).unbindTrack(record.id) }
    fun update(record: ServerTrackRecord, chapter: Double, status: Int, score: String) = change(writes = true) { backend.requireSession(sessionId).updateTrack(record.id, chapter, status, score) }
    private fun change(writes: Boolean = false, visible: Boolean = true, action: suspend () -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            if (writes && Injekt.get<eu.kanade.domain.base.BasePreferences>().incognitoMode().get()) return@withLock
            mutableState.update { it.copy(loading = visible || !it.hasLoaded, error = null) }
            try {
                action()
                val session = backend.requireSession(sessionId)
                val trackers = session.serverTrackers()
                val records = mangaId?.let { session.mangaTracks(it) }.orEmpty()
                mutableState.update { it.copy(loading = false, hasLoaded = true, trackers = trackers, records = records, sharedLibrary = session.sharedLibrary) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    data class State(val trackers: List<ServerTracker> = emptyList(), val records: List<ServerTrackRecord> = emptyList(), val searches: List<ServerTrackSearch> = emptyList(), val tracker: ServerTracker? = null, val loading: Boolean = true, val hasLoaded: Boolean = false, val error: SuwayomiFailure? = null, val sharedLibrary: Boolean = false)
}

class SuwayomiTrackingScreen(private val sessionId: String, private val mangaId: Int? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = navigator.lastItem.rememberScreenModel(tag = "server-track-$sessionId-$mangaId") { SuwayomiTrackingModel(sessionId, mangaId) }
        val state by model.state.collectAsState()
        val context = LocalContext.current
        val snackbar = rememberRefreshErrors(state.error, state.hasLoaded) { model.load() }
        val incognito by remember { Injekt.get<eu.kanade.domain.base.BasePreferences>().incognitoMode() }.collectAsState()
        var login by remember { mutableStateOf<ServerTracker?>(null) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var callback by remember { mutableStateOf("") }
        var searching by remember { mutableStateOf<ServerTracker?>(null) }
        var query by remember { mutableStateOf("") }
        var editing by remember { mutableStateOf<ServerTrackRecord?>(null) }
        var chapter by remember { mutableStateOf("") }
        var score by remember { mutableStateOf("") }
        var status by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { model.load() }
        Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Text(stringResource(KMR.strings.suwayomi_tracking)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                RefreshProgress(state.loading)
                if (!state.hasLoaded) state.error?.let { Text(failureText(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (incognito) Text(stringResource(KMR.strings.suwayomi_incognito_notice), Modifier.padding(16.dp))
                Text(stringResource(if (state.sharedLibrary) KMR.strings.suwayomi_shared_tracking_notice else KMR.strings.suwayomi_tracking_notice), Modifier.padding(16.dp))
                TextButton(onClick = { model.load() }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_reload)) }
                LazyColumn {
                    items(state.records, key = { "record-${it.id}" }) { record ->
                        Column(Modifier.padding(16.dp)) {
                            Text(record.title, style = MaterialTheme.typography.titleMedium)
                            Text("${state.trackers.find { it.id == record.trackerId }?.name.orEmpty()} · ${record.lastChapterRead} · ${record.displayScore}")
                            Row {
                                TextButton(onClick = {
                                    editing = record
                                    chapter = record.lastChapterRead.toString()
                                    status = record.status
                                    score = record.displayScore
                                }, enabled = !state.loading && !incognito) { Text(stringResource(KMR.strings.suwayomi_edit_track)) }
                                TextButton(onClick = { model.unbind(record) }, enabled = !state.loading && !incognito) { Text(stringResource(KMR.strings.suwayomi_unbind)) }
                            }
                        }
                    }
                    items(state.trackers, key = { "tracker-${it.id}" }) { tracker ->
                        Row(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(tracker.name, Modifier.weight(1f))
                            if (!tracker.isLoggedIn) {
                                TextButton(onClick = {
                                    login = tracker
                                    username = ""
                                    password = ""
                                    callback = ""
                                }, enabled = !state.loading && !incognito) { Text(stringResource(KMR.strings.suwayomi_sign_in)) }
                            } else if (mangaId != null) {
                                TextButton(onClick = {
                                    searching = tracker
                                    query = ""
                                }, enabled = !state.loading && !incognito) { Text(stringResource(KMR.strings.suwayomi_bind)) }
                            } else {
                                Text(stringResource(KMR.strings.suwayomi_signed_in))
                            }
                        }
                    }
                    items(state.searches, key = { "search-${it.remoteId}" }) { result ->
                        Row(Modifier.padding(12.dp)) {
                            Text(result.title, Modifier.weight(1f))
                            Button(onClick = { model.bind(result) }, enabled = !state.loading && !incognito) { Text(stringResource(KMR.strings.suwayomi_bind)) }
                        }
                    }
                }
            }
        }
        login?.let { tracker ->
            AlertDialog(
                onDismissRequest = {
                    login = null
                    password = ""
                },
                title = { Text(tracker.name) },
                text = {
                    Column {
                        if (tracker.authUrl != null) {
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, tracker.authUrl.toUri())) }) { Text(stringResource(KMR.strings.suwayomi_authorize)) }
                            Text(stringResource(KMR.strings.suwayomi_callback_notice))
                            OutlinedTextField(callback, { callback = it }, label = { Text(stringResource(KMR.strings.suwayomi_callback)) })
                        } else {
                            OutlinedTextField(username, { username = it }, label = { Text(stringResource(KMR.strings.suwayomi_username)) })
                            OutlinedTextField(password, { password = it }, visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(KMR.strings.suwayomi_password)) })
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        model.login(tracker, username, password, if (tracker.authUrl != null) callback else null)
                        login = null
                        password = ""
                    }, enabled = !incognito) { Text(stringResource(KMR.strings.suwayomi_sign_in)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        login = null
                        password = ""
                    }) { Text(stringResource(KMR.strings.suwayomi_cancel)) }
                },
            )
        }
        searching?.let { tracker ->
            AlertDialog(
                onDismissRequest = { searching = null },
                title = { Text(tracker.name) },
                text = { OutlinedTextField(query, { query = it }, label = { Text(stringResource(KMR.strings.suwayomi_search)) }) },
                confirmButton = {
                    TextButton(onClick = {
                        model.search(tracker, query)
                        searching = null
                    }, enabled = query.isNotBlank()) { Text(stringResource(KMR.strings.suwayomi_search)) }
                },
                dismissButton = { TextButton(onClick = { searching = null }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
            )
        }
        editing?.let { record ->
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(stringResource(KMR.strings.suwayomi_edit_track)) },
                text = {
                    Column {
                        OutlinedTextField(chapter, { chapter = it }, label = { Text(stringResource(KMR.strings.suwayomi_last_chapter)) })
                        OutlinedTextField(score, { score = it }, label = { Text(stringResource(KMR.strings.suwayomi_score)) })
                        state.trackers.find { it.id == record.trackerId }?.statuses?.forEach { option ->
                            TextButton(onClick = { status = option.value }) { Text((if (status == option.value) "✓ " else "") + option.name) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        model.update(record, chapter.toDouble(), status, score)
                        editing = null
                    }, enabled = !incognito && chapter.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true) { Text(stringResource(KMR.strings.suwayomi_save)) }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
            )
        }
    }
}
// KMK <--
