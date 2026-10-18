// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.suwayomi.ServerFilter
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.ServerSource
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.browseSource
import eu.kanade.tachiyomi.data.suwayomi.serverSources
import eu.kanade.tachiyomi.data.suwayomi.sourceFilters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiBrowseScreenModel(private val sessionId: String, private val backend: SuwayomiBackend = Injekt.get()) : StateScreenModel<SuwayomiBrowseScreenModel.State>(State()) {
    private var task: Job? = null
    private var query = ""
    private var type = "POPULAR"
    private var changes = emptyList<JsonObject>()

    fun sources() {
        task?.cancel()
        task = screenModelScope.launch(Dispatchers.IO) {
            mutableState.value = State(loading = true)
            try {
                val sources = backend.requireSession(sessionId).serverSources()
                mutableState.value = State(sources = sources)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }

    fun select(source: ServerSource) {
        task?.cancel()
        task = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(source = source, filters = emptyList(), mangas = emptyList(), loading = true, error = null, page = 0, hasNext = false) }
            try {
                val filters = backend.requireSession(sessionId).sourceFilters(source.id)
                mutableState.update { it.copy(filters = filters, loading = false) }
                search("", "POPULAR", emptyList())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }

    fun search(query: String, type: String, changes: List<JsonObject>) {
        this.query = query
        this.type = type
        this.changes = changes
        fetch(next = false)
    }

    fun next() = fetch(next = true)

    private fun fetch(next: Boolean) {
        val source = state.value.source ?: return
        val page = if (next) state.value.page + 1 else 1
        task?.cancel()
        task = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(loading = true, error = null, mangas = if (next) it.mangas else emptyList()) }
            try {
                val result = backend.requireSession(sessionId).browseSource(source.id, page, query, type, changes)
                mutableState.update { it.copy(loading = false, page = page, hasNext = result.hasNextPage, mangas = (it.mangas + result.mangas).distinctBy(ServerManga::id)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }

    data class State(
        val sources: List<ServerSource> = emptyList(),
        val source: ServerSource? = null,
        val filters: List<ServerFilter> = emptyList(),
        val mangas: List<ServerManga> = emptyList(),
        val loading: Boolean = false,
        val error: SuwayomiFailure? = null,
        val page: Int = 0,
        val hasNext: Boolean = false,
    )
}
// KMK <--
