// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.imageLoader
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerExtension
import eu.kanade.tachiyomi.data.suwayomi.ServerExtensionStore
import eu.kanade.tachiyomi.data.suwayomi.ServerPreference
import eu.kanade.tachiyomi.data.suwayomi.ServerUser
import eu.kanade.tachiyomi.data.suwayomi.ServerUserOptions
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiNotificationWorker
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import eu.kanade.tachiyomi.data.suwayomi.allowed
import eu.kanade.tachiyomi.data.suwayomi.changeExtension
import eu.kanade.tachiyomi.data.suwayomi.changeExtensionStore
import eu.kanade.tachiyomi.data.suwayomi.changeSourcePreference
import eu.kanade.tachiyomi.data.suwayomi.changeUserOption
import eu.kanade.tachiyomi.data.suwayomi.extensionStores
import eu.kanade.tachiyomi.data.suwayomi.extensions
import eu.kanade.tachiyomi.data.suwayomi.refreshExtensions
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiAdministrationModel(private val sessionId: String, private val sourceId: Long?) : StateScreenModel<SuwayomiAdministrationModel.State>(State()) {
    private val mutex = Mutex()
    fun load(action: suspend SuwayomiSession.() -> Unit = {}) = screenModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val session = Injekt.get<SuwayomiBackend>().requireSession(sessionId)
                session.action()
                val user = session.user()
                mutableState.value = if (sourceId != null) {
                    State(user = user, preferences = if (user.allowed("MANAGE_SOURCE_PREFERENCES")) session.sourcePreferences(sourceId) else emptyList(), loading = false)
                } else {
                    State(user, session.extensions(), session.extensionStores(), options = session.userOptions(), interval = if (user.allowed("MANAGE_SETTINGS")) session.updateInterval() else null, loading = false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    data class State(val user: ServerUser? = null, val extensions: List<ServerExtension> = emptyList(), val stores: List<ServerExtensionStore> = emptyList(), val preferences: List<ServerPreference> = emptyList(), val options: ServerUserOptions? = null, val interval: Double? = null, val loading: Boolean = true, val error: SuwayomiFailure? = null)
}

class SuwayomiAdministrationScreen(private val sessionId: String, private val sourceId: Long? = null) : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiAdministrationModel(sessionId, sourceId) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        var background by remember { mutableStateOf(SuwayomiNotificationWorker.enabled(context)) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            background = granted
            SuwayomiNotificationWorker.setEnabled(context, granted, sessionId)
        }
        var search by remember { mutableStateOf("") }
        var storeUrl by remember { mutableStateOf("") }
        var interval by remember(state.interval) { mutableStateOf(state.interval?.toString().orEmpty()) }
        var confirmation by remember { mutableStateOf<Pair<ServerExtension, String>?>(null) }
        var removeStore by remember { mutableStateOf<ServerExtensionStore?>(null) }
        var tab by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { model.load() }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(if (sourceId == null) KMR.strings.suwayomi_administration else KMR.strings.suwayomi_source_preferences)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            }, actions = { TextButton(onClick = { model.load() }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_reload)) } })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(failureText(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (sourceId != null) {
                    Text(stringResource(KMR.strings.suwayomi_shared_source_notice), Modifier.padding(16.dp))
                    if (state.user?.allowed("MANAGE_SOURCE_PREFERENCES") == false) Text(failureText(SuwayomiFailure.PERMISSION_DENIED), Modifier.padding(16.dp))
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.preferences.forEachIndexed { index, preference ->
                            if (preference.visible) SourcePreference(preference, !state.loading) { field, value -> model.load { changeSourcePreference(sourceId, index, preference, field, value) } }
                        }
                    }
                } else {
                    Row {
                        TextButton(onClick = { tab = 0 }) { Text(stringResource(KMR.strings.suwayomi_extensions)) }
                        TextButton(onClick = { tab = 1 }) { Text(stringResource(KMR.strings.suwayomi_server_options)) }
                    }
                    if (tab == 0) {
                        Text(stringResource(KMR.strings.suwayomi_shared_source_notice), Modifier.padding(12.dp))
                        OutlinedTextField(search, { search = it }, label = { Text(stringResource(KMR.strings.suwayomi_search)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                        TextButton(onClick = { model.load { refreshExtensions() } }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_refresh_extensions)) }
                        LazyColumn(Modifier.weight(1f)) {
                            items(state.extensions.filter { it.name.contains(search, true) || it.lang.contains(search, true) }.sortedWith(compareByDescending<ServerExtension> { it.isInstalled }.thenBy { it.name }), key = { it.pkgName }) { extension ->
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("${extension.name} · ${extension.lang} · ${extension.versionName}", style = MaterialTheme.typography.titleSmall)
                                    if (extension.isObsolete) Text(stringResource(KMR.strings.suwayomi_obsolete_extension), color = MaterialTheme.colorScheme.error)
                                    Row {
                                        if (!extension.isInstalled || extension.hasUpdate) TextButton(onClick = { confirmation = extension to if (extension.isInstalled) "update" else "install" }, enabled = !state.loading && state.user?.allowed("INSTALL_EXTENSIONS") == true) { Text(stringResource(if (extension.isInstalled) KMR.strings.suwayomi_update_extension else KMR.strings.suwayomi_install_extension)) }
                                        if (extension.isInstalled) TextButton(onClick = { confirmation = extension to "uninstall" }, enabled = !state.loading && state.user?.allowed("UNINSTALL_EXTENSIONS") == true) { Text(stringResource(KMR.strings.suwayomi_uninstall_extension)) }
                                    }
                                }
                            }
                            item {
                                HorizontalDivider()
                                Text(stringResource(KMR.strings.suwayomi_extension_stores), Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                                state.stores.forEach { store ->
                                    Text("${store.name}\n${store.indexUrl}", Modifier.padding(12.dp))
                                    if (state.user?.allowed("MANAGE_EXTENSION_STORES") == true) TextButton(onClick = { removeStore = store }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_delete)) }
                                }
                                if (state.user?.allowed("MANAGE_EXTENSION_STORES") == true) {
                                    OutlinedTextField(storeUrl, { storeUrl = it }, label = { Text(stringResource(KMR.strings.suwayomi_store_url)) }, singleLine = true, modifier = Modifier.padding(12.dp))
                                    TextButton(onClick = {
                                        val url = storeUrl.trim()
                                        model.load { changeExtensionStore(url, true) }
                                        storeUrl = ""
                                    }, enabled = !state.loading && (storeUrl.startsWith("https://") || storeUrl.startsWith("http://"))) { Text(stringResource(KMR.strings.suwayomi_add_store)) }
                                }
                            }
                        }
                    } else {
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
                            Text(stringResource(KMR.strings.suwayomi_user_options_notice))
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
        confirmation?.let { (extension, action) ->
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(extension.name) }, text = {
                Text(
                    stringResource(
                        KMR.strings.suwayomi_extension_confirm,
                        stringResource(
                            when (action) {
                                "install" -> KMR.strings.suwayomi_install_extension
                                "update" -> KMR.strings.suwayomi_update_extension
                                else -> KMR.strings.suwayomi_uninstall_extension
                            },
                        ),
                        extension.pkgName,
                    ),
                )
            }, confirmButton = {
                TextButton(onClick = {
                    model.load { changeExtension(extension.pkgName, action) }
                    confirmation = null
                }) { Text(stringResource(KMR.strings.suwayomi_save)) }
            }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } })
        }
        removeStore?.let { store ->
            AlertDialog(onDismissRequest = { removeStore = null }, title = { Text(stringResource(KMR.strings.suwayomi_delete)) }, text = { Text(store.indexUrl) }, confirmButton = {
                TextButton(onClick = {
                    model.load { changeExtensionStore(store.indexUrl, false) }
                    removeStore = null
                }) { Text(stringResource(KMR.strings.suwayomi_delete)) }
            }, dismissButton = { TextButton(onClick = { removeStore = null }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } })
        }
    }
}

