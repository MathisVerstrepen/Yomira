// KMK -->
package eu.kanade.tachiyomi.ui.library

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.Tracker
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/** Presentation settings can be driven by either repository without coupling the sheet to SQL. */
interface LibrarySettingsController {
    val preferences: BasePreferences
    val libraryPreferences: LibraryPreferences
    val trackersFlow: StateFlow<List<Tracker>>
    val grouping: Int
    val serverBacked: Boolean get() = false
    val supportedSorts: Set<LibrarySort.Type> get() = LibrarySort.types
    fun toggleFilter(preference: (LibraryPreferences) -> Preference<TriState>)
    fun toggleTracker(id: Int)
    fun setDisplayMode(mode: LibraryDisplayMode)
    fun setSort(category: Category?, mode: LibrarySort.Type, direction: LibrarySort.Direction)
    fun setGrouping(grouping: Int)
}
// KMK <--
