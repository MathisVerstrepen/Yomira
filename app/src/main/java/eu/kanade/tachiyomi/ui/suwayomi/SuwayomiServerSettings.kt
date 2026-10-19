// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.suwayomi.allowed
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ServerSettingsContent(
    state: SuwayomiAdministrationModel.State,
    busy: Boolean,
    background: Boolean,
    onBackground: (Boolean) -> Unit,
    onOption: (String, Boolean) -> Unit,
    onInterval: suspend (Double) -> Boolean,
    onRetry: () -> Unit,
    onClearCache: () -> Unit,
    onWebUi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var schedule by rememberSaveable { mutableStateOf(false) }
    var notificationInfo by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (!state.hasLoaded && state.error != null) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Text(failureText(state.error), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(KMR.strings.suwayomi_reload)) }
                    }
                }
            }
            if (state.hasLoaded) {
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(if (state.sharedLibrary) KMR.strings.suwayomi_settings_shared else KMR.strings.suwayomi_settings_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.options?.let { options ->
                ServerSettingsSection(stringResource(KMR.strings.suwayomi_settings_updates), Icons.Outlined.Update) {
                    state.interval?.let { hours ->
                        SettingsActionRow(
                            stringResource(KMR.strings.suwayomi_settings_schedule),
                            if (hours == 0.0) stringResource(KMR.strings.suwayomi_settings_schedule_off) else stringResource(KMR.strings.suwayomi_settings_schedule_every, formatHours(hours)),
                            enabled = !busy,
                            onClick = { schedule = true },
                        )
                        SettingsDivider()
                    }
                    SettingsSwitchRow(stringResource(KMR.strings.suwayomi_settings_skip_unread), options.excludeUnreadChapters, !busy) { onOption("excludeUnreadChapters", it) }
                    SettingsSwitchRow(stringResource(KMR.strings.suwayomi_settings_skip_not_started), options.excludeNotStarted, !busy) { onOption("excludeNotStarted", it) }
                    SettingsSwitchRow(stringResource(KMR.strings.suwayomi_settings_skip_completed), options.excludeCompleted, !busy) { onOption("excludeCompleted", it) }
                    SettingsSwitchRow(stringResource(KMR.strings.suwayomi_settings_refresh_details), options.updateMangas, !busy) { onOption("updateMangas", it) }
                }
                val canDownload = state.user?.allowed("DOWNLOAD_CHAPTERS") == true
                ServerSettingsSection(stringResource(KMR.strings.suwayomi_server_downloads), Icons.Outlined.Download) {
                    Text(
                        stringResource(if (canDownload) KMR.strings.suwayomi_settings_download_summary else KMR.strings.suwayomi_settings_download_permission),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsSwitchRow(stringResource(KMR.strings.suwayomi_settings_auto_download), options.autoDownloadNewChapters, !busy && canDownload) { onOption("autoDownloadNewChapters", it) }
                    SettingsDivider()
                    SettingsSwitchRow(stringResource(KMR.strings.suwayomi_settings_skip_unread), options.excludeEntryWithUnreadChapters, !busy && canDownload && options.autoDownloadNewChapters) { onOption("excludeEntryWithUnreadChapters", it) }
                    SettingsSwitchRow(stringResource(KMR.strings.suwayomi_settings_ignore_reuploads), options.autoDownloadIgnoreReUploads, !busy && canDownload && options.autoDownloadNewChapters) { onOption("autoDownloadIgnoreReUploads", it) }
                }
            }
            ServerSettingsSection(stringResource(KMR.strings.suwayomi_settings_phone), Icons.Outlined.Notifications) {
                SettingsSwitchRow(
                    stringResource(KMR.strings.suwayomi_settings_notifications),
                    background,
                    true,
                    description = stringResource(KMR.strings.suwayomi_settings_notifications_summary),
                    onChange = onBackground,
                )
                SettingsActionRow(stringResource(KMR.strings.suwayomi_settings_notifications_help), onClick = { notificationInfo = true })
                SettingsDivider()
                SettingsActionRow(
                    stringResource(KMR.strings.suwayomi_settings_clear_cache),
                    stringResource(KMR.strings.suwayomi_settings_cache_summary),
                    enabled = !busy,
                    icon = Icons.Outlined.CleaningServices,
                    onClick = onClearCache,
                )
            }
            ServerSettingsSection(stringResource(KMR.strings.suwayomi_settings_advanced), Icons.Outlined.OpenInNew) {
                SettingsActionRow(
                    stringResource(KMR.strings.suwayomi_settings_webui),
                    stringResource(KMR.strings.suwayomi_settings_webui_summary),
                    icon = Icons.Outlined.OpenInNew,
                    onClick = onWebUi,
                )
            }
        }
    }
    if (schedule && state.interval != null) {
        ServerScheduleDialog(state.interval, busy = busy, onDismiss = { schedule = false }, onSave = { hours ->
            scope.launch { if (onInterval(hours)) schedule = false }
        })
    }
    if (notificationInfo) {
        AlertDialog(
            onDismissRequest = { notificationInfo = false },
            icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text(stringResource(KMR.strings.suwayomi_settings_notifications)) },
            text = { Text(stringResource(KMR.strings.suwayomi_background_notice)) },
            confirmButton = { TextButton(onClick = { notificationInfo = false }) { Text(stringResource(MR.strings.action_close)) } },
        )
    }
}

@Composable
private fun ServerSettingsSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsSwitchRow(title: String, checked: Boolean, enabled: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) },
        supportingContent = description?.let { { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun SettingsActionRow(title: String, description: String? = null, enabled: Boolean = true, icon: ImageVector = Icons.Filled.ChevronRight, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = description?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f),
    )
}

private fun formatHours(hours: Double): String = if (hours % 1.0 == 0.0) hours.toLong().toString() else hours.toString()

@Composable
private fun ServerScheduleDialog(hours: Double, busy: Boolean, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var input by rememberSaveable { mutableStateOf(formatHours(hours)) }
    val value = input.trim().replace(',', '.').toDoubleOrNull()
    val valid = value != null && (value == 0.0 || (value.isFinite() && value >= 6.0))
    val changed = !busy && valid && value != hours
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Schedule, null) },
        title = { Text(stringResource(KMR.strings.suwayomi_settings_schedule)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(KMR.strings.suwayomi_settings_schedule_summary), style = MaterialTheme.typography.bodySmall)
                // Two short rows also fit narrow screens and enlarged system text.
                listOf(listOf(0, 6), listOf(12, 24)).forEach { presets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = value == preset.toDouble(),
                                enabled = !busy,
                                onClick = { input = preset.toString() },
                                label = { Text(if (preset == 0) stringResource(KMR.strings.suwayomi_settings_schedule_off) else stringResource(KMR.strings.suwayomi_settings_hours, preset)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(KMR.strings.suwayomi_update_hours)) },
                    supportingText = { Text(stringResource(KMR.strings.suwayomi_settings_schedule_hint)) },
                    isError = input.isNotEmpty() && !valid,
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (changed) onSave(value!!) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (changed) onSave(value!!) }, enabled = changed) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(KMR.strings.suwayomi_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(KMR.strings.suwayomi_cancel)) } },
    )
}
// KMK <--
