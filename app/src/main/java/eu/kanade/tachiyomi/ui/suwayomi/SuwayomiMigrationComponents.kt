// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.suwayomi.MigrationChapterMatch
import eu.kanade.tachiyomi.data.suwayomi.MigrationMatchKind
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerSource
import eu.kanade.tachiyomi.data.suwayomi.SourceMigrationOptions
import eu.kanade.tachiyomi.data.suwayomi.SourceMigrationPreview
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiImages
import eu.kanade.tachiyomi.data.suwayomi.coverUrl
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

internal enum class ChapterReviewFilter {
    ALL,
    UNMATCHED,
    SAVED,
    ;

    fun includes(match: MigrationChapterMatch): Boolean = when (this) {
        ALL -> true
        UNMATCHED -> match.replacement == null
        SAVED -> match.original.user.let { it.isRead || it.isBookmarked || it.lastPageRead > 0 }
    }
}

@Composable
internal fun MigrationSteps(current: Int) {
    val labels = listOf(stringResource(KMR.strings.suwayomi_migration_step_provider), stringResource(KMR.strings.suwayomi_migration_step_title), stringResource(KMR.strings.suwayomi_migration_step_review))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index <= current
            val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(shape = CircleShape, color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        if (index < current) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = color) else Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall, color = color)
                    }
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    HorizontalDivider()
}

@Composable
internal fun MigrationSearchField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = true, label = { Text(label) }, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }, enabled = enabled) { Icon(Icons.Filled.Close, stringResource(KMR.strings.suwayomi_migration_clear_search)) }
        },
        keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = {
            focus.clearFocus()
            onSearch?.invoke()
        }, onDone = { focus.clearFocus() }),
    )
}

@Composable
internal fun MigrationTitleSearch(query: String, onChange: (String) -> Unit, enabled: Boolean, onSearch: () -> Unit) {
    // OutlinedTextField reserves half the floating label's height above its visible outline.
    val labelInset = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2 }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MigrationSearchField(query, onChange, stringResource(KMR.strings.suwayomi_migration_search_titles), enabled, Modifier.weight(1f), onSearch)
        FilledIconButton(onClick = onSearch, enabled = enabled && query.isNotBlank(), modifier = Modifier.padding(top = labelInset)) {
            Icon(Icons.Filled.Search, stringResource(KMR.strings.suwayomi_search))
        }
    }
}

@Composable
internal fun MigrationProviderRow(sessionId: String, source: ServerSource, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Text(source.name.take(1).uppercase(Locale.ROOT), style = MaterialTheme.typography.titleMedium)
                    if (source.iconUrl.isNotBlank()) AsyncImage(SuwayomiImages.url(sessionId, source.iconUrl), null, Modifier.fillMaxSize().padding(4.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleMedium)
                Text(source.lang.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MigrationProviderHeading(source: ServerSource, enabled: Boolean, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleLarge)
            Text(source.lang.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onChange, enabled = enabled) { Text(stringResource(KMR.strings.suwayomi_migration_change_provider)) }
    }
}

@Composable
internal fun MigrationProviderIcon(sessionId: String, source: ServerSource, modifier: Modifier = Modifier) {
    Surface(modifier.clearAndSetSemantics {}, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(source.name.take(1).uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall)
            if (source.iconUrl.isNotBlank()) AsyncImage(SuwayomiImages.url(sessionId, source.iconUrl), null, Modifier.fillMaxSize().padding(2.dp))
        }
    }
}

