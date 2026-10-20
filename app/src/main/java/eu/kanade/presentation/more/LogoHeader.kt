// KMK -->
package eu.kanade.presentation.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun LogoHeader() {
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.ic_yomira), contentDescription = null, modifier = Modifier.size(72.dp))
            Text(stringResource(KMR.strings.suwayomi_app_name), style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
    }
}
// KMK <--
