// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiAuthMode
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ServerSetup(
    loading: Boolean,
    error: SuwayomiFailure?,
    connect: (String, SuwayomiAuthMode, String, String) -> Any,
    initialUrl: String = "",
    modifier: Modifier = Modifier,
    navigateUp: (() -> Unit)? = null,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var mode by rememberSaveable { mutableStateOf(SuwayomiAuthMode.UI_LOGIN) }
    var username by rememberSaveable { mutableStateOf("") }
    // Passwords remain in memory only, never in saved-instance state.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var modes by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var showServerError by remember { mutableStateOf(false) }
    val parsedUrl = remember(url) { runCatching { connectionUrl(url) }.getOrNull() }
    val needsCredentials = mode != SuwayomiAuthMode.NONE
    val urlInvalid = submitted && parsedUrl == null
    val usernameInvalid = submitted && needsCredentials && username.isBlank()
    val passwordInvalid = submitted && needsCredentials && password.isEmpty()
    val urlFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(error) { showServerError = error != null }

    fun submit() {
        if (loading) return
        submitted = true
        when {
            parsedUrl == null -> urlFocus.requestFocus()
            needsCredentials && username.isBlank() -> usernameFocus.requestFocus()
            needsCredentials && password.isEmpty() -> passwordFocus.requestFocus()
            else -> {
                showPassword = false
                showServerError = true
                focus.clearFocus()
                keyboard?.hide()
                connect(parsedUrl, mode, if (needsCredentials) username.trim() else "", if (needsCredentials) password else "")
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.3f), colors.surface, colors.surface)),
        ).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                // Keep the form centered in the page while reserving room for back navigation.
                .padding(vertical = if (navigateUp != null) 56.dp else 0.dp)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = colors.primaryContainer) {
                    Icon(painterResource(R.drawable.ic_komikku), contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.padding(10.dp).size(28.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(MR.strings.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(KMR.strings.suwayomi_setup_brand), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            showServerError = false
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(urlFocus),
                        label = { Text(stringResource(KMR.strings.suwayomi_server_url)) },
                        placeholder = { Text(stringResource(KMR.strings.suwayomi_setup_url_example)) },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        supportingText = { Text(stringResource(if (urlInvalid) KMR.strings.suwayomi_setup_url_error else KMR.strings.suwayomi_setup_url_hint)) },
                        isError = urlInvalid,
                        enabled = !loading,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = if (needsCredentials) ImeAction.Next else ImeAction.Done),
                        keyboardActions = KeyboardActions(onNext = { usernameFocus.requestFocus() }, onDone = { submit() }),
                    )
                    Box {
                        Surface(
                            onClick = { modes = true },
                            enabled = !loading,
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.Key, contentDescription = null, tint = colors.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(KMR.strings.suwayomi_setup_sign_in_method), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    Text(stringResource(authModeLabel(mode)), style = MaterialTheme.typography.bodyMedium)
                                }
                                Icon(Icons.Outlined.ExpandMore, contentDescription = null)
                            }
                        }
                        DropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
                            listOf(SuwayomiAuthMode.UI_LOGIN, SuwayomiAuthMode.NONE, SuwayomiAuthMode.BASIC_AUTH, SuwayomiAuthMode.SIMPLE_LOGIN).forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(authModeLabel(item))) },
                                    onClick = {
                                        mode = item
                                        modes = false
                                        submitted = false
                                        showServerError = false
                                        showPassword = false
                                    },
                                )
                            }
                        }
                    }
                    if (needsCredentials) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = it
                                showServerError = false
                            },
                            label = { Text(stringResource(KMR.strings.suwayomi_username)) },
                            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus).semantics { contentType = ContentType.Username },
                            singleLine = true,
                            enabled = !loading,
                            isError = usernameInvalid,
                            supportingText = if (usernameInvalid) ({ Text(stringResource(KMR.strings.suwayomi_setup_username_error)) }) else null,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                showServerError = false
                            },
                            label = { Text(stringResource(KMR.strings.suwayomi_password)) },
                            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }, enabled = !loading) {
                                    Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = stringResource(if (showPassword) KMR.strings.suwayomi_setup_hide_password else KMR.strings.suwayomi_setup_show_password))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).semantics { contentType = ContentType.Password },
                            singleLine = true,
                            enabled = !loading,
                            isError = passwordInvalid,
                            supportingText = if (passwordInvalid) ({ Text(stringResource(KMR.strings.suwayomi_setup_password_error)) }) else null,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                        )
                    }
                    if (parsedUrl?.startsWith("http://") == true) {
                        SetupMessage(stringResource(KMR.strings.suwayomi_setup_http), error = false)
                    }
                    if (showServerError && !loading && error != null) {
                        SetupMessage(
                            stringResource(
                                when (error) {
                                    SuwayomiFailure.AUTHENTICATION_REQUIRED -> KMR.strings.suwayomi_setup_auth_error
                                    SuwayomiFailure.CONTRACT -> KMR.strings.suwayomi_setup_response_error
                                    else -> failureResource(error)
                                },
                            ),
                            error = true,
                        )
                    }
                    Button(
                        onClick = { submit() },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
                        }
                        Text(stringResource(if (loading) KMR.strings.suwayomi_setup_connecting else KMR.strings.suwayomi_setup_connect))
                        if (!loading) Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                    }
                }
            }
        }
        navigateUp?.let { back ->
            IconButton(onClick = back, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(KMR.strings.suwayomi_back))
            }
        }
    }
}

private fun authModeLabel(mode: SuwayomiAuthMode) = when (mode) {
    SuwayomiAuthMode.UI_LOGIN -> KMR.strings.suwayomi_setup_auth_account
    SuwayomiAuthMode.NONE -> KMR.strings.suwayomi_setup_auth_none
    SuwayomiAuthMode.BASIC_AUTH -> KMR.strings.suwayomi_setup_auth_basic
    SuwayomiAuthMode.SIMPLE_LOGIN -> KMR.strings.suwayomi_setup_auth_legacy
}

@Composable
private fun SetupMessage(message: String, error: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (error) colors.errorContainer else colors.surfaceContainerHigh,
        contentColor = if (error) colors.onErrorContainer else colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}
// KMK <--
