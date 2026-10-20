// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun SuwayomiSmartDownloadDialog(
    title: String,
    enabled: Boolean,
    count: Int,
    onDismiss: () -> Unit,
    onToggle: suspend (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.suwayomi_smart_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    Text(pluralStringResource(KMR.plurals.suwayomi_smart_buffer_summary, count, count), style = MaterialTheme.typography.bodyMedium)
                }
                Surface(shape = RoundedCornerShape(16.dp), color = if (enabled) colors.secondaryContainer else colors.surfaceContainerHigh) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .toggleable(value = enabled, enabled = !saving, role = Role.Switch) { value ->
                                saving = true
                                failed = false
                                scope.launch {
                                    try {
                                        onToggle(value)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        failed = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(KMR.strings.suwayomi_smart_auto_download), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Box(Modifier.size(width = 52.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                            if (saving) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) else Switch(checked = enabled, onCheckedChange = null)
                        }
                    }
                }
                if (failed) {
                    Text(
                        stringResource(KMR.strings.suwayomi_smart_save_error),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(KMR.strings.suwayomi_smart_disable_hint), style = MaterialTheme.typography.bodySmall)
                Surface(onClick = onSettings, enabled = !saving, shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(KMR.strings.suwayomi_smart_dialog_settings), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(KMR.strings.suwayomi_smart_dialog_settings_summary), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
}
// KMK <--
