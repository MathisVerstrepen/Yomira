// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.imageLoader
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerPreference
import eu.kanade.tachiyomi.data.suwayomi.ServerUser
import eu.kanade.tachiyomi.data.suwayomi.ServerUserOptions
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiNotificationWorker
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import eu.kanade.tachiyomi.data.suwayomi.allowed
import eu.kanade.tachiyomi.data.suwayomi.changeUserOption
import eu.kanade.tachiyomi.data.suwayomi.setUpdateInterval
import eu.kanade.tachiyomi.data.suwayomi.sourcePreferences
import eu.kanade.tachiyomi.data.suwayomi.updateInterval
import eu.kanade.tachiyomi.data.suwayomi.userOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiAdministrationModel(private val sessionId: String, private val sourceId: Long?) : StateScreenModel<SuwayomiAdministrationModel.State>(State()) {
    private val mutex = Mutex()
    private val refreshTask = SuwayomiRefreshTask()
    fun load() = refreshTask.launch(screenModelScope) { change(false) {}.join() }
    fun load(action: suspend SuwayomiSession.() -> Unit) = change(true, action)
    private fun change(visible: Boolean, action: suspend SuwayomiSession.() -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(loading = visible || !it.hasLoaded, error = null) }
            try {
                val session = Injekt.get<SuwayomiBackend>().requireSession(sessionId)
                session.action()
                val user = session.user()
                mutableState.value = if (sourceId != null) {
                    State(user = user, preferences = if (user.allowed("MANAGE_SOURCE_PREFERENCES")) session.sourcePreferences(sourceId) else emptyList(), loading = false, hasLoaded = true, sharedLibrary = session.sharedLibrary)
                } else {
                    State(user = user, options = session.userOptions(), interval = if (user.allowed("MANAGE_SETTINGS")) session.updateInterval() else null, loading = false, hasLoaded = true, sharedLibrary = session.sharedLibrary)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    data class State(val user: ServerUser? = null, val preferences: List<ServerPreference> = emptyList(), val options: ServerUserOptions? = null, val interval: Double? = null, val loading: Boolean = true, val hasLoaded: Boolean = false, val error: SuwayomiFailure? = null, val sharedLibrary: Boolean = false)
}

class SuwayomiAdministrationScreen(private val sessionId: String, private val sourceId: Long? = null) : Screen() {
    @Composable
    override fun Content() {
        if (sourceId != null) {
            SuwayomiSourcePreferencesScreen(sessionId, sourceId).Content()
            return
        }
        val navigator = LocalNavigator.currentOrThrow
        val model = navigator.lastItem.rememberScreenModel(tag = "server-admin-$sessionId-$sourceId") { SuwayomiAdministrationModel(sessionId, sourceId) }
        val state by model.state.collectAsState()
        val context = LocalContext.current
        val snackbar = rememberRefreshErrors(state.error, state.hasLoaded) { model.load() }
        var background by remember { mutableStateOf(SuwayomiNotificationWorker.enabled(context)) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            background = granted
            SuwayomiNotificationWorker.setEnabled(context, granted, sessionId)
        }
        var interval by remember(state.interval) { mutableStateOf(state.interval?.toString().orEmpty()) }
        LaunchedEffect(Unit) { model.load() }
        Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Text(stringResource(if (sourceId == null) KMR.strings.suwayomi_server_settings else KMR.strings.suwayomi_source_preferences)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            }, actions = { TextButton(onClick = { model.load() }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_reload)) } })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                RefreshProgress(state.loading)
                if (!state.hasLoaded) state.error?.let { Text(failureText(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(KMR.strings.suwayomi_background_notice))
                    Row {
                        Checkbox(background, { checked ->
                            if (checked && android.os.Build.VERSION.SDK_INT >= 33 && !SuwayomiNotificationWorker.canNotify(context)) {
                                permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                background = checked
                                SuwayomiNotificationWorker.setEnabled(context, checked, sessionId)
                            }
                        })
                        Text(stringResource(KMR.strings.suwayomi_background_checks), Modifier.padding(top = 12.dp))
                    }
                    Text(stringResource(if (state.sharedLibrary) KMR.strings.suwayomi_shared_settings_notice else KMR.strings.suwayomi_user_options_notice))
                    state.options?.let { options ->
                        val fields = listOf(
                            Triple("excludeUnreadChapters", options.excludeUnreadChapters, KMR.strings.suwayomi_exclude_unread),
                            Triple("excludeNotStarted", options.excludeNotStarted, KMR.strings.suwayomi_exclude_not_started),
                            Triple("excludeCompleted", options.excludeCompleted, KMR.strings.suwayomi_exclude_completed),
                            Triple("updateMangas", options.updateMangas, KMR.strings.suwayomi_update_details),
                            Triple("autoDownloadNewChapters", options.autoDownloadNewChapters, KMR.strings.suwayomi_auto_download),
                            Triple("excludeEntryWithUnreadChapters", options.excludeEntryWithUnreadChapters, KMR.strings.suwayomi_download_exclude_unread),
                            Triple("autoDownloadIgnoreReUploads", options.autoDownloadIgnoreReUploads, KMR.strings.suwayomi_ignore_reuploads),
                        )
                        fields.forEach { (field, value, label) ->
                            Row {
                                Checkbox(value, { model.load { changeUserOption(field, JsonPrimitive(it)) } }, enabled = !state.loading && (!field.contains("Download") || state.user?.allowed("DOWNLOAD_CHAPTERS") == true))
                                Text(stringResource(label), Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    if (state.interval != null) {
                        Text(stringResource(KMR.strings.suwayomi_global_schedule_notice))
                        OutlinedTextField(interval, { interval = it }, label = { Text(stringResource(KMR.strings.suwayomi_update_hours)) }, singleLine = true)
                        TextButton(onClick = { interval.toDoubleOrNull()?.let { model.load { setUpdateInterval(it) } } }, enabled = !state.loading && interval.toDoubleOrNull()?.let { it == 0.0 || (it >= 6 && it.isFinite()) } == true) { Text(stringResource(KMR.strings.suwayomi_save)) }
                    }
                    TextButton(onClick = {
                        model.load {
                            pageCache.deleteRecursively()
                            context.imageLoader.memoryCache?.clear()
                            context.imageLoader.diskCache?.clear()
                        }
                    }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_clear_android_cache)) }
                    Text(stringResource(KMR.strings.suwayomi_webui_admin_notice))
                    TextButton(onClick = {
                        val session = Injekt.get<SuwayomiBackend>().requireSession(sessionId)
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(session.baseUrl)))
                    }) { Text(stringResource(KMR.strings.suwayomi_webui)) }
                }
            }
        }
    }
}

// KMK <--
