// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.SubcomposeAsyncImage
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerExtension
import eu.kanade.tachiyomi.data.suwayomi.ServerExtensionStore
import eu.kanade.tachiyomi.data.suwayomi.ServerSource
import eu.kanade.tachiyomi.data.suwayomi.ServerUser
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiImages
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import eu.kanade.tachiyomi.data.suwayomi.allowed
import eu.kanade.tachiyomi.data.suwayomi.changeExtension
import eu.kanade.tachiyomi.data.suwayomi.changeExtensionStore
import eu.kanade.tachiyomi.data.suwayomi.extensionSources
import eu.kanade.tachiyomi.data.suwayomi.extensionStores
import eu.kanade.tachiyomi.data.suwayomi.extensions
import eu.kanade.tachiyomi.data.suwayomi.refreshExtensions
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Kept by Browse so returning to Extensions preserves its list during a silent refresh. */
internal class SuwayomiExtensionsModel(
    private val sessionId: String,
    private val pkgName: String? = null,
    private val repositories: Boolean = false,
    private val backend: SuwayomiBackend = Injekt.get(),
) : StateScreenModel<SuwayomiExtensionsModel.State>(State()) {
    private val mutex = Mutex()
    private val refreshTask = SuwayomiRefreshTask()

    fun refresh() = refreshTask.launch(screenModelScope) { change(false) {}.join() }
    fun mutate(action: suspend SuwayomiSession.() -> Unit) = change(true, action)

    private fun change(mutation: Boolean, action: suspend SuwayomiSession.() -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(loading = mutation || !it.hasLoaded, busy = mutation, error = null) }
            try {
                val session = backend.requireSession(sessionId)
                session.action()
                val user = session.user()
                val extensions = if (repositories) emptyList() else session.extensions()
                val stores = if (repositories) session.extensionStores() else emptyList()
                val sources = if (pkgName != null && extensions.any { it.pkgName == pkgName && it.isInstalled }) session.extensionSources(pkgName) else emptyList()
                backend.requireSession(sessionId)
                mutableState.value = State(user, extensions, stores, sources, loading = false, hasLoaded = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, busy = false, error = failure(e)) }
            }
        }
    }

    data class State(
        val user: ServerUser? = null,
        val extensions: List<ServerExtension> = emptyList(),
        val stores: List<ServerExtensionStore> = emptyList(),
        val sources: List<ServerSource> = emptyList(),
        val loading: Boolean = true,
        val busy: Boolean = false,
        val hasLoaded: Boolean = false,
        val error: SuwayomiFailure? = null,
    )
}

