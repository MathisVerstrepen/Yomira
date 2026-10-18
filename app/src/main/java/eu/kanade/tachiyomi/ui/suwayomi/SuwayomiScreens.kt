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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiAuthMode
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Buffer
import okio.BufferedSource
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SuwayomiHomeScreen : Screen() {
    private fun readResolve(): Any = SuwayomiHomeScreen

    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiScreenModel() }
        val context = LocalContext.current
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var menu by remember { mutableStateOf(false) }
        var replace by remember { mutableStateOf(false) }
        var preferenceSync by remember { mutableStateOf(false) }
        var reauthenticate by remember { mutableStateOf(false) }
        var loginName by remember { mutableStateOf("") }
        var loginPassword by remember { mutableStateOf("") }
        val preferences = remember { Injekt.get<BasePreferences>() }
        val incognito by preferences.incognitoMode().collectAsState()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(KMR.strings.suwayomi_library)) }, actions = {
                if (state.session != null) {
                    IconButton(onClick = { model.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, stringResource(KMR.strings.suwayomi_reload))
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(KMR.strings.suwayomi_settings)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_sign_in_again)) }, onClick = {
                            menu = false
                            loginName = state.user?.username.orEmpty()
                            reauthenticate = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_tracking)) }, onClick = {
                            menu = false
                            state.session?.let { navigator.push(SuwayomiTrackingScreen(it.id)) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_webui)) }, onClick = {
                            menu = false
                            state.session?.let { session -> context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(session.baseUrl))) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_recent_reading)) }, onClick = {
                            menu = false
                            state.session?.let { navigator.push(SuwayomiActivityScreen(it.id, true)) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_updates)) }, onClick = {
                            menu = false
                            state.session?.let { navigator.push(SuwayomiActivityScreen(it.id, false)) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_preference_sync)) }, onClick = {
                            menu = false
                            preferenceSync = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_sources)) }, onClick = {
                            menu = false
                            state.session?.let { navigator.push(SuwayomiBrowseScreen(it.id)) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_categories)) }, onClick = {
                            menu = false
                            state.session?.let { navigator.push(SuwayomiCategoriesScreen(it.id)) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_server_downloads)) }, onClick = {
                            menu = false
                            state.session?.let { navigator.push(SuwayomiDownloadsScreen(it.id)) }
                        })

                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_appearance)) }, onClick = {
                            menu = false
                            navigator.push(SettingsAppearanceScreen)
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_reader_settings)) }, onClick = {
                            menu = false
                            navigator.push(SettingsReaderScreen)
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_incognito)) }, trailingIcon = { Checkbox(incognito, null) }, onClick = {
                            preferences.incognitoMode().set(!incognito)
                            menu = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_replace)) }, onClick = {
                            menu = false
                            replace = true
                        })
                    }
                }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                val session = state.session
                if (session == null) {
                    ServerSetup(state.loading, state.error, model::connect)
                } else {
                    PendingChanges(session)
                    if (state.error != null) {
                        if (state.error == SuwayomiFailure.AUTHENTICATION_REQUIRED) {
                            TextButton(onClick = {
                                loginName = state.user?.username.orEmpty()
                                reauthenticate = true
                            }) { Text(stringResource(KMR.strings.suwayomi_sign_in_again)) }
                        }
                        ConnectionError(state.error!!, { model.refresh() }, { replace = true })
                    } else {
                        Text(
                            listOfNotNull(state.user?.username, state.info?.let { "${it.name} ${it.version}" }).joinToString(" · "),
                            Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (incognito) Text(stringResource(KMR.strings.suwayomi_incognito_notice), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        SuwayomiLibraryContent(state, model, navigator, incognito, Modifier.weight(1f))
                    }
                }
            }
        }
        if (reauthenticate) {
            AlertDialog(
                onDismissRequest = {
                    reauthenticate = false
                    loginPassword = ""
                },
                title = { Text(stringResource(KMR.strings.suwayomi_sign_in_again)) },
                text = {
                    Column {
                        Text(stringResource(KMR.strings.suwayomi_reauthenticate_notice))
                        OutlinedTextField(loginName, { loginName = it }, label = { Text(stringResource(KMR.strings.suwayomi_username)) }, singleLine = true)
                        OutlinedTextField(loginPassword, { loginPassword = it }, label = { Text(stringResource(KMR.strings.suwayomi_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        model.reauthenticate(loginName, loginPassword)
                        reauthenticate = false
                        loginPassword = ""
                    }) { Text(stringResource(KMR.strings.suwayomi_sign_in)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        reauthenticate = false
                        loginPassword = ""
                    }) { Text(stringResource(KMR.strings.suwayomi_cancel)) }
                },
            )
        }
        if (preferenceSync) {
            AlertDialog(
                onDismissRequest = { preferenceSync = false },
                title = { Text(stringResource(KMR.strings.suwayomi_preference_sync)) },
                text = { Text(stringResource(KMR.strings.suwayomi_preference_sync_notice)) },
                confirmButton = {
                    TextButton(onClick = {
                        preferenceSync = false
                        model.readingPreferences(true)
                    }) { Text(stringResource(KMR.strings.suwayomi_upload_preferences)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        preferenceSync = false
                        model.readingPreferences(false)
                    }) { Text(stringResource(KMR.strings.suwayomi_apply_preferences)) }
                },
            )
        }
        if (replace) {
            AlertDialog(
                onDismissRequest = { replace = false },
                title = { Text(stringResource(KMR.strings.suwayomi_replace)) },
                text = { Text(stringResource(KMR.strings.suwayomi_disconnect_notice)) },
                confirmButton = {
                    TextButton(onClick = {
                        replace = false
                        model.disconnect()
                    }) { Text(stringResource(KMR.strings.suwayomi_disconnect)) }
                },
                dismissButton = { TextButton(onClick = { replace = false }) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
            )
        }
    }
}

@Composable
private fun ServerSetup(loading: Boolean, error: SuwayomiFailure?, connect: (String, SuwayomiAuthMode, String, String) -> Any) {
    var url by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(SuwayomiAuthMode.NONE) }
    var username by rememberSaveable { mutableStateOf("") }
    // Credentials must not be serialized into saved-instance-state or screenshots of diagnostics.
    var password by remember { mutableStateOf("") }
    var modes by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(KMR.strings.suwayomi_connect), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(KMR.strings.suwayomi_setup_notice))
        OutlinedTextField(url, { url = it }, label = { Text(stringResource(KMR.strings.suwayomi_server_url)) }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth())
        Box {
            TextButton(onClick = { modes = true }, enabled = !loading) { Text(stringResource(KMR.strings.suwayomi_authentication) + ": " + mode.name) }
            DropdownMenu(modes, { modes = false }) {
                SuwayomiAuthMode.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(item.name) }, onClick = {
                        mode = item
                        modes = false
                    })
                }
            }
        }
        if (mode != SuwayomiAuthMode.NONE) {
            OutlinedTextField(username, { username = it }, label = { Text(stringResource(KMR.strings.suwayomi_username)) }, singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(KMR.strings.suwayomi_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !loading, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { connect(url, mode, username, password) }, enabled = !loading && url.isNotBlank()) { Text(stringResource(KMR.strings.suwayomi_connect)) }
        if (loading) CircularProgressIndicator()
    }
}

