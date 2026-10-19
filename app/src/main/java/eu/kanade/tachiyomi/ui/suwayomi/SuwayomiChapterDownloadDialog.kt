// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

internal data class ChapterDownloadDestinationAction(
    val label: String,
    val enabled: Boolean,
    val destructive: Boolean,
    val onClick: () -> Unit,
)

@Composable
internal fun SuwayomiChapterDownloadDialog(
    chapterLabel: String,
    count: Int,
    phoneSaved: Int,
    serverSaved: Int,
    phoneAction: ChapterDownloadDestinationAction,
    serverAction: ChapterDownloadDestinationAction?,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(KMR.strings.suwayomi_chapter_downloads)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(chapterLabel, style = MaterialTheme.typography.bodyMedium)
                DownloadDestinationOption(
                    icon = Icons.Outlined.PhoneAndroid,
                    description = if (phoneSaved > 0) {
                        stringResource(KMR.strings.suwayomi_download_saved_count, phoneSaved, count)
                    } else {
                        stringResource(KMR.strings.suwayomi_download_destination_phone_summary)
                    },
                    action = phoneAction,
                )
                if (serverAction != null) {
                    DownloadDestinationOption(
                        icon = Icons.Outlined.Storage,
                        description = if (serverSaved > 0) {
                            stringResource(KMR.strings.suwayomi_download_saved_count, serverSaved, count)
                        } else {
                            stringResource(KMR.strings.suwayomi_download_destination_server_summary)
                        },
                        action = serverAction,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_close)) }
        },
    )
}

@Composable
private fun DownloadDestinationOption(
    icon: ImageVector,
    description: String,
    action: ChapterDownloadDestinationAction,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = when {
        !action.enabled -> colors.onSurface.copy(alpha = 0.38f)
        action.destructive -> colors.error
        else -> colors.onSecondaryContainer
    }
    Surface(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (action.destructive) colors.surfaceContainerHigh else colors.secondaryContainer,
        contentColor = contentColor,
        border = if (action.destructive) BorderStroke(1.dp, colors.outlineVariant) else null,
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 72.dp).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(action.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (action.enabled) colors.onSurfaceVariant else contentColor,
                )
            }
            Icon(
                imageVector = if (action.destructive) Icons.Outlined.DeleteOutline else Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
// KMK <--
