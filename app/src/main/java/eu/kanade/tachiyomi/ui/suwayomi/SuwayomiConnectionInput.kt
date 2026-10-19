// KMK -->
package eu.kanade.tachiyomi.ui.suwayomi

import eu.kanade.tachiyomi.data.suwayomi.SuwayomiUrl

/** Prefer HTTPS for a pasted hostname, preserving explicit local HTTP and proxy paths. */
internal fun connectionUrl(input: String): String {
    val address = input.trim()
    require(address.isNotEmpty())
    return SuwayomiUrl(if ("://" in address) address else "https://$address").base.toString()
}
// KMK <--
