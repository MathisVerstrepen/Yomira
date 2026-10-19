// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerActivityChapter
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiMode
import eu.kanade.tachiyomi.data.suwayomi.activityPage
import eu.kanade.tachiyomi.data.suwayomi.coverUrl
import eu.kanade.tachiyomi.data.suwayomi.updateLibraryOnServer
import eu.kanade.tachiyomi.data.suwayomi.updateRunning
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiActivityModel(private val sessionId: String, private val history: Boolean) : StateScreenModel<SuwayomiActivityModel.State>(State()) {
    private val backend = Injekt.get<SuwayomiBackend>()
    private val mutex = Mutex()
    private val refreshTask = SuwayomiRefreshTask()
    fun refresh(next: Boolean = false, fetch: Boolean = false) = refreshTask.launch(screenModelScope, next to fetch) {
        mutex.withLock {
            mutableState.update { it.copy(loading = next || fetch || !it.hasLoaded, error = null) }
            try {
                val session = backend.requireSession(sessionId)
                if (fetch && !session.updateRunning()) session.updateLibraryOnServer()
                val previous = state.value
                if (next && previous.hasLoaded && !previous.hasNext) {
                    mutableState.update { it.copy(loading = false) }
                    return@withLock
                }
                val snapshot = readServerWindow(pages = if (next) 1 else previous.pages.coerceAtLeast(1), after = if (next) previous.cursor else null, keyOf = ServerActivityChapter::id) { cursor -> session.activityPage(history, cursor) }
                backend.requireSession(sessionId)
                val running = if (history) false else session.updateRunning()
                mutableState.update {
                    it.copy(
                        chapters = ((if (next) it.chapters else emptyList()) + snapshot.items).distinctBy(ServerActivityChapter::id),
                        cursor = snapshot.cursor,
                        hasNext = snapshot.hasNext,
                        pages = if (next) it.pages + snapshot.pages else snapshot.pages,
                        loading = false,
                        hasLoaded = true,
                        updating = running,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    fun act(action: suspend (eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession) -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                action(backend.requireSession(sessionId))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
                return@launch
            }
            mutableState.update { it.copy(loading = false) }
        }
        refresh()
    }
    data class State(val chapters: List<ServerActivityChapter> = emptyList(), val cursor: String? = null, val hasNext: Boolean = false, val pages: Int = 0, val hasLoaded: Boolean = false, val loading: Boolean = true, val updating: Boolean = false, val error: SuwayomiFailure? = null)
}

/** Legacy saved navigation delegates to the standard tab presentation. */
class SuwayomiActivityScreen(private val sessionId: String, private val history: Boolean) : Screen() {
    @Composable
    override fun Content() {
        val navigator = cafe.adriel.voyager.navigator.LocalNavigator.currentOrThrow
        val backend = remember { Injekt.get<SuwayomiBackend>() }
        val current by backend.session.collectAsState()
        if (current?.id == sessionId) SuwayomiActivityPresenter(history) else ConnectionError(SuwayomiFailure.SESSION_CHANGED, { navigator.replaceAll(eu.kanade.tachiyomi.ui.home.HomeScreen) })
    }
}
// KMK <--