class SuwayomiMangaScreen(private val sessionId: String, private val mangaId: Int) : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiMangaScreenModel(sessionId, mangaId) }
        val preferences = remember { Injekt.get<BasePreferences>() }
        val incognito by preferences.incognitoMode().collectAsState()
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val backend = remember { Injekt.get<SuwayomiBackend>() }
        val current by backend.session.collectAsState()
        val session = current?.takeIf { it.id == sessionId }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
        Scaffold(topBar = {
            TopAppBar(title = { Text(state.manga?.title ?: stringResource(KMR.strings.suwayomi_manga), maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            }, actions = {
                IconButton(onClick = { model.refresh() }, enabled = !state.loading) { Icon(Icons.Default.Refresh, stringResource(KMR.strings.suwayomi_reload)) }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (session == null) {
                    ConnectionError(SuwayomiFailure.SESSION_CHANGED, { navigator.pop() })
                    return@Column
                }
                PendingChanges(session)
                if (incognito) Text(stringResource(KMR.strings.suwayomi_incognito_notice), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error != null) {
                    ConnectionError(state.error!!, { model.refresh() })
                    return@Column
                }
                LazyColumn {
                    state.manga?.let { manga ->
                        item {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                ServerCover(session, manga.thumbnailUrl, Modifier.width(100.dp).height(150.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(manga.title, style = MaterialTheme.typography.titleLarge)
                                    Text(listOfNotNull(manga.author, manga.artist).distinct().joinToString(" · "))
                                    Button(onClick = { model.setLibrary(!manga.user.inLibrary) }, enabled = !state.loading) {
                                        Text(stringResource(if (manga.user.inLibrary) KMR.strings.suwayomi_remove_library else KMR.strings.suwayomi_add_library))
                                    }
                                }
                            }
                            manga.description?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                            TextButton(onClick = { navigator.push(SuwayomiTrackingScreen(sessionId, mangaId)) }) { Text(stringResource(KMR.strings.suwayomi_tracking)) }
                            TextButton(onClick = { navigator.push(SuwayomiCategoriesScreen(sessionId, mangaId)) }) { Text(stringResource(KMR.strings.suwayomi_categories)) }
                            TextButton(onClick = { model.refresh(fetch = true) }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_fetch_source)) }
                            HorizontalDivider()
                        }
                    }
                    if (state.chapters.isEmpty() && !state.loading) item { Text(stringResource(KMR.strings.suwayomi_empty_chapters), Modifier.padding(24.dp)) }
                    items(state.chapters, key = { it.id }) { chapter ->
                        var actions by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                context.startActivity(ReaderActivity.newIntent(context, SuwayomiMode.readerId(mangaId), SuwayomiMode.readerId(chapter.id)))
                            }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(chapter.name, color = if (chapter.user.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                Text(
                                    listOfNotNull(chapter.scanlator, stringResource(if (chapter.user.isRead) KMR.strings.suwayomi_read else KMR.strings.suwayomi_page, chapter.user.lastPageRead + 1), if (chapter.user.isBookmarked) stringResource(KMR.strings.suwayomi_bookmarked) else null).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Box {
                                IconButton(onClick = { actions = true }) { Icon(Icons.Default.MoreVert, stringResource(KMR.strings.suwayomi_chapter_actions)) }
                                DropdownMenu(actions, { actions = false }) {
                                    if (state.canDownload) {
                                        DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_download_on_server)) }, onClick = {
                                            actions = false
                                            model.download("enqueue", chapter.id)
                                        })
                                        if (chapter.user.isDownloaded) {
                                            DropdownMenuItem(text = { Text(stringResource(KMR.strings.suwayomi_delete_server_download)) }, onClick = {
                                                actions = false
                                                model.download("delete", chapter.id)
                                            })
                                        }
                                    }

                                    DropdownMenuItem(enabled = !incognito, text = { Text(stringResource(KMR.strings.suwayomi_mark_read)) }, onClick = {
                                        actions = false
                                        model.chapterAction(chapter, ChapterIntentKind.MARK_READ)
                                    })
                                    DropdownMenuItem(enabled = !incognito, text = { Text(stringResource(KMR.strings.suwayomi_mark_unread)) }, onClick = {
                                        actions = false
                                        model.chapterAction(chapter, ChapterIntentKind.MARK_UNREAD)
                                    })
                                    DropdownMenuItem(enabled = !incognito, text = { Text(stringResource(if (chapter.user.isBookmarked) KMR.strings.suwayomi_remove_bookmark else KMR.strings.suwayomi_add_bookmark)) }, onClick = {
                                        actions = false
                                        model.chapterAction(chapter, ChapterIntentKind.BOOKMARK)
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
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

@Composable
private fun PendingChanges(session: SuwayomiSession) {
    val pending by session.pending.collectAsState()
    val failure by session.mutationFailure.collectAsState()
    val scope = rememberCoroutineScope()
    var discard by remember { mutableStateOf(false) }
    if (pending.isNotEmpty()) {
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
private fun ConnectionError(failure: SuwayomiFailure, retry: () -> Unit, configure: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(failureText(failure), color = MaterialTheme.colorScheme.error)
        Button(onClick = retry) { Text(stringResource(KMR.strings.suwayomi_retry)) }
        configure?.let { TextButton(onClick = it) { Text(stringResource(KMR.strings.suwayomi_replace)) } }
    }
}

@Composable
internal fun failureText(failure: SuwayomiFailure): String = stringResource(
    when (failure) {
        SuwayomiFailure.CONNECTION_REQUIRED -> KMR.strings.suwayomi_connection_required
        SuwayomiFailure.AUTHENTICATION_REQUIRED -> KMR.strings.suwayomi_auth_required
        SuwayomiFailure.PERMISSION_DENIED -> KMR.strings.suwayomi_permission_denied
        SuwayomiFailure.NETWORK -> KMR.strings.suwayomi_network_error
        SuwayomiFailure.SERVER -> KMR.strings.suwayomi_server_error
        SuwayomiFailure.CONTRACT -> KMR.strings.suwayomi_contract_error
        SuwayomiFailure.SESSION_CHANGED -> KMR.strings.suwayomi_session_changed
    },
)
// KMK <--
