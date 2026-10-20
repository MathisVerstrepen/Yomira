// KMK -->
package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun YomiraAboutContent() {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val navigator = LocalNavigator.currentOrThrow
    val handleBack = LocalBackPress.current
    val repository = "https://github.com/MathisVerstrepen/komikku-suwayomi"
    Scaffold(topBar = { scrollBehavior ->
        AppBar(title = stringResource(MR.strings.pref_category_about), navigateUp = handleBack?.let { it::invoke }, scrollBehavior = scrollBehavior)
    }) { padding ->
        ScrollbarLazyColumn(contentPadding = padding) {
            item { LogoHeader() }
            item {
                Text(stringResource(KMR.strings.yomira_fork_description), modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.version),
                    subtitle = AboutScreen.getVersionName(withBuildDate = true),
                    onPreferenceClick = { context.copyToClipboard(context.stringResource(MR.strings.pref_debug_info), CrashLogUtil(context).getDebugInfo()) },
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.yomira_upstream_title),
                    subtitle = stringResource(KMR.strings.yomira_upstream_summary),
                    widget = { Icon(painterResource(R.drawable.ic_komikku), null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onPreferenceClick = { uri.openUri("https://github.com/komikku-app/komikku") },
                )
            }
            item {
                TextPreferenceWidget(title = stringResource(KMR.strings.yomira_releases), onPreferenceClick = { uri.openUri("$repository/releases/latest") })
            }
            item {
                TextPreferenceWidget(title = stringResource(KMR.strings.yomira_source_code), onPreferenceClick = { uri.openUri(repository) })
            }
            item {
                TextPreferenceWidget(title = stringResource(KMR.strings.yomira_report_issue), onPreferenceClick = { uri.openUri("$repository/issues") })
            }
            item {
                TextPreferenceWidget(title = stringResource(KMR.strings.yomira_credits), subtitle = stringResource(KMR.strings.yomira_credits_summary), onPreferenceClick = { uri.openUri("$repository#credits-and-license") })
            }
            item {
                TextPreferenceWidget(title = stringResource(MR.strings.licenses), onPreferenceClick = { navigator.push(OpenSourceLicensesScreen()) })
            }
            item {
                TextPreferenceWidget(title = stringResource(MR.strings.privacy_policy), onPreferenceClick = { uri.openUri("$repository/blob/main/docs/PRIVACY.md") })
            }
        }
    }
}
// KMK <--
