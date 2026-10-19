// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.suwayomi.ServerConnection
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiException
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource

/** Repeated resume/subscription triggers share their in-flight read, never a mutation. */
internal class SuwayomiRefreshTask {
    private val jobs = mutableMapOf<Any, Job>()

    @Synchronized
    fun launch(scope: CoroutineScope, key: Any = Unit, read: suspend () -> Unit): Job {
        jobs[key]?.takeIf { it.isActive }?.let { return it }
        return scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { read() }.also {
            jobs[key] = it
            it.start()
        }
    }

    @Synchronized
    fun cancel() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }
}

internal data class ServerWindow<T>(val items: List<T>, val total: Int, val cursor: String?, val hasNext: Boolean, val pages: Int)

/** Collect privately; a refresh must never replace a complete visible list with a partial page. */
internal suspend fun <T> readServerWindow(
    pages: Int = Int.MAX_VALUE,
    after: String? = null,
    keyOf: (T) -> Int,
    fetch: suspend (String?) -> ServerConnection<T>,
): ServerWindow<T> {
    require(pages > 0)
    val items = linkedMapOf<Int, T>()
    val cursors = mutableSetOf<String>()
    var cursor = after
    var count = 0
    var total = 0
    while (true) {
        val page = fetch(cursor)
        currentCoroutineContext().ensureActive()
        count++
        if (count == 1) total = page.totalCount
        page.nodes.forEach { items[keyOf(it)] = it }
        val next = page.pageInfo.endCursor
        if (page.pageInfo.hasNextPage && (page.nodes.isEmpty() || next == null || next == cursor || !cursors.add(next))) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        if (!page.pageInfo.hasNextPage || count >= pages) return ServerWindow(items.values.toList(), total, next, page.pageInfo.hasNextPage, count)
        cursor = next
    }
}

/** A stable-height status slot for explicit work; background reads leave it quiet. */
@androidx.compose.runtime.Composable
internal fun RefreshProgress(visible: Boolean) {
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(4.dp)) {
        if (visible) androidx.compose.material3.LinearProgressIndicator(androidx.compose.ui.Modifier.fillMaxWidth())
    }
}

@androidx.compose.runtime.Composable
internal fun rememberRefreshErrors(error: SuwayomiFailure?, hasLoaded: Boolean, retry: () -> Unit): androidx.compose.material3.SnackbarHostState {
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentRetry by androidx.compose.runtime.rememberUpdatedState(retry)
    androidx.compose.runtime.LaunchedEffect(error) {
        if (hasLoaded) {
            error?.let {
                if (snackbar.showSnackbar(context.stringResource(failureResource(it)), context.stringResource(tachiyomi.i18n.MR.strings.action_retry)) == androidx.compose.material3.SnackbarResult.ActionPerformed) currentRetry()
            }
        }
    }
    return snackbar
}
// KMK <--
