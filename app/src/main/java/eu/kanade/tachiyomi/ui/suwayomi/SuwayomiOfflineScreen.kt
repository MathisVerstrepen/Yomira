// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.OfflineState
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiOfflineScreen(private val sessionId: String) : Screen() {
    @Composable
    override fun Content() {
        val backend = remember { Injekt.get<SuwayomiBackend>() }
        val current by backend.session.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val session = current?.takeIf { it.id == sessionId }
        if (session == null) {
            ConnectionError(SuwayomiFailure.SESSION_CHANGED, { navigator.pop() })
            return
        }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snapshot by session.offline.state.collectAsState()
        var actionError by remember { mutableStateOf(false) }
        fun action(block: (eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession) -> Unit) {
            scope.launch(Dispatchers.IO) {
                try {
                    block(backend.requireSession(sessionId))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    actionError = true
                }
            }
        }
        var used by remember { mutableLongStateOf(0) }
        var editLimit by remember { mutableStateOf(false) }
        var limit by remember(snapshot.limitMiB) { mutableStateOf(snapshot.limitMiB.toString()) }
        var remove by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(snapshot) { used = withContext(Dispatchers.IO) { session.offline.usedBytes() } }
        Scaffold(topBar = { AppBar(title = stringResource(KMR.strings.suwayomi_phone_downloads), navigateUp = { navigator.pop() }) }) { padding ->
            LazyColumn(contentPadding = padding) {
                item {
                    SwitchPreferenceWidget(title = stringResource(KMR.strings.suwayomi_offline_wifi), subtitle = stringResource(KMR.strings.suwayomi_offline_wifi_summary), checked = snapshot.wifiOnly, onCheckedChanged = { value ->
                        action { it.configureOffline(value, snapshot.limitMiB) }
                    })
                    TextPreferenceWidget(title = stringResource(KMR.strings.suwayomi_offline_limit), subtitle = stringResource(KMR.strings.suwayomi_offline_usage, Formatter.formatShortFileSize(context, used), Formatter.formatShortFileSize(context, snapshot.limitMiB * 1024L * 1024)), onPreferenceClick = { editLimit = true })
                    Text(stringResource(KMR.strings.suwayomi_offline_storage_notice), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { action { it.pauseOffline(!snapshot.paused) } }) { Text(stringResource(if (snapshot.paused) KMR.strings.suwayomi_offline_resume else KMR.strings.suwayomi_offline_pause)) }
                        TextButton(onClick = { action { it.retryOffline() } }) { Text(stringResource(KMR.strings.suwayomi_retry)) }
                    }
                    TextPreferenceWidget(title = stringResource(KMR.strings.suwayomi_server_downloads), onPreferenceClick = { navigator.push(eu.kanade.tachiyomi.ui.download.DownloadQueueScreen) })
                    if (snapshot.paused) Text(stringResource(KMR.strings.suwayomi_offline_paused), Modifier.padding(horizontal = 16.dp))
                    if (actionError) Text(stringResource(KMR.strings.suwayomi_offline_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    PendingChanges(session)
                    HorizontalDivider()
                }
                if (snapshot.downloads.isEmpty()) item { Text(stringResource(KMR.strings.suwayomi_offline_empty), Modifier.padding(24.dp)) }
                items(snapshot.downloads, key = { it.chapterId }) { download ->
                    val title = snapshot.titles[download.mangaId]
                    val chapter = title?.chapters?.firstOrNull { it.id == download.chapterId }
                    Column {
                        ListItem(
                            modifier = Modifier.clickable { navigator.push(serverMangaScreen(sessionId, download.mangaId)) },
                            headlineContent = { Text(title?.manga?.title.orEmpty()) },
                            supportingContent = {
                                Column {
                                    Text(chapter?.name.orEmpty())
                                    Text(stringResource(offlineStatus(download.state), download.pages, download.total))
                                }
                            },
                        )
                        if (download.state == OfflineState.DOWNLOADING) LinearProgressIndicator(progress = { if (download.total == 0) 0f else download.pages.toFloat() / download.total }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        Row {
                            if (download.state == OfflineState.COMPLETE) {
                                TextButton(onClick = {
                                    backend.requireSession(sessionId)
                                    context.startActivity(ReaderActivity.newIntent(context, SuwayomiMode.readerId(download.mangaId), SuwayomiMode.readerId(download.chapterId)))
                                }) { Text(stringResource(KMR.strings.suwayomi_offline_read)) }
                            }
                            TextButton(onClick = { remove = download.chapterId }) { Text(stringResource(if (download.state == OfflineState.COMPLETE) KMR.strings.suwayomi_offline_delete else MR.strings.action_cancel)) }
                        }
                    }
                }
            }
        }
        if (editLimit) {
            AlertDialog(
                onDismissRequest = { editLimit = false },
                title = { Text(stringResource(KMR.strings.suwayomi_offline_limit)) },
                text = { OutlinedTextField(value = limit, onValueChange = { limit = it.filter(Char::isDigit).take(7) }, label = { Text(stringResource(KMR.strings.suwayomi_offline_limit_mib)) }, supportingText = { Text(stringResource(KMR.strings.suwayomi_offline_limit_range)) }, isError = limit.toIntOrNull() !in 128..1_048_576, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) },
                confirmButton = {
                    TextButton(enabled = limit.toIntOrNull() in 128..1_048_576, onClick = {
                        val value = limit.toInt()
                        editLimit = false
                        action { it.configureOffline(snapshot.wifiOnly, value) }
                    }) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = { TextButton(onClick = { editLimit = false }) { Text(stringResource(MR.strings.action_cancel)) } },
            )
        }
        remove?.let { id ->
            AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(KMR.strings.suwayomi_offline_delete)) }, text = { Text(stringResource(KMR.strings.suwayomi_offline_delete_notice)) }, confirmButton = {
                TextButton(onClick = {
                    remove = null
                    action { it.removeOffline(id) }
                }) { Text(stringResource(MR.strings.action_ok)) }
            }, dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(MR.strings.action_cancel)) } })
        }
    }
}

private fun offlineStatus(state: OfflineState) = when (state) {
    OfflineState.QUEUED -> KMR.strings.suwayomi_offline_queued
    OfflineState.DOWNLOADING -> KMR.strings.suwayomi_offline_progress
    OfflineState.WIFI -> KMR.strings.suwayomi_offline_wait_wifi
    OfflineState.STORAGE -> KMR.strings.suwayomi_offline_wait_storage
    OfflineState.NETWORK -> KMR.strings.suwayomi_offline_wait_network
    OfflineState.ERROR -> KMR.strings.suwayomi_offline_error
    OfflineState.COMPLETE -> KMR.strings.suwayomi_offline_complete
}
// KMK <--
