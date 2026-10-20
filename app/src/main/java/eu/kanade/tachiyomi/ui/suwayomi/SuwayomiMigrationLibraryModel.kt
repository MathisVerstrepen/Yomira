// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.suwayomi.MigrationLibrarySource
import eu.kanade.tachiyomi.data.suwayomi.ServerManga
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.migrationLibrarySources
import eu.kanade.tachiyomi.data.suwayomi.migrationLibraryTitles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class SuwayomiMigrationLibraryModel(
    private val sessionId: String,
    private val sourceId: Long? = null,
    private val backend: SuwayomiBackend = Injekt.get(),
) : StateScreenModel<SuwayomiMigrationLibraryModel.State>(State()) {
    private val refreshTask = SuwayomiRefreshTask()

    fun refresh() = refreshTask.launch(screenModelScope) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val session = backend.requireSession(sessionId)
            val sources = if (sourceId == null) session.migrationLibrarySources() else emptyList()
            val mangas = if (sourceId != null) session.migrationLibraryTitles(sourceId) else emptyList()
            currentCoroutineContext().ensureActive()
            backend.requireSession(sessionId)
            val ids = mangas.map { it.id }.toSet()
            mutableState.update {
                it.copy(sources = sources, mangas = mangas, selected = it.selected.intersect(ids), hasLoaded = true, loading = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(loading = false, error = failure(e)) }
        }
    }

    fun toggle(id: Int) {
        mutableState.update { state ->
            if (state.loading || state.mangas.none { it.id == id }) state else state.copy(selected = if (id in state.selected) state.selected - id else state.selected + id)
        }
    }

    /** Select only visible matches; keep selections outside a search filter. */
    fun selectAll(ids: Set<Int>) {
        mutableState.update { state ->
            val valid = ids.intersect(state.mangas.map { it.id }.toSet())
            if (state.loading) state else state.copy(selected = if (valid.isNotEmpty() && state.selected.containsAll(valid)) state.selected - valid else state.selected + valid)
        }
    }

    fun queue(): List<Int> = state.value.let { state ->
        if (state.loading || state.error != null) emptyList() else state.mangas.filter { it.id in state.selected }.map { it.id }
    }

    data class State(
        val sources: List<MigrationLibrarySource> = emptyList(),
        val mangas: List<ServerManga> = emptyList(),
        val selected: Set<Int> = emptySet(),
        val hasLoaded: Boolean = false,
        val loading: Boolean = true,
        val error: SuwayomiFailure? = null,
    )
}
// KMK <--
