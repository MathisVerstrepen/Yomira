// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import tachiyomi.core.common.i18n.stringResource
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
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var background by remember { mutableStateOf(SuwayomiNotificationWorker.enabled(context)) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            background = granted
            SuwayomiNotificationWorker.setEnabled(context, granted, sessionId)
            if (!granted) {
                scope.launch {
                    val result = snackbar.showSnackbar(
                        context.stringResource(KMR.strings.suwayomi_settings_notification_permission),
                        actionLabel = context.stringResource(KMR.strings.suwayomi_settings_open_android_settings),
                    )
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }
            }
        }
        var refreshing by remember { mutableStateOf(false) }
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
        LaunchedEffect(Unit) { model.load() }
        Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Text(stringResource(KMR.strings.suwayomi_server_settings)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            }, actions = {
                IconButton(onClick = ::reload, enabled = !state.loading && !refreshing) {
                    Icon(Icons.Filled.Refresh, stringResource(KMR.strings.suwayomi_reload))
                }
            })
        }) { padding ->
            ServerSettingsContent(
                state = state,
                busy = state.loading || refreshing,
                background = background,
                modifier = Modifier.padding(padding),
                onBackground = { checked ->
                    if (checked && android.os.Build.VERSION.SDK_INT >= 33 && !SuwayomiNotificationWorker.canNotify(context)) {
                        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        background = checked
                        SuwayomiNotificationWorker.setEnabled(context, checked, sessionId)
                    }
                },
                onOption = { field, value -> model.load { changeUserOption(field, JsonPrimitive(value)) } },
                onInterval = { hours ->
                    model.load { setUpdateInterval(hours) }.join()
                    model.state.value.error == null
                },
                onRetry = ::reload,
                onClearCache = {
                    scope.launch {
                        model.load {
                            pageCache.deleteRecursively()
                            context.imageLoader.memoryCache?.clear()
                            context.imageLoader.diskCache?.clear()
                        }.join()
                        if (model.state.value.error == null) snackbar.showSnackbar(context.stringResource(KMR.strings.suwayomi_settings_cache_cleared))
                    }
                },
                onWebUi = {
                    val session = Injekt.get<SuwayomiBackend>().requireSession(sessionId)
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(session.baseUrl)))
                },
            )
        }
    }
}

// KMK <--
