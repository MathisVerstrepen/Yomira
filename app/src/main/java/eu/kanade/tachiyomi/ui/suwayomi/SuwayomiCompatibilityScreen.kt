// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

class SuwayomiCompatibilityScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(KMR.strings.suwayomi_feature_availability)) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(KMR.strings.suwayomi_back)) }
            })
        }) { padding ->
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(stringResource(KMR.strings.suwayomi_availability_core))
                Text(stringResource(KMR.strings.suwayomi_availability_deferred))
                Text(stringResource(KMR.strings.suwayomi_availability_history))
                Text(stringResource(KMR.strings.suwayomi_availability_offline))
            }
        }
    }
}
// KMK <--