@Composable
private fun SourcePreference(preference: ServerPreference, enabled: Boolean, change: (String, JsonElement) -> Unit) {
    var editing by remember(preference) { mutableStateOf(false) }
    var text by remember(preference) { mutableStateOf(preference.valueString ?: preference.defaultString.orEmpty()) }
    var selected by remember(preference) { mutableStateOf((preference.valueList ?: preference.defaultList.orEmpty()).toSet()) }
    val editable = enabled && preference.enabled
    Column {
        Text(preference.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
        preference.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when (preference.type) {
            "SwitchPreference", "CheckBoxPreference" -> Checkbox(preference.valueBool ?: preference.defaultBool ?: false, { change(if (preference.type == "SwitchPreference") "switchState" else "checkBoxState", JsonPrimitive(it)) }, enabled = editable)
            "EditTextPreference", "ListPreference", "MultiSelectListPreference" -> TextButton(onClick = { editing = true }, enabled = editable) { Text(stringResource(KMR.strings.suwayomi_edit_preference)) }
            else -> Text(stringResource(KMR.strings.suwayomi_unsupported_preference))
        }
    }
    if (editing) {
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(preference.title.orEmpty()) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (preference.type == "EditTextPreference") {
                    OutlinedTextField(text, { text = it }, visualTransformation = PasswordVisualTransformation())
                } else {
                    preference.entries.forEachIndexed { index, label ->
                        val value = preference.entryValues.getOrNull(index)
                        if (value != null) {
                            Row {
                                Checkbox(if (preference.type == "ListPreference") text == value else value in selected, { checked -> if (preference.type == "ListPreference") text = value else selected = if (checked) selected + value else selected - value })
                                Text(label, Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                when (preference.type) {
                    "EditTextPreference" -> change("editTextState", JsonPrimitive(text))
                    "ListPreference" -> change("listState", JsonPrimitive(text))
                    "MultiSelectListPreference" -> change("multiSelectState", JsonArray(selected.map(::JsonPrimitive)))
                }
                editing = false
            }, enabled = preference.type != "ListPreference" || text in preference.entryValues) { Text(stringResource(KMR.strings.suwayomi_save)) }
        }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } })
    }
}
// KMK <--
