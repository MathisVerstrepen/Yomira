// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ChapterIntentKind
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import eu.kanade.tachiyomi.data.suwayomi.coverUrl
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Connection/account settings only. The application home is Komikku's HomeScreen. */
object SuwayomiHomeScreen : Screen() {
    private fun readResolve(): Any = SuwayomiHomeScreen

    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiScreenModel(loadLibrary = false) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        if (state.session != null && navigator.items.size == 1 && navigator.lastItem == SuwayomiHomeScreen) {
            androidx.compose.runtime.LaunchedEffect(Unit) { navigator.replaceAll(eu.kanade.tachiyomi.ui.home.HomeScreen) }
            return
        }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        var checking by remember { mutableStateOf(false) }
        var reauthError by remember { mutableStateOf<SuwayomiFailure?>(null) }
        var reauthenticate by remember { mutableStateOf(false) }
        var replacement by remember { mutableStateOf(false) }
        var preferenceSync by remember { mutableStateOf(false) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
        fun checkConnection() {
            scope.launch {
                checking = true
                val verified = try {
                    model.refresh().join()
                    model.state.value.error == null
                } finally {
                    checking = false
                }
                if (verified) snackbar.showSnackbar(context.stringResource(KMR.strings.suwayomi_connection_verified))
            }
        }
        val busy = state.loading || checking
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            if (state.session != null) {
                eu.kanade.presentation.components.AppBar(
                    title = stringResource(KMR.strings.suwayomi_setup_connection),
                    actions = {
                        IconButton(onClick = ::checkConnection, enabled = !busy) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(KMR.strings.suwayomi_connection_check))
                        }
                    },
                    navigateUp = if (navigator.canPop) {
                        { navigator.pop() }
                    } else {
                        null
                    },
                )
            }
        }) { padding ->
            if (state.session == null) {
                Column(Modifier.padding(padding)) {
                    ServerSetup(
                        state.loading,
                        state.error,
                        model::connect,
                        navigateUp = if (navigator.canPop) ({ navigator.pop() }) else null,
                    )
                }
            } else {
                ConnectionSettingsContent(
                    state = state,
                    busy = busy,
                    modifier = Modifier.padding(padding),
                    onSignIn = {
                        username = state.user?.username.orEmpty()
                        password = ""
                        reauthError = null
                        reauthenticate = true
                    },
                    onPreferences = { preferenceSync = true },
                    onDisconnect = { replacement = true },
                    onCheck = ::checkConnection,
                )
            }
        }
        if (reauthenticate) {
            ConnectionSignInDialog(
                username = username,
                password = password,
                onUsername = {
                    username = it
                    reauthError = null
                },
                onPassword = {
                    password = it
                    reauthError = null
                },
                busy = busy,
                error = reauthError,
                onDismiss = {
                    reauthenticate = false
                    password = ""
                    if (!busy && reauthError != null) model.refresh()
                    reauthError = null
                },
                onSubmit = {
                    val submittedUsername = username.trim()
                    val submittedPassword = password
                    scope.launch {
                        reauthError = null
                        model.reauthenticate(submittedUsername, submittedPassword).join()
                        if (model.state.value.error == null) {
                            reauthenticate = false
                            password = ""
                            snackbar.showSnackbar(context.stringResource(KMR.strings.suwayomi_connection_signed_in))
                        } else {
                            reauthError = model.state.value.error
                        }
                    }
                },
            )
        }
        if (replacement) {
            AlertDialog(
                onDismissRequest = { replacement = false },
                title = { Text(stringResource(KMR.strings.suwayomi_connection_disconnect_title)) },
                text = { Text(stringResource(KMR.strings.suwayomi_disconnect_notice)) },
                confirmButton = {
                    TextButton(onClick = {
                        replacement = false
                        model.disconnect()
                    }) { Text(stringResource(KMR.strings.suwayomi_disconnect)) }
                },
                dismissButton = { TextButton(onClick = { replacement = false }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
            )
        }
        if (preferenceSync) {
            ConnectionPreferenceDialog(
                onDismiss = { preferenceSync = false },
                onCopy = { upload ->
                    preferenceSync = false
                    scope.launch {
                        model.readingPreferences(upload).join()
                        if (model.state.value.error == null) {
                            snackbar.showSnackbar(context.stringResource(if (upload) KMR.strings.suwayomi_connection_uploaded else KMR.strings.suwayomi_connection_applied))
                        }
                    }
                },
            )
        }
    }
}

