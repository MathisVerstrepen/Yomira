// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.ServerCategory
import eu.kanade.tachiyomi.data.suwayomi.ServerDownloads
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.categoryMutation
import eu.kanade.tachiyomi.data.suwayomi.downloadAction
import eu.kanade.tachiyomi.data.suwayomi.mangaCategories
import eu.kanade.tachiyomi.data.suwayomi.serverCategories
import eu.kanade.tachiyomi.data.suwayomi.serverDownloads
import eu.kanade.tachiyomi.data.suwayomi.setCategoryMembership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiCategoriesModel(private val sessionId: String, private val mangaId: Int?) : StateScreenModel<SuwayomiCategoriesModel.State>(State()) {
    private val mutex = Mutex()
    private val backend = Injekt.get<SuwayomiBackend>()
    fun load() = change {}
    fun edit(action: String, id: Int? = null, name: String? = null, position: Int? = null) = change { backend.requireSession(sessionId).categoryMutation(action, id, name, position) }
    fun membership(categoryId: Int, member: Boolean) = change { backend.requireSession(sessionId).setCategoryMembership(mangaId!!, categoryId, member) }
    private fun change(action: suspend () -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                action()
                val session = backend.requireSession(sessionId)
                val categories = session.serverCategories()
                val selected = mangaId?.let { session.mangaCategories(it).map(ServerCategory::id).toSet() }.orEmpty()
                mutableState.value = State(categories, selected, loading = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    data class State(val categories: List<ServerCategory> = emptyList(), val selected: Set<Int> = emptySet(), val loading: Boolean = true, val error: SuwayomiFailure? = null)
}

/** Restore old navigation through Komikku's category screen. */
class SuwayomiCategoriesScreen(private val sessionId: String, private val mangaId: Int? = null) : Screen() {
    @Composable
    override fun Content() {
        eu.kanade.tachiyomi.ui.category.CategoryScreen(serverSessionId = sessionId).Content()
    }
}

class SuwayomiDownloadsModel(private val sessionId: String) : StateScreenModel<SuwayomiDownloadsModel.State>(State()) {
    private val backend = Injekt.get<SuwayomiBackend>()
    private val mutex = Mutex()
    fun load() = screenModelScope.launch(Dispatchers.IO) { refresh() }
    private val actionMutex = Mutex()
    fun action(action: String, chapterId: Int? = null, position: Int? = null) = batch { downloadAction(action, chapterId, position) }
    fun batch(action: suspend eu.kanade.tachiyomi.data.suwayomi.SuwayomiSession.() -> Unit) = screenModelScope.launch(Dispatchers.IO) {
        actionMutex.withLock {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                backend.requireSession(sessionId).action()
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }
    fun observationFailed(e: Exception) {
        mutableState.update { it.copy(error = failure(e)) }
    }
    suspend fun observe() {
        backend.requireSession(sessionId).downloadEvents()
            .retryWhen { _, attempt ->
                if (attempt >= 3) {
                    false
                } else {
                    delay(2000L * (attempt + 1))
                    true
                }
            }.collect { refresh() }
    }
    private suspend fun refresh() = mutex.withLock {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val session = backend.requireSession(sessionId)
            val user = session.user()
            mutableState.value = State(session.serverDownloads(), user.roles.contains("ADMIN") || user.permissions.contains("DOWNLOAD_CHAPTERS"), loading = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }
    data class State(val downloads: ServerDownloads? = null, val canDownload: Boolean = false, val loading: Boolean = true, val error: SuwayomiFailure? = null)
}

/** Restore old navigation through the native queue screen. */
class SuwayomiDownloadsScreen(private val sessionId: String) : Screen() {
    @Composable
    override fun Content() {
        SuwayomiDownloadQueuePresenter(sessionId)
    }
}
// KMK <--
