// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.suwayomi.OfflineDownload
import eu.kanade.tachiyomi.data.suwayomi.OfflineSnapshot
import eu.kanade.tachiyomi.data.suwayomi.OfflineState
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun PhoneStorageCard(used: Long, limitMiB: Int, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(KMR.strings.suwayomi_phone_storage), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(stringResource(KMR.strings.suwayomi_offline_usage, Formatter.formatShortFileSize(context, used), Formatter.formatShortFileSize(context, limitMiB * 1024L * 1024)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (used.toFloat() / (limitMiB * 1024L * 1024)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.outlineVariant, drawStopIndicator = {})
        }
    }
}

@Composable
internal fun PhoneDownloadList(
    snapshot: OfflineSnapshot,
    state: LazyListState,
    busy: Boolean,
    header: @Composable () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onTitle: (Int) -> Unit,
    onRead: (OfflineDownload) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val saved = snapshot.downloads.count { it.state == OfflineState.COMPLETE }
    val waiting = snapshot.downloads.size - saved
    val canRetry = snapshot.downloads.any { it.state !in listOf(OfflineState.COMPLETE, OfflineState.QUEUED, OfflineState.DOWNLOADING) }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                header()
                Text(stringResource(KMR.strings.suwayomi_phone_overview, saved, waiting), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snapshot.downloads.isNotEmpty() || snapshot.smartTitles.isNotEmpty() || snapshot.paused) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onPause, enabled = !busy && (snapshot.downloads.isNotEmpty() || snapshot.smartTitles.isNotEmpty() || snapshot.paused)) {
                            Icon(if (snapshot.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(if (snapshot.paused) KMR.strings.suwayomi_phone_resume else KMR.strings.suwayomi_phone_pause), Modifier.padding(start = 8.dp))
                        }
                        if (canRetry) OutlinedButton(onClick = onRetry, enabled = !busy) { Text(stringResource(KMR.strings.suwayomi_retry)) }
                    }
                }
                if (snapshot.paused) Text(stringResource(KMR.strings.suwayomi_offline_paused), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (snapshot.downloads.isEmpty()) {
            item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(KMR.strings.suwayomi_phone_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(KMR.strings.suwayomi_phone_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        snapshot.downloads.groupBy { it.mangaId }.forEach { (mangaId, downloads) ->
            val title = snapshot.titles[mangaId]
            item(key = "title-$mangaId") {
                Surface(onClick = { onTitle(mangaId) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title?.manga?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(pluralStringResource(KMR.plurals.suwayomi_phone_chapters, downloads.size, downloads.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            items(downloads, key = { "chapter-${it.chapterId}" }) { download ->
                val chapter = title?.chapters?.firstOrNull { it.id == download.chapterId }
                PhoneDownloadRow(download, chapter?.name.orEmpty(), onRead = { onRead(download) }, onRemove = { onRemove(download.chapterId) })
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PhoneDownloadRow(download: OfflineDownload, name: String, onRead: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val complete = download.state == OfflineState.COMPLETE
    val readLabel = stringResource(KMR.strings.suwayomi_offline_read)
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = complete, onClickLabel = readLabel, onClick = onRead).padding(start = 32.dp, end = 8.dp).heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(phoneOfflineStatus(download.state), download.pages, download.total), style = MaterialTheme.typography.bodySmall, color = if (download.state == OfflineState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (download.automatic) Text(stringResource(KMR.strings.suwayomi_phone_auto), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (complete) Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(KMR.strings.suwayomi_phone_chapter_actions, name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (complete) {
                        DropdownMenuItem(text = { Text(readLabel) }, onClick = {
                            menu = false
                            onRead()
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(if (complete) KMR.strings.suwayomi_offline_delete else MR.strings.action_cancel), color = MaterialTheme.colorScheme.error) }, onClick = {
                        menu = false
                        onRemove()
                    })
                }
            }
        }
        if (download.state == OfflineState.DOWNLOADING) LinearProgressIndicator(progress = { if (download.total == 0) 0f else download.pages.toFloat() / download.total }, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))
        HorizontalDivider(Modifier.padding(horizontal = 32.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
internal fun PhoneSettingSwitch(title: String, description: String?, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PhoneSettingAction(title: String, value: String? = null, description: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun PhoneSectionTitle(title: String) {
    Text(title, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

internal fun phoneOfflineStatus(state: OfflineState) = when (state) {
    OfflineState.QUEUED -> KMR.strings.suwayomi_offline_queued
    OfflineState.DOWNLOADING -> KMR.strings.suwayomi_offline_progress
    OfflineState.WIFI -> KMR.strings.suwayomi_offline_wait_wifi
    OfflineState.STORAGE -> KMR.strings.suwayomi_offline_wait_storage
    OfflineState.NETWORK -> KMR.strings.suwayomi_offline_wait_network
    OfflineState.ERROR -> KMR.strings.suwayomi_offline_error
    OfflineState.COMPLETE -> KMR.strings.suwayomi_offline_complete
}
// KMK <--
