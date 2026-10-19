// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Restore legacy navigation through Komikku's standard browser presentation. */
class SuwayomiBrowseScreen(private val sessionId: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = cafe.adriel.voyager.navigator.LocalNavigator.currentOrThrow
        val backend = remember { Injekt.get<SuwayomiBackend>() }
        val current by backend.session.collectAsState()
        if (current?.id == sessionId) SuwayomiBrowseTab() else ConnectionError(SuwayomiFailure.SESSION_CHANGED, { navigator.replaceAll(eu.kanade.tachiyomi.ui.home.HomeScreen) })
    }
}
// KMK <--
