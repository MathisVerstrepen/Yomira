// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.CategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun SuwayomiCategoriesPresenter(expectedSession: String?) {
    val navigator = LocalNavigator.currentOrThrow
    val backend = remember { Injekt.get<SuwayomiBackend>() }
    val current by backend.session.collectAsState()
    val expected by rememberSaveable { mutableStateOf(expectedSession ?: current?.id) }
    val session = current?.takeIf { it.id == expected }
    if (session == null) {
        ConnectionError(SuwayomiFailure.SESSION_CHANGED, { navigator.pop() })
        return
    }
    val model = navigator.lastItem.rememberScreenModel { SuwayomiCategoriesModel(session.id, null) }
    val state by model.state.collectAsState()
    val context = LocalContext.current
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Category?>(null) }
    var delete by remember { mutableStateOf<Category?>(null) }
    var presentation by remember { mutableStateOf(session.libraryPresentation()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.load() }
    LaunchedEffect(state.error) { state.error?.let { context.toastMessage(context.stringResource(failureResource(it))) } }
    if (state.loading && state.categories.isEmpty()) {
        LoadingScreen()
        return
    }
    val categories = state.categories.filter { it.id != 0 }.sortedBy { it.order }.map {
        Category(it.id.toLong(), it.name, it.order.toLong(), 0, presentation.category(it.id).hidden)
    }.toImmutableList()
    CategoryScreen(
        state = CategoryScreenState.Success(categories),
        navigateUp = { navigator.pop() },
        onClickCreate = { create = true },
        onClickRename = { rename = it },
        onClickDelete = { delete = it },
        onChangeOrder = { category, index -> model.edit("reorder", id = category.id.toInt(), position = index) },
        onClickHide = { category -> presentation = session.updateLibraryPresentation { value -> value.withCategory(category.id.toInt(), value.category(category.id.toInt()).copy(hidden = !category.hidden)) } },
    )
    if (create) CategoryCreateDialog({ create = false }, { model.edit("create", name = it) }, categories.map { it.name }.toImmutableList())
    rename?.let { category -> CategoryRenameDialog({ rename = null }, { model.edit("rename", id = category.id.toInt(), name = it) }, categories.map { it.name }.toImmutableList(), category.name) }
    delete?.let { category -> CategoryDeleteDialog({ delete = null }, { model.edit("delete", id = category.id.toInt()) }, category.name) }
}

private fun android.content.Context.toastMessage(message: String) {
    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
}
// KMK <--
