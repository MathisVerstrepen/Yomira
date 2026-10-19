package eu.kanade.tachiyomi.ui.library

import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class LibrarySettingsScreenModel(
    /* KMK --> */
    override /* KMK <-- */ val preferences: BasePreferences = Injekt.get(),
    /* KMK --> */
    override /* KMK <-- */ val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val setDisplayMode: SetDisplayMode = Injekt.get(),
    private val setSortModeForCategory: SetSortModeForCategory = Injekt.get(),
    trackerManager: TrackerManager = Injekt.get(),
) : ScreenModel, /* KMK --> */ LibrarySettingsController /* KMK <-- */ {

    /* KMK --> */ override /* KMK <-- */ val trackersFlow = trackerManager.loggedInTrackersFlow()
        .stateIn(
            scope = screenModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds.inWholeMilliseconds),
            initialValue = trackerManager.loggedInTrackers(),
        )

    // SY -->
    /* KMK --> */ override /* KMK <-- */ val grouping by libraryPreferences.groupLibraryBy().asState(screenModelScope)

    // SY <--
    /* KMK --> */ override /* KMK <-- */ fun toggleFilter(preference: (LibraryPreferences) -> Preference<TriState>) {
        preference(libraryPreferences).getAndSet {
            it.next()
        }
    }

    /* KMK --> */ override /* KMK <-- */ fun toggleTracker(id: Int) {
        toggleFilter { libraryPreferences.filterTracking(id) }
    }

    /* KMK --> */ override /* KMK <-- */ fun setDisplayMode(mode: LibraryDisplayMode) {
        setDisplayMode.await(mode)
    }

    /* KMK --> */ override /* KMK <-- */ fun setSort(category: Category?, mode: LibrarySort.Type, direction: LibrarySort.Direction) {
        screenModelScope.launchIO {
            setSortModeForCategory.await(category, mode, direction)
        }
    }

    // SY -->
    /* KMK --> */ override /* KMK <-- */ fun setGrouping(grouping: Int) {
        screenModelScope.launchIO {
            libraryPreferences.groupLibraryBy().set(grouping)
        }
    }
    // SY <--
}