@Composable
internal fun MigrationTitleRow(sessionId: String, manga: ServerManga, enabled: Boolean, provider: ServerSource? = null, chapterCount: MigrationChapterCount? = null, onRetryCount: () -> Unit = {}, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(54.dp).height(78.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                manga.coverUrl()?.let { AsyncImage(SuwayomiImages.url(sessionId, it), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(manga.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                provider?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MigrationProviderIcon(sessionId, it, Modifier.size(20.dp))
                        Text("${it.name} · ${it.lang.uppercase(Locale.ROOT)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                manga.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (manga.user.inLibrary) Text(stringResource(KMR.strings.suwayomi_migration_already_in_library), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                MigrationTitleChapterCount(manga, chapterCount, enabled, onRetryCount)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MigrationTitleChapterCount(manga: ServerManga, state: MigrationChapterCount?, enabled: Boolean, onRetry: () -> Unit) {
    val count = state?.count ?: manga.chapters.totalCount.takeIf { it > 0 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            count != null -> Text(pluralStringResource(KMR.plurals.suwayomi_migration_provider_chapters, count, count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state?.loading == true -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(stringResource(KMR.strings.suwayomi_migration_chapters_loading), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state?.error != null -> {
                Text(stringResource(KMR.strings.suwayomi_migration_chapters_unavailable), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry, enabled = enabled) { Text(stringResource(KMR.strings.suwayomi_retry)) }
            }
            else -> Text(stringResource(KMR.strings.suwayomi_migration_chapters_unknown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MigrationComparison(preview: SourceMigrationPreview, provider: String?, enabled: Boolean, onChange: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(KMR.strings.suwayomi_migration_replacement), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onChange, enabled = enabled) { Text(stringResource(KMR.strings.suwayomi_migration_change_title)) }
            }
            Text(preview.replacement.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(KMR.strings.suwayomi_migration_from), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(preview.original.source?.name ?: stringResource(KMR.strings.suwayomi_migration_original_provider), style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(KMR.strings.suwayomi_migration_to), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(provider ?: preview.replacement.source?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun MigrationMatchSummary(preview: SourceMigrationPreview) {
    val matched = preview.matches.count { it.replacement != null }
    val unmatched = preview.matches.size - matched
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(KMR.strings.suwayomi_migration_chapters), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(KMR.strings.suwayomi_migration_match_ratio, matched, preview.matches.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { matched.toFloat() / preview.matches.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(KMR.strings.suwayomi_migration_matched_count, matched), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(KMR.strings.suwayomi_migration_unmatched_count, unmatched), style = MaterialTheme.typography.bodySmall, color = if (unmatched > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MigrationChapterFilters(preview: SourceMigrationPreview, selected: ChapterReviewFilter, onSelect: (ChapterReviewFilter) -> Unit) {
    val labels = listOf(stringResource(KMR.strings.suwayomi_migration_filter_all), stringResource(KMR.strings.suwayomi_migration_filter_unmatched), stringResource(KMR.strings.suwayomi_migration_filter_saved))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChapterReviewFilter.entries.forEachIndexed { index, filter ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(stringResource(KMR.strings.suwayomi_migration_filter_count, labels[index], preview.matches.count(filter::includes))) })
        }
    }
}

@Composable
internal fun MigrationChapterRow(match: MigrationChapterMatch) {
    val matched = match.replacement != null
    val color = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (matched) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, null, Modifier.padding(top = 2.dp).size(20.dp), tint = color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(match.original.name, style = MaterialTheme.typography.titleSmall)
            Text("→ ${match.replacement?.name ?: stringResource(KMR.strings.suwayomi_migration_no_match)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(
                    when (match.kind) {
                        MigrationMatchKind.NUMBER -> KMR.strings.suwayomi_migration_match_number
                        MigrationMatchKind.NAME -> KMR.strings.suwayomi_migration_match_name
                        MigrationMatchKind.MISSING -> KMR.strings.suwayomi_migration_match_missing
                        MigrationMatchKind.AMBIGUOUS -> KMR.strings.suwayomi_migration_match_ambiguous
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            val saved = buildList {
                if (match.original.user.isRead) add(stringResource(KMR.strings.suwayomi_migration_read))
                if (match.original.user.isBookmarked) add(stringResource(KMR.strings.suwayomi_migration_bookmarked))
                if (match.original.user.lastPageRead > 0) add(stringResource(KMR.strings.suwayomi_migration_page, match.original.user.lastPageRead + 1))
            }
            if (saved.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(saved.joinToString(" · "), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
internal fun MigrationFooter(label: String, enabled: Boolean, supporting: String, onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
            }
        }
    }
}

@Composable
internal fun MigrationEmpty(icon: ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MigrationNotice(text: String, retryLabel: String, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null)
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) { Text(retryLabel) }
        }
    }
}

@Composable
internal fun MigrationLoading(operation: MigrationOperation?, candidate: String?, onCancel: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when (operation) {
                            MigrationOperation.SEARCH -> KMR.strings.suwayomi_migration_searching
                            MigrationOperation.PREVIEW -> KMR.strings.suwayomi_migration_preparing_preview
                            else -> KMR.strings.suwayomi_migration_loading
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (operation == MigrationOperation.PREVIEW && candidate != null) Text(candidate, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onCancel) { Text(stringResource(KMR.strings.suwayomi_cancel)) }
        }
    }
}

@Composable
internal fun MigrationTransferProgress(progress: Pair<Int, Int>?, onCancel: () -> Unit) {
    val verifying = progress != null && progress.first >= progress.second
    Column(Modifier.widthIn(max = 500.dp).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(if (verifying) KMR.strings.suwayomi_migration_verifying else KMR.strings.suwayomi_migration_transferring), style = MaterialTheme.typography.headlineSmall)
        if (progress == null || verifying) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress.first.toFloat() / progress.second.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(KMR.strings.suwayomi_migration_transfer_count, progress.first, progress.second), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(KMR.strings.suwayomi_migration_safe_until_done), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCancel) { Text(stringResource(KMR.strings.suwayomi_cancel)) }
    }
}

@Composable
internal fun MigrationCompleted(title: String, keepOriginal: Boolean) {
    Column(Modifier.widthIn(max = 500.dp).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(KMR.strings.suwayomi_migration_success), style = MaterialTheme.typography.headlineSmall)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (keepOriginal) KMR.strings.suwayomi_migration_original_kept else KMR.strings.suwayomi_migration_original_replaced), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MigrationOptionsSheet(preview: SourceMigrationPreview, options: SourceMigrationOptions, incognito: Boolean, onOptions: (SourceMigrationOptions) -> Unit, onDismiss: () -> Unit, onTransfer: () -> Unit) {
    val unmatched = preview.unmatchedSaved(options)
    val chapters = preview.originalChapters
    val categories = preview.original.categories.nodes.filter { it.id != 0 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(KMR.strings.suwayomi_migration_transfer_options), style = MaterialTheme.typography.headlineSmall)
            Text(preview.replacement.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(KMR.strings.suwayomi_migration_merge_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                MigrationOption(Icons.AutoMirrored.Filled.MenuBook, stringResource(KMR.strings.suwayomi_migration_progress_option), stringResource(KMR.strings.suwayomi_migration_reading_counts, chapters.count { it.user.isRead }, chapters.count { !it.user.isRead && it.user.lastPageRead > 0 }), options.progress) { onOptions(options.copy(progress = it)) }
                MigrationOption(Icons.Outlined.BookmarkBorder, stringResource(KMR.strings.suwayomi_migration_bookmarks_option), stringResource(KMR.strings.suwayomi_migration_bookmark_count, chapters.count { it.user.isBookmarked }), options.bookmarks) { onOptions(options.copy(bookmarks = it)) }
                MigrationOption(Icons.Outlined.Folder, stringResource(KMR.strings.suwayomi_migration_categories_option), categories.joinToString { it.name }.ifEmpty { stringResource(KMR.strings.suwayomi_migration_no_categories) }, options.categories) { onOptions(options.copy(categories = it)) }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().toggleable(options.keepOriginal, role = Role.Switch) { onOptions(options.copy(keepOriginal = it)) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(KMR.strings.suwayomi_migration_keep_original_short), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(if (options.keepOriginal) KMR.strings.suwayomi_migration_keep_hint else KMR.strings.suwayomi_migration_replace_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = options.keepOriginal, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
            }
            if (unmatched > 0) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(stringResource(KMR.strings.suwayomi_migration_unmatched_saved, unmatched), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
            if (incognito) Text(stringResource(KMR.strings.suwayomi_incognito_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(stringResource(KMR.strings.suwayomi_migration_final_safety), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onTransfer, enabled = !incognito && (options.keepOriginal || unmatched == 0), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (options.keepOriginal) KMR.strings.suwayomi_migration_copy_action else KMR.strings.suwayomi_migration_replace_action))
            }
        }
    }
}

@Composable
private fun MigrationOption(icon: ImageVector, title: String, supporting: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).heightIn(min = 68.dp).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}
// KMK <--
