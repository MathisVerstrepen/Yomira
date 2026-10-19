// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.suwayomi.ServerTracker
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

@Composable
internal fun ServerTrackerLogo(name: String) {
    // Server IDs are independent of the phone's tracker IDs.
    val logo = when (name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }) {
        "myanimelist" -> R.drawable.brand_myanimelist
        "anilist" -> R.drawable.brand_anilist
        "kitsu" -> R.drawable.brand_kitsu
        "shikimori" -> R.drawable.brand_shikimori
        "bangumi" -> R.drawable.brand_bangumi
        "mangaupdates" -> R.drawable.brand_mangaupdates
        else -> null
    }
    if (logo != null) {
        Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(44.dp).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ServerTrackerRow(tracker: ServerTracker, canBind: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val actionable = !tracker.isLoggedIn || canBind
    ListItem(
        headlineContent = { Text(tracker.name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                stringResource(if (tracker.isLoggedIn) KMR.strings.suwayomi_signed_in else KMR.strings.suwayomi_tracker_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { ServerTrackerLogo(tracker.name) },
        trailingContent = {
            if (actionable) {
                Text(
                    stringResource(if (!tracker.isLoggedIn) KMR.strings.suwayomi_sign_in else KMR.strings.suwayomi_bind),
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Icon(Icons.Filled.CheckCircle, stringResource(KMR.strings.suwayomi_signed_in), tint = MaterialTheme.colorScheme.primary)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = if (actionable) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier,
    )
}

@Composable
internal fun ServerTrackerLoginDialog(
    tracker: ServerTracker,
    username: String,
    password: String,
    callback: String,
    busy: Boolean,
    error: String?,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onCallback: (String) -> Unit,
    onAuthorize: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val autofill = LocalContext.current.getSystemService(android.view.autofill.AutofillManager::class.java)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val passwordFocus = remember { FocusRequester() }
    val oauth = tracker.authUrl != null
    val canSubmit = !busy && if (oauth) callback.isNotBlank() else username.isNotBlank() && password.isNotEmpty()
    fun dismiss() {
        autofill?.cancel()
        onDismiss()
    }
    fun submit() {
        if (!canSubmit) return
        visible = false
        focus.clearFocus()
        keyboard?.hide()
        onSubmit()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        icon = { ServerTrackerLogo(tracker.name) },
        title = { Text(tracker.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(KMR.strings.suwayomi_tracker_login_summary), style = MaterialTheme.typography.bodySmall)
                if (oauth) {
                    FilledTonalButton(onClick = onAuthorize, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.padding(end = 8.dp).size(18.dp))
                        Text(stringResource(KMR.strings.suwayomi_authorize))
                    }
                    Text(stringResource(KMR.strings.suwayomi_tracker_callback_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = callback, onValueChange = onCallback,
                        label = { Text(stringResource(KMR.strings.suwayomi_callback)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                } else {
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
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }, enabled = !busy) {
                                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (visible) KMR.strings.suwayomi_setup_hide_password else KMR.strings.suwayomi_setup_show_password))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = canSubmit) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(KMR.strings.suwayomi_sign_in))
            }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
    )
}
// KMK <--
