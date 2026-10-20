// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

@Composable
internal fun MigrationAllProvidersEntry(enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(KMR.strings.suwayomi_migration_all_providers), style = MaterialTheme.typography.titleMedium)
        }
    }
}

internal fun LazyListScope.migrationAllProviderResults(
    sessionId: String,
    state: SuwayomiMigrationScreenModel.State,
    counts: Map<MigrationCountKey, MigrationChapterCount>,
    expandedProviders: List<Long>,
    onToggleProvider: (Long) -> Unit,
    model: SuwayomiMigrationScreenModel,
    onSearch: () -> Unit,
    onPreview: (ServerManga) -> Unit,
) {
    val searching = state.operation == MigrationOperation.SEARCH
    val interactive = !state.loading || searching
    val checked = state.providerResults.count { it.page > 0 || it.error != null }
    val remaining = state.providerResults.count { it.page == 0 || it.error != null }
    val count = state.providerResults.sumOf { it.mangas.size }
    item(key = "search") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(KMR.strings.suwayomi_migration_all_scope), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.back() }, enabled = interactive) { Text(stringResource(KMR.strings.suwayomi_migration_change_provider)) }
            }
            MigrationTitleSearch(state.query, model::query, interactive, onSearch)
            Text(stringResource(KMR.strings.suwayomi_migration_all_progress, checked, state.providerResults.size, count), style = MaterialTheme.typography.labelLarge)
            if (searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(KMR.strings.suwayomi_migration_all_live), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = model::cancel) { Text(stringResource(KMR.strings.suwayomi_migration_all_stop)) }
            } else if (remaining > 0 && !state.loading) {
                Text(stringResource(KMR.strings.suwayomi_migration_all_remaining, remaining), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::resumeAll) { Text(stringResource(KMR.strings.suwayomi_migration_all_resume)) }
            }
        }
    }
    if (!state.loading && checked == state.providerResults.size && count == 0) {
        item(key = "empty") {
            MigrationEmpty(Icons.Outlined.SearchOff, stringResource(KMR.strings.suwayomi_migration_no_results_title), stringResource(KMR.strings.suwayomi_migration_no_results))
        }
    }
    // Preserve provider ordering as responses arrive and keep pagination local to that provider.
    state.providerResults.filter { it.mangas.isNotEmpty() || it.error != null }.forEach { provider ->
        item(key = "provider-${provider.source.id}") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MigrationProviderIcon(sessionId, provider.source, Modifier.size(32.dp))
                    Text(provider.source.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(KMR.strings.suwayomi_migration_provider_results, provider.source.lang.uppercase(Locale.ROOT), provider.mangas.size), style = MaterialTheme.typography.labelMedium)
                provider.error?.let { error ->
                    Text(failureText(error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.providerPage(provider.source.id) }, enabled = !state.loading) { Text(stringResource(KMR.strings.suwayomi_retry)) }
                }
            }
        }
        val expanded = provider.source.id in expandedProviders
        items(if (expanded) provider.mangas else provider.mangas.take(3), key = { "manga-${it.id}" }) { manga ->
            MigrationTitleRow(sessionId, manga, interactive, provider.source, counts[manga.migrationCountKey()], { model.chapterCounts.retry(manga) }) { onPreview(manga) }
        }
        if (provider.mangas.size > 3) {
            item(key = "expand-${provider.source.id}") {
                TextButton(onClick = { onToggleProvider(provider.source.id) }, enabled = interactive, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    Text(
                        text = if (expanded) stringResource(KMR.strings.suwayomi_migration_collapse_results) else stringResource(KMR.strings.suwayomi_migration_expand_results, provider.mangas.size),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        if ((expanded || provider.mangas.size <= 3) && provider.hasNext && provider.error == null) {
            item(key = "more-${provider.source.id}") {
                TextButton(onClick = {
                    if (!expanded) onToggleProvider(provider.source.id)
                    model.providerPage(provider.source.id)
                }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(KMR.strings.suwayomi_load_more)) }
            }
        }
    }
    if (state.operation == MigrationOperation.MORE) item(key = "more-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
}
// KMK <--
