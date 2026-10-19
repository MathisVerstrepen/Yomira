// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        val scope = rememberCoroutineScope()
        var refreshing by remember { mutableStateOf(false) }
        var loginFailure by remember { mutableStateOf<SuwayomiFailure?>(null) }
        var loginRejected by remember { mutableStateOf(false) }
        fun reload() {
            scope.launch {
                refreshing = true
                try {
                    model.load().join()
                } finally {
                    refreshing = false
                }
            }
        }
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
        val busy = state.loading || refreshing
        Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Text(stringResource(KMR.strings.suwayomi_tracking)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            }, actions = {
                IconButton(onClick = ::reload, enabled = !busy) { Icon(Icons.Filled.Refresh, stringResource(KMR.strings.suwayomi_reload)) }
            })
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp)) {
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    if (!state.hasLoaded) {
                        state.error?.let { error ->
                            item { Text(failureText(error), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    if (incognito) item { Text(stringResource(KMR.strings.suwayomi_incognito_notice), style = MaterialTheme.typography.bodySmall) }
                    if (state.hasLoaded) {
                        item {
                            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Text(
                                    stringResource(if (state.sharedLibrary) KMR.strings.suwayomi_tracker_shared_summary else KMR.strings.suwayomi_tracker_account_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(state.records, key = { "record-${it.id}" }) { record ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ServerTrackerLogo(state.trackers.find { it.id == record.trackerId }?.name.orEmpty())
                                    Column(Modifier.weight(1f)) {
                                        Text(record.title, style = MaterialTheme.typography.titleMedium)
                                        Text("${state.trackers.find { it.id == record.trackerId }?.name.orEmpty()} · ${record.lastChapterRead} · ${record.displayScore}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Row {
                                    TextButton(onClick = {
                                        editing = record
                                        chapter = record.lastChapterRead.toString()
                                        status = record.status
                                        score = record.displayScore
                                    }, enabled = !busy && !incognito) { Text(stringResource(KMR.strings.suwayomi_edit_track)) }
                                    TextButton(onClick = { model.unbind(record) }, enabled = !busy && !incognito) { Text(stringResource(KMR.strings.suwayomi_unbind)) }
                                }
                            }
                        }
                    }
                    if (state.trackers.isNotEmpty()) {
                        item(key = "services") {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column {
                                    state.trackers.forEachIndexed { index, tracker ->
                                        if (index > 0) HorizontalDivider(Modifier.padding(start = 76.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                        ServerTrackerRow(tracker, canBind = mangaId != null, enabled = !busy && !incognito) {
                                            if (!tracker.isLoggedIn) {
                                                login = tracker
                                                username = ""
                                                password = ""
                                                callback = ""
                                                loginFailure = null
                                                loginRejected = false
                                            } else {
                                                searching = tracker
                                                query = ""
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.hasLoaded && state.trackers.isEmpty()) {
                        item {
                            Text(stringResource(KMR.strings.suwayomi_tracker_empty), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(state.searches, key = { "search-${it.remoteId}" }) { result ->
                        Row(Modifier.padding(12.dp)) {
                            Text(result.title, Modifier.weight(1f))
                            Button(onClick = { model.bind(result) }, enabled = !busy && !incognito) { Text(stringResource(KMR.strings.suwayomi_bind)) }
                        }
                    }
                    item { Box(Modifier.height(4.dp)) }
                }
            }
        }
        login?.let { tracker ->
            ServerTrackerLoginDialog(
                tracker = tracker, username = username, password = password, callback = callback,
                busy = busy || incognito,
                error = when {
                    loginRejected || loginFailure == SuwayomiFailure.AUTHENTICATION_REQUIRED -> stringResource(KMR.strings.suwayomi_tracker_login_failed)
                    loginFailure != null -> failureText(loginFailure!!)
                    else -> null
                },
                onUsername = {
                    username = it
                    loginFailure = null
                    loginRejected = false
                },
                onPassword = {
                    password = it
                    loginFailure = null
                    loginRejected = false
                },
                onCallback = {
                    callback = it
                    loginFailure = null
                    loginRejected = false
                },
                onAuthorize = { context.startActivity(Intent(Intent.ACTION_VIEW, tracker.authUrl!!.toUri())) },
                onSubmit = {
                    val submittedUsername = username.trim()
                    val submittedPassword = password
                    val submittedCallback = if (tracker.authUrl != null) callback.trim() else null
                    scope.launch {
                        loginFailure = null
                        loginRejected = false
                        model.login(tracker, submittedUsername, submittedPassword, submittedCallback).join()
                        val result = model.state.value
                        if (result.error == null && result.trackers.any { it.id == tracker.id && it.isLoggedIn }) {
                            login = null
                            password = ""
                            callback = ""
                        } else {
                            loginFailure = result.error
                            loginRejected = result.error == null
                        }
                    }
                },
                onDismiss = {
                    login = null
                    password = ""
                    callback = ""
                },
            )
        }
        searching?.let { tracker ->
            AlertDialog(
                onDismissRequest = { searching = null },
                icon = { ServerTrackerLogo(tracker.name) },
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
