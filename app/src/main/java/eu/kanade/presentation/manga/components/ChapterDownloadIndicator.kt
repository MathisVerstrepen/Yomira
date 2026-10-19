package eu.kanade.presentation.manga.components

// KMK -->
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
// KMK <--

enum class ChapterDownloadAction {
    START,
    START_NOW,
    CANCEL,
    DELETE,
}

@Composable
fun ChapterDownloadIndicator(
    enabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
    // KMK -->
    serverDownloaded: Boolean? = null,
    // KMK <--
) {
    // KMK -->
    val downloadState = downloadStateProvider()
    if (serverDownloaded != null && (serverDownloaded || downloadState == Download.State.DOWNLOADED)) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            if (serverDownloaded) {
                DownloadLocationIndicator(enabled, phone = false) { onClick(ChapterDownloadAction.START) }
            }
            if (downloadState == Download.State.DOWNLOADED) {
                DownloadLocationIndicator(enabled, phone = true) { onClick(ChapterDownloadAction.START) }
            } else if (downloadState != Download.State.NOT_DOWNLOADED) {
                // Keep phone progress/retry controls visible alongside the server's saved copy.
                ChapterDownloadIndicator(enabled, downloadStateProvider, downloadProgressProvider, onClick)
            }
        }
        return
    }
    when (downloadState) {
        // KMK <--
        Download.State.NOT_DOWNLOADED -> NotDownloadedIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
        Download.State.QUEUE, Download.State.DOWNLOADING -> DownloadingIndicator(
            enabled = enabled,
            modifier = modifier,
            downloadState = downloadState,
            downloadProgressProvider = downloadProgressProvider,
            onClick = onClick,
        )
        Download.State.DOWNLOADED -> DownloadedIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
        Download.State.ERROR -> ErrorIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
    }
}

@Composable
private fun NotDownloadedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.START_NOW) },
                onClick = { onClick(ChapterDownloadAction.START) },
            )
            .secondaryItemAlpha(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_download_chapter_24dp),
            contentDescription = stringResource(MR.strings.manga_download),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.primary, // KMK: onSurfaceVariant
        )
    }
}

@Composable
private fun DownloadingIndicator(
    enabled: Boolean,
    downloadState: Download.State,
    downloadProgressProvider: () -> Int,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.CANCEL) },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val arrowColor: Color
        val strokeColor = MaterialTheme.colorScheme.primary // KMK: onSurfaceVariant
        val downloadProgress = downloadProgressProvider()
        val indeterminate = downloadState == Download.State.QUEUE ||
            (downloadState == Download.State.DOWNLOADING && downloadProgress == 0)
        if (indeterminate) {
            arrowColor = strokeColor
            CircularProgressIndicator(
                modifier = IndicatorModifier,
                color = strokeColor,
                strokeWidth = IndicatorStrokeWidth,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
            )
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = downloadProgress / 100f,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
            )
            arrowColor = if (animatedProgress < 0.5f) {
                strokeColor
            } else {
                MaterialTheme.colorScheme.background
            }
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = IndicatorModifier,
                color = strokeColor,
                strokeWidth = IndicatorSize / 2,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        }
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_start_downloading_now)) },
                onClick = {
                    onClick(ChapterDownloadAction.START_NOW)
                    isMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_cancel)) },
                onClick = {
                    onClick(ChapterDownloadAction.CANCEL)
                    isMenuExpanded = false
                },
            )
        }
        Icon(
            imageVector = Icons.Outlined.ArrowDownward,
            contentDescription = null,
            modifier = ArrowModifier,
            tint = arrowColor,
        )
    }
}

@Composable
private fun DownloadedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { isMenuExpanded = true },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.primary, // KMK: onSurfaceVariant
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_delete)) },
                onClick = {
                    onClick(ChapterDownloadAction.DELETE)
                    isMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun ErrorIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.START) },
                onClick = { onClick(ChapterDownloadAction.START) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(MR.strings.chapter_error),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

private fun Modifier.commonClickable(
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) = this.combinedClickable(
    enabled = enabled,
    onLongClick = {
        onLongClick()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    },
    onClick = onClick,
    role = Role.Button,
    interactionSource = null,
    indication = ripple(
        bounded = false,
        radius = IconButtonTokens.StateLayerSize / 2,
    ),
)

internal val IndicatorSize = 26.dp
private val IndicatorPadding = 2.dp

// To match composable parameter name when used later
private val IndicatorStrokeWidth = IndicatorPadding

private val IndicatorModifier = Modifier
    .size(IndicatorSize)
    .padding(IndicatorPadding)
private val ArrowModifier = Modifier
    .size(IndicatorSize - 7.dp)

// KMK -->
@Composable
private fun DownloadLocationIndicator(enabled: Boolean, phone: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(IconButtonTokens.StateLayerSize).commonClickable(
            enabled = enabled,
            hapticFeedback = LocalHapticFeedback.current,
            onLongClick = onClick,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(IndicatorSize)) {
            Icon(
                imageVector = if (phone) Icons.Outlined.PhoneAndroid else Icons.Outlined.Storage,
                contentDescription = stringResource(if (phone) KMR.strings.suwayomi_downloaded_on_phone else KMR.strings.suwayomi_downloaded_on_server),
                modifier = Modifier.size(IndicatorSize),
                tint = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).size(12.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
// KMK <--
