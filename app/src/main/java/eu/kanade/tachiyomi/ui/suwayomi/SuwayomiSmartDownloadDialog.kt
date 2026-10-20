// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun SuwayomiSmartDownloadDialog(
    title: String,
    enabled: Boolean,
    count: Int,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.suwayomi_smart_downloads)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title)
                SwitchPreferenceWidget(
                    title = stringResource(KMR.strings.suwayomi_smart_enable),
                    subtitle = stringResource(KMR.strings.suwayomi_smart_title_summary, count),
                    checked = enabled,
                    onCheckedChanged = onToggle,
                )
                Text(stringResource(KMR.strings.suwayomi_smart_disable_summary))
                TextButton(onClick = onSettings) { Text(stringResource(KMR.strings.suwayomi_smart_settings)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
}
// KMK <--