internal fun serverMangaScreen(sessionId: String, mangaId: Int) = eu.kanade.tachiyomi.ui.manga.MangaScreen(
    mangaId = SuwayomiMode.readerId(mangaId),
    serverSessionId = sessionId,
)

/** Restore previously saved navigation through the same native screen. */
class SuwayomiMangaScreen(private val sessionId: String, private val mangaId: Int) : Screen() {
    @Composable
    override fun Content() {
        serverMangaScreen(sessionId, mangaId).Content()
    }
}

@Composable
internal fun ServerCover(session: SuwayomiSession, path: String?, modifier: Modifier) {
    val bytes by produceState<BufferedSource?>(null, session, path) {
        value = try {
            path?.let { Buffer().write(session.image(it)) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
    AsyncImage(model = bytes, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

internal fun showPendingChanges(pending: List<eu.kanade.tachiyomi.data.suwayomi.ChapterIntent>, failure: SuwayomiFailure?, showRoutinePending: Boolean): Boolean = pending.isNotEmpty() && (
    showRoutinePending || failure != null || pending.any { it.status == eu.kanade.tachiyomi.data.suwayomi.ChapterIntentStatus.AMBIGUOUS || it.status == eu.kanade.tachiyomi.data.suwayomi.ChapterIntentStatus.FAILED }
    )

@Composable
internal fun PendingChanges(session: SuwayomiSession, showRoutinePending: Boolean = true) {
    val pending by session.pending.collectAsState()
    val failure by session.mutationFailure.collectAsState()
    val scope = rememberCoroutineScope()
    var discard by remember { mutableStateOf(false) }
    if (showPendingChanges(pending, failure, showRoutinePending)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(KMR.strings.suwayomi_pending, pending.size), style = MaterialTheme.typography.labelMedium)
            if (failure != null) {
                Text(failureText(failure!!), color = MaterialTheme.colorScheme.error)
                Text(stringResource(KMR.strings.suwayomi_retry_notice), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch(Dispatchers.IO) { runCatching { session.retryPending() } } }) { Text(stringResource(KMR.strings.suwayomi_retry_changes)) }
                TextButton(onClick = { discard = true }) { Text(stringResource(KMR.strings.suwayomi_discard_changes)) }
            }
        }
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(KMR.strings.suwayomi_discard_changes)) },
            text = { Text(stringResource(KMR.strings.suwayomi_discard_notice)) },
            confirmButton = {
                TextButton(onClick = {
                    discard = false
                    scope.launch(Dispatchers.IO) { runCatching { session.discardPending() } }
                }) { Text(stringResource(KMR.strings.suwayomi_discard_changes)) }
            },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
        )
    }
}

@Composable
internal fun ConnectionError(failure: SuwayomiFailure, retry: () -> Unit, configure: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(failureText(failure), color = MaterialTheme.colorScheme.error)
        Button(onClick = retry) { Text(stringResource(KMR.strings.suwayomi_retry)) }
        configure?.let { TextButton(onClick = it) { Text(stringResource(KMR.strings.suwayomi_replace)) } }
    }
}

@Composable
internal fun failureText(failure: SuwayomiFailure): String = stringResource(failureResource(failure))

internal fun failureResource(failure: SuwayomiFailure): dev.icerock.moko.resources.StringResource = when (failure) {
    SuwayomiFailure.CONNECTION_REQUIRED -> KMR.strings.suwayomi_connection_required
    SuwayomiFailure.AUTHENTICATION_REQUIRED -> KMR.strings.suwayomi_auth_required
    SuwayomiFailure.PERMISSION_DENIED -> KMR.strings.suwayomi_permission_denied
    SuwayomiFailure.NETWORK -> KMR.strings.suwayomi_network_error
    SuwayomiFailure.SERVER -> KMR.strings.suwayomi_server_error
    SuwayomiFailure.CONTRACT -> KMR.strings.suwayomi_contract_error
    SuwayomiFailure.UNSUPPORTED_SERVER -> KMR.strings.suwayomi_unsupported_server
    SuwayomiFailure.SESSION_CHANGED -> KMR.strings.suwayomi_session_changed
}
// KMK <--
