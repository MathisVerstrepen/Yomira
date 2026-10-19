// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiBackend
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiFailure
import eu.kanade.tachiyomi.data.suwayomi.SuwayomiLink
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiLinkActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val value = intent.dataString.orEmpty()
        setComposeContent { Navigator(listOf(SuwayomiHomeScreen, SuwayomiLinkScreen(value))) }
    }
}

private class SuwayomiLinkScreen(private val value: String) : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SuwayomiScreenModel() }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val link = remember(value) { runCatching { SuwayomiLink.parse(value) }.getOrNull() }
        var error by remember { mutableStateOf<SuwayomiFailure?>(null) }
        var retry by remember { mutableStateOf(0) }
        LaunchedEffect(state.session, retry) {
            val session = state.session ?: return@LaunchedEffect
            if (link == null || session.baseUrl != link.server) return@LaunchedEffect
            error = null
            try {
                val manga = session.manga(link.mangaId)
                Injekt.get<SuwayomiBackend>().requireSession(session.id)
                if (link.matches(manga)) navigator.replace(serverMangaScreen(session.id, manga.id)) else error = SuwayomiFailure.SESSION_CHANGED
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = failure(e)
            }
        }
        Scaffold { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                when {
                    link == null -> Text(stringResource(KMR.strings.suwayomi_invalid_link))
                    state.session == null -> {
                        Text(stringResource(KMR.strings.suwayomi_link_connect, link.server))
                        ServerSetup(state.loading, state.error, model::connect, link.server)
                    }
                    state.session?.baseUrl != link.server -> Text(stringResource(KMR.strings.suwayomi_link_other_server, link.server))
                    error != null -> {
                        Text(failureText(error!!))
                        TextButton(onClick = { retry++ }) { Text(stringResource(KMR.strings.suwayomi_retry)) }
                    }
                    else -> CircularProgressIndicator()
                }
                TextButton(onClick = { navigator.pop() }) { Text(stringResource(KMR.strings.suwayomi_back_library)) }
            }
        }
    }
}
// KMK <--
