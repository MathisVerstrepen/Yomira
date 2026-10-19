// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun SuwayomiMoreTab() {
    val backend = remember { Injekt.get<SuwayomiBackend>() }
    val current by backend.session.collectAsState()
    val session = current ?: return
    val navigator = LocalNavigator.currentOrThrow
    val base = remember { Injekt.get<BasePreferences>() }
    val prefs = remember { Injekt.get<LibraryPreferences>() }
    val incognito by base.incognitoMode().collectAsState()
    val downloaded by prefs.filterDownloaded().collectAsState()
    MoreScreen(
        downloadQueueStateProvider = { DownloadQueueState.Stopped },
        downloadedOnly = downloaded == TriState.ENABLED_IS,
        onDownloadedOnlyChange = { prefs.filterDownloaded().set(if (it) TriState.ENABLED_IS else TriState.DISABLED) },
        incognitoMode = incognito, onIncognitoModeChange = { base.incognitoMode().set(it) },
        showNavUpdates = true, showNavHistory = true,
        onClickDownloadQueue = { navigator.push(SuwayomiDownloadsScreen(session.id)) },
        onClickCategories = { navigator.push(SuwayomiCategoriesScreen(session.id)) },
        onClickSettings = { navigator.push(SettingsScreen()) },
        onClickAbout = { navigator.push(SettingsScreen(SettingsScreen.Destination.About)) },
        onClickStats = { navigator.push(SuwayomiCompatibilityScreen()) },
        onClickDataAndStorage = { navigator.push(SuwayomiAdministrationScreen(session.id)) },
        onClickBatchAdd = { navigator.push(SuwayomiCompatibilityScreen()) },
        onClickUpdates = { navigator.push(SuwayomiActivityScreen(session.id, false)) },
        onClickHistory = { navigator.push(SuwayomiActivityScreen(session.id, true)) },
        onClickLibraryUpdateErrors = { navigator.push(SuwayomiCompatibilityScreen()) },
        serverBacked = true,
    )
}
// KMK <--