@Composable
internal fun suwayomiExtensionsTab(sessionId: String, model: SuwayomiExtensionsModel, query: String?): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val state by model.state.collectAsState()
    return TabContent(
        titleRes = KMR.strings.suwayomi_extensions,
        badgeNumber = state.extensions.count { it.isInstalled && it.hasUpdate }.takeIf { it > 0 },
        searchEnabled = true,
        actions = persistentListOf(
            AppBar.Action(stringResource(KMR.strings.suwayomi_reload), Icons.Outlined.Refresh, onClick = { model.refresh() }),
            AppBar.OverflowAction(stringResource(KMR.strings.suwayomi_refresh_extensions), onClick = { model.mutate { refreshExtensions() } }),
            AppBar.OverflowAction(stringResource(KMR.strings.suwayomi_extension_stores), onClick = { navigator.push(SuwayomiExtensionStoresScreen(sessionId)) }),
        ),
    ) { padding, _ ->
        val snackbar = rememberRefreshErrors(state.error, state.hasLoaded) { model.refresh() }
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column {
                RefreshProgress(state.loading)
                if (!state.hasLoaded) state.error?.let { ConnectionError(it, { model.refresh() }) }
                LazyColumn {
                    item { SharedExtensionsNotice() }
                    val filtered = state.extensions.filter { query.isNullOrBlank() || it.name.contains(query, true) || it.lang.contains(query, true) }
                    val groups = filtered.groupBy {
                        when {
                            it.isInstalled && it.hasUpdate -> 0
                            it.isInstalled -> 1
                            else -> 2
                        }
                    }.toSortedMap()
                    if (state.hasLoaded && filtered.isEmpty()) item { Text(stringResource(KMR.strings.suwayomi_no_extensions), Modifier.padding(16.dp)) }
                    groups.forEach { (section, entries) ->
                        item(key = "header-$section") {
                            Text(
                                stringResource(
                                    when (section) {
                                        0 -> KMR.strings.suwayomi_extension_updates
                                        1 -> KMR.strings.suwayomi_extensions_installed
                                        else -> KMR.strings.suwayomi_extensions_available
                                    },
                                ),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        items(entries.sortedWith(compareBy<ServerExtension> { it.lang }.thenBy { it.name }), key = { it.pkgName }) { extension ->
                            ServerExtensionRow(sessionId, extension, state, model) { navigator.push(SuwayomiExtensionDetailsScreen(sessionId, extension.pkgName)) }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun SharedExtensionsNotice() {
    Text(stringResource(KMR.strings.suwayomi_shared_source_notice), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ExtensionLogo(sessionId: String, path: String) {
    SubcomposeAsyncImage(
        model = path.takeIf { it.isNotBlank() }?.let { SuwayomiImages.url(sessionId, it) },
        contentDescription = null,
        modifier = Modifier.size(40.dp),
        error = { Icon(Icons.Outlined.Extension, null) },
    )
}

@Composable
private fun ServerExtensionRow(sessionId: String, extension: ServerExtension, state: SuwayomiExtensionsModel.State, model: SuwayomiExtensionsModel, open: (() -> Unit)? = null) {
    var action by remember { mutableStateOf<String?>(null) }
    BaseBrowseItem(
        modifier = Modifier.fillMaxWidth(),
        onClickItem = open ?: {},
        onLongClickItem = { if (extension.isInstalled && state.user?.allowed("UNINSTALL_EXTENSIONS") == true) action = "uninstall" },
        icon = { ExtensionLogo(sessionId, extension.iconUrl) },
        action = {
            if (extension.isInstalled && open != null) IconButton(onClick = open) { Icon(Icons.Outlined.Settings, stringResource(KMR.strings.suwayomi_source_preferences)) }
            if (!extension.isInstalled || extension.hasUpdate) {
                IconButton(
                    onClick = { action = if (extension.isInstalled) "update" else "install" },
                    enabled = !state.busy && state.user?.allowed("INSTALL_EXTENSIONS") == true,
                ) { Icon(Icons.Outlined.GetApp, stringResource(if (extension.isInstalled) KMR.strings.suwayomi_update_extension else KMR.strings.suwayomi_install_extension)) }
            }
        },
    ) {
        Column(Modifier.weight(1f).padding(start = MaterialTheme.padding.medium)) {
            Text(extension.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text("${LocaleHelper.getSourceDisplayName(extension.lang, LocalContext.current)} · ${extension.versionName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (extension.isObsolete) Text(stringResource(KMR.strings.suwayomi_obsolete_extension), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    action?.let { selected ->
        ExtensionActionConfirmation(extension, selected, onDismiss = { action = null }) {
            model.mutate { changeExtension(extension.pkgName, selected) }
            action = null
        }
    }
}

@Composable
private fun ExtensionActionConfirmation(extension: ServerExtension, action: String, onDismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(extension.name) },
        text = {
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
        },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(KMR.strings.suwayomi_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
    )
}

private class SuwayomiExtensionDetailsScreen(private val sessionId: String, private val pkgName: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SuwayomiExtensionsModel(sessionId, pkgName) }
        val state by model.state.collectAsState()
        val extension = state.extensions.firstOrNull { it.pkgName == pkgName }
        val snackbar = rememberRefreshErrors(state.error, state.hasLoaded) { model.refresh() }
        var uninstall by remember { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
        Scaffold(
            topBar = { AppBar(title = extension?.name ?: stringResource(KMR.strings.suwayomi_extensions), navigateUp = navigator::pop) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                RefreshProgress(state.loading)
                if (!state.hasLoaded) state.error?.let { ConnectionError(it, { model.refresh() }) }
                LazyColumn {
                    item { SharedExtensionsNotice() }
                    extension?.let { ext ->
                        item { ServerExtensionRow(sessionId, ext, state, model) }
                        if (ext.isInstalled) {
                            item { Text(stringResource(KMR.strings.suwayomi_source_preferences), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                            items(state.sources.sortedWith(compareBy<ServerSource> { it.lang }.thenBy { it.name }), key = { it.id }) { source ->
                                BaseBrowseItem(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClickItem = { navigator.push(SuwayomiSourcePreferencesScreen(sessionId, source.id, source.name, source.lang)) },
                                    icon = { ExtensionLogo(sessionId, source.iconUrl) },
                                    action = { Icon(Icons.Outlined.Settings, null) },
                                ) {
                                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                        Text(source.name)
                                        Text(LocaleHelper.getSourceDisplayName(source.lang, LocalContext.current), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            item {
                                TextButton(onClick = { uninstall = true }, enabled = !state.busy && state.user?.allowed("UNINSTALL_EXTENSIONS") == true, modifier = Modifier.padding(16.dp)) {
                                    Text(stringResource(KMR.strings.suwayomi_uninstall_extension))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (uninstall && extension != null) {
            ExtensionActionConfirmation(extension, "uninstall", { uninstall = false }) {
                model.mutate { changeExtension(pkgName, "uninstall") }
                uninstall = false
            }
        }
    }
}

private class SuwayomiExtensionStoresScreen(private val sessionId: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SuwayomiExtensionsModel(sessionId, repositories = true) }
        val state by model.state.collectAsState()
        val snackbar = rememberRefreshErrors(state.error, state.hasLoaded) { model.refresh() }
        var url by rememberSaveable { mutableStateOf("") }
        var remove by remember { mutableStateOf<ServerExtensionStore?>(null) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
        Scaffold(
            topBar = { AppBar(title = stringResource(KMR.strings.suwayomi_extension_stores), navigateUp = navigator::pop) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                RefreshProgress(state.loading)
                if (!state.hasLoaded) state.error?.let { ConnectionError(it, { model.refresh() }) }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { SharedExtensionsNotice() }
                    items(state.stores, key = { it.indexUrl }) { store ->
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(store.name)
                                Text(store.indexUrl, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { remove = store }, enabled = !state.busy && state.user?.allowed("MANAGE_EXTENSION_STORES") == true) { Icon(Icons.Outlined.Delete, stringResource(KMR.strings.suwayomi_delete)) }
                        }
                    }
                    if (state.user?.allowed("MANAGE_EXTENSION_STORES") == true) {
                        item {
                            OutlinedTextField(url, { url = it }, label = { Text(stringResource(KMR.strings.suwayomi_store_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = {
                                val value = url.trim()
                                model.mutate { changeExtensionStore(value, true) }
                            }, enabled = !state.busy && (url.trim().startsWith("https://") || url.trim().startsWith("http://"))) { Text(stringResource(KMR.strings.suwayomi_add_store)) }
                        }
                    }
                }
            }
        }
        remove?.let { store ->
            AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(KMR.strings.suwayomi_delete)) }, text = { Text(store.indexUrl) }, confirmButton = {
                TextButton(onClick = {
                    model.mutate { changeExtensionStore(store.indexUrl, false) }
                    remove = null
                }) { Text(stringResource(KMR.strings.suwayomi_delete)) }
            }, dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } })
        }
    }
}
// KMK <--
