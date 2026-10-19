// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.launch
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
    val ui = remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>() }
    val showUpdates by ui.showNavUpdates().collectAsState()
    val showHistory by ui.showNavHistory().collectAsState()
    val scope = rememberCoroutineScope()
    val queue = eu.kanade.tachiyomi.ui.more.MoreTab.rememberScreenModel(tag = "server-more-${session.id}") { SuwayomiDownloadsModel(session.id) }
    val downloads by queue.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { queue.load() }
    val incognito by base.incognitoMode().collectAsState()
    val downloaded by prefs.filterDownloaded().collectAsState()
    MoreScreen(
        downloadQueueStateProvider = {
            val count = downloads.downloads?.queue?.size ?: 0
            when {
                count == 0 -> DownloadQueueState.Stopped
                downloads.downloads?.state?.uppercase() == "STARTED" -> DownloadQueueState.Downloading(count)
                else -> DownloadQueueState.Paused(count)
            }
        },
        downloadedOnly = downloaded == TriState.ENABLED_IS,
        onDownloadedOnlyChange = { prefs.filterDownloaded().set(if (it) TriState.ENABLED_IS else TriState.DISABLED) },
        incognitoMode = incognito, onIncognitoModeChange = { base.incognitoMode().set(it) },
        showNavUpdates = showUpdates, showNavHistory = showHistory,
        onClickDownloadQueue = { navigator.push(SuwayomiDownloadsScreen(session.id)) },
        onClickCategories = { navigator.push(eu.kanade.tachiyomi.ui.category.CategoryScreen(serverSessionId = session.id)) },
        onClickSettings = { navigator.push(SettingsScreen()) },
        onClickAbout = { navigator.push(SettingsScreen(SettingsScreen.Destination.About)) },
        onClickStats = { navigator.push(SuwayomiCompatibilityScreen()) },
        onClickDataAndStorage = { navigator.push(SuwayomiAdministrationScreen(session.id)) },
        onClickBatchAdd = { navigator.push(SuwayomiCompatibilityScreen()) },
        onClickUpdates = { scope.launch { HomeScreen.openTab(HomeScreen.Tab.Updates) } },
        onClickHistory = { scope.launch { HomeScreen.openTab(HomeScreen.Tab.History) } },
        onClickLibraryUpdateErrors = { navigator.push(SuwayomiCompatibilityScreen()) },
        serverBacked = true,
    )
}
// KMK <--
