// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
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
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiOfflineScreen(private val sessionId: String, private val showSettings: Boolean = false) : Screen() {
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
        val snack = remember { SnackbarHostState() }
        val errorMessage = stringResource(KMR.strings.suwayomi_phone_action_error)
        var busy by remember { mutableStateOf(false) }
        fun action(block: (eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession) -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { block(backend.requireSession(sessionId)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    scope.launch { snack.showSnackbar(errorMessage) }
                } finally {
                    busy = false
                }
            }
        }
        var used by remember { mutableLongStateOf(0) }
        var editLimit by remember { mutableStateOf(false) }
        var editCount by remember { mutableStateOf(false) }
        var limit by remember(snapshot.limitMiB) { mutableStateOf(snapshot.limitMiB.toString()) }
        var remove by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(snapshot) { used = withContext(Dispatchers.IO) { session.offline.usedBytes() } }
        val downloadsScroll = rememberLazyListState()
        val settingsScroll = rememberLazyListState()
        var settings by rememberSaveable(sessionId) { mutableStateOf(showSettings) }

        @Composable
        fun Status() {
            PendingChanges(session)
        }
        Scaffold(topBar = { AppBar(title = stringResource(KMR.strings.suwayomi_phone_downloads), navigateUp = { navigator.pop() }) }, snackbarHost = { SnackbarHost(snack) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = if (settings) 1 else 0) {
                    Tab(selected = !settings, onClick = { settings = false }, text = { Text(stringResource(KMR.strings.suwayomi_phone_downloads_tab)) })
                    Tab(selected = settings, onClick = { settings = true }, text = { Text(stringResource(KMR.strings.suwayomi_phone_settings_tab)) })
                }
                if (settings) {
                    LazyColumn(state = settingsScroll, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { Status() }
                        item {
                            PhoneSectionTitle(stringResource(KMR.strings.suwayomi_phone_transfers))
                            Surface(Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                PhoneSettingSwitch(stringResource(KMR.strings.suwayomi_phone_wifi), stringResource(KMR.strings.suwayomi_phone_wifi_hint), snapshot.wifiOnly, !busy) { value -> action { it.configureOffline(value, snapshot.limitMiB) } }
                            }
                        }
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PhoneStorageCard(used, snapshot.limitMiB, !busy, onClick = {
                                    limit = snapshot.limitMiB.toString()
                                    editLimit = true
                                })
                                Text(stringResource(KMR.strings.suwayomi_phone_storage_hint), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            PhoneSectionTitle(stringResource(KMR.strings.suwayomi_smart_dialog_title))
                            Text(stringResource(KMR.strings.suwayomi_phone_smart_hint), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column {
                                    PhoneSettingAction(stringResource(KMR.strings.suwayomi_phone_buffer), value = pluralStringResource(KMR.plurals.suwayomi_phone_chapters, snapshot.smartChapterCount, snapshot.smartChapterCount), enabled = !busy, onClick = { editCount = true })
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    PhoneSettingSwitch(stringResource(KMR.strings.suwayomi_phone_cleanup), stringResource(KMR.strings.suwayomi_phone_cleanup_hint), snapshot.smartRemoveRead, !busy) { value -> action { it.configureSmartDownloads(snapshot.smartChapterCount, value) } }
                                }
                            }
                        }
                        item {
                            PhoneSectionTitle(stringResource(KMR.strings.suwayomi_phone_selected, snapshot.smartTitles.size))
                            if (snapshot.smartTitles.isEmpty()) Text(stringResource(KMR.strings.suwayomi_smart_titles_empty), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(snapshot.smartTitles.sorted(), key = { "smart-$it" }) { mangaId ->
                            val title = snapshot.titles[mangaId]
                            if (title != null) {
                                Surface(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(title.manga.title, Modifier.weight(1f).clickable { navigator.push(serverMangaScreen(sessionId, mangaId)) }.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                                        Switch(checked = true, enabled = !busy, onCheckedChange = { action { it.selectSmartDownloads(title.manga, title.chapters, false) } }, modifier = Modifier.semantics { contentDescription = title.manga.title })
                                    }
                                }
                            }
                        }
                        item {
                            PhoneSectionTitle(stringResource(KMR.strings.suwayomi_phone_server_section))
                            Surface(Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                PhoneSettingAction(stringResource(KMR.strings.suwayomi_server_downloads), description = stringResource(KMR.strings.suwayomi_phone_server_hint), onClick = { navigator.push(eu.kanade.tachiyomi.ui.download.DownloadQueueScreen) })
                            }
                        }
                    }
                } else {
                    PhoneDownloadList(
                        snapshot = snapshot,
                        state = downloadsScroll,
                        busy = busy,
                        header = {
                            Status()
                            PhoneStorageCard(used, snapshot.limitMiB, !busy, onClick = {
                                limit = snapshot.limitMiB.toString()
                                editLimit = true
                            })
                        },
                        onPause = { action { it.pauseOffline(!snapshot.paused) } },
                        onRetry = { action { it.retryOffline() } },
                        onTitle = { navigator.push(serverMangaScreen(sessionId, it)) },
                        onRead = { download ->
                            backend.requireSession(sessionId)
                            context.startActivity(ReaderActivity.newIntent(context, SuwayomiMode.readerId(download.mangaId), SuwayomiMode.readerId(download.chapterId)))
                        },
                        onRemove = { remove = it },
                    )
                }
            }
        }
        if (editCount) {
            AlertDialog(
                onDismissRequest = { editCount = false },
                title = { Text(stringResource(KMR.strings.suwayomi_smart_count)) },
                text = {
                    Column {
                        listOf(1, 3, 5, 10, 20).forEach { count ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = snapshot.smartChapterCount == count, role = Role.RadioButton) {
                                    editCount = false
                                    action { it.configureSmartDownloads(count, snapshot.smartRemoveRead) }
                                },
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = snapshot.smartChapterCount == count, onClick = null)
                                Text(pluralStringResource(KMR.plurals.suwayomi_phone_chapters, count, count), Modifier.padding(8.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { editCount = false }) { Text(stringResource(MR.strings.action_close)) } },
            )
        }
        if (editLimit) {
            AlertDialog(
                onDismissRequest = { editLimit = false },
                title = { Text(stringResource(KMR.strings.suwayomi_phone_storage)) },
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
            val download = snapshot.downloads.firstOrNull { it.chapterId == id }
            val chapter = download?.let { snapshot.titles[it.mangaId]?.chapters?.firstOrNull { chapter -> chapter.id == id } }
            AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(KMR.strings.suwayomi_offline_delete)) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (chapter != null) Text(chapter.name, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(KMR.strings.suwayomi_offline_delete_notice))
                }
            }, confirmButton = {
                TextButton(onClick = {
                    remove = null
                    action { it.removeOffline(id) }
                }) { Text(stringResource(KMR.strings.suwayomi_phone_remove), color = MaterialTheme.colorScheme.error) }
            }, dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(MR.strings.action_cancel)) } })
        }
    }
}

// KMK <--
