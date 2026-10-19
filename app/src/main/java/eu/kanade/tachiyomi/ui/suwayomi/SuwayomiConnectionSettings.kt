// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ConnectionSettingsContent(
    state: SuwayomiScreenModel.State,
    busy: Boolean,
    onSignIn: () -> Unit,
    onPreferences: () -> Unit,
    onDisconnect: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session ?: return
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.secondaryContainer) {
                        Icon(Icons.Outlined.Storage, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.padding(12.dp).size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Uri.parse(session.baseUrl).host ?: session.baseUrl, style = MaterialTheme.typography.titleMedium)
                        state.info?.let { Text("${it.name} ${it.version}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                    }
                }
                Text(
                    state.user?.username?.let { stringResource(KMR.strings.suwayomi_connection_account, it) }
                        ?: stringResource(KMR.strings.suwayomi_setup_auth_account),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer { Text(session.baseUrl, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                if (session.sharedLibrary) {
                    HorizontalDivider(color = colors.outlineVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Groups, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Text(stringResource(KMR.strings.suwayomi_connection_shared), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        PendingChanges(session)
        state.error?.let {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.errorContainer, contentColor = colors.onErrorContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(failureText(it), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onCheck, enabled = !busy) { Text(stringResource(KMR.strings.suwayomi_connection_check)) }
                }
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
            Column {
                ConnectionActionRow(
                    Icons.Outlined.Key,
                    stringResource(KMR.strings.suwayomi_sign_in_again),
                    stringResource(KMR.strings.suwayomi_connection_sign_in_summary),
                    enabled = !busy,
                    onClick = onSignIn,
                )
                HorizontalDivider(Modifier.padding(start = 56.dp), color = colors.outlineVariant)
                ConnectionActionRow(
                    Icons.Outlined.Sync,
                    stringResource(KMR.strings.suwayomi_connection_reading_preferences),
                    stringResource(KMR.strings.suwayomi_connection_preferences_summary),
                    enabled = !busy,
                    onClick = onPreferences,
                )
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
            ConnectionActionRow(
                Icons.AutoMirrored.Outlined.Logout,
                stringResource(KMR.strings.suwayomi_connection_change),
                stringResource(KMR.strings.suwayomi_connection_change_summary),
                enabled = !busy,
                destructive = true,
                onClick = onDisconnect,
            )
        }
    }
}

@Composable
private fun ConnectionActionRow(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(title, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(description, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = colors.surfaceContainer,
            headlineColor = if (destructive) colors.error else colors.onSurface,
            leadingIconColor = if (destructive) colors.error else colors.primary,
        ),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f),
    )
}

@Composable
internal fun ConnectionPreferenceDialog(onDismiss: () -> Unit, onCopy: (upload: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.suwayomi_connection_reading_preferences)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(KMR.strings.suwayomi_connection_preferences_notice), style = MaterialTheme.typography.bodySmall)
                Surface(shape = RoundedCornerShape(16.dp)) {
                    Column {
                        ConnectionActionRow(Icons.Outlined.CloudDownload, stringResource(KMR.strings.suwayomi_connection_apply), stringResource(KMR.strings.suwayomi_connection_apply_summary), compact = true, onClick = { onCopy(false) })
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                        ConnectionActionRow(Icons.Outlined.CloudUpload, stringResource(KMR.strings.suwayomi_connection_upload), stringResource(KMR.strings.suwayomi_connection_upload_summary), compact = true, onClick = { onCopy(true) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
}

@Composable
internal fun ConnectionSignInDialog(
    username: String,
    password: String,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    busy: Boolean,
    error: SuwayomiFailure?,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val canSubmit = !busy && username.isNotBlank() && password.isNotEmpty()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (!canSubmit) return
        visible = false
        focus.clearFocus()
        keyboard?.hide()
        onSubmit()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.suwayomi_sign_in_again)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(KMR.strings.suwayomi_connection_sign_in_notice), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = username, onValueChange = onUsername,
                    label = { Text(stringResource(KMR.strings.suwayomi_username)) },
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                    singleLine = true, enabled = !busy, shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                )
                OutlinedTextField(
                    value = password, onValueChange = onPassword,
                    label = { Text(stringResource(KMR.strings.suwayomi_password)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).semantics { contentType = ContentType.Password },
                    singleLine = true, enabled = !busy, shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }, enabled = !busy) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = stringResource(if (visible) KMR.strings.suwayomi_setup_hide_password else KMR.strings.suwayomi_setup_show_password)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                error?.let {
                    val message = when (it) {
                        SuwayomiFailure.AUTHENTICATION_REQUIRED -> stringResource(KMR.strings.suwayomi_connection_sign_in_error)
                        SuwayomiFailure.PERMISSION_DENIED -> stringResource(KMR.strings.suwayomi_connection_account_error)
                        else -> failureText(it)
                    }
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = canSubmit) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(KMR.strings.suwayomi_sign_in))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
}
// KMK <--
