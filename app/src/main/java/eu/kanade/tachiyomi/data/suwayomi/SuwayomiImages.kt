// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Session identity is part of every image/cache key used by the existing Komikku components. */
internal object SuwayomiImages {
    fun url(sessionId: String, path: String): String = "https://komikku-suwayomi.invalid/".toHttpUrl().newBuilder()
        .addPathSegment(sessionId).addQueryParameter("asset", path).build().toString()

    fun handles(url: String?): Boolean = url?.startsWith("https://komikku-suwayomi.invalid/") == true

    class StringFactory : coil3.fetch.Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: coil3.ImageLoader): Fetcher? =
            if (handles(data)) fetcher(data, options) else null
    }

    fun fetcher(url: String, options: Options): Fetcher = object : Fetcher {
        override suspend fun fetch(): SourceFetchResult {
            val address = url.toHttpUrl()
            val sessionId = address.pathSegments.firstOrNull() ?: throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
            val session = Injekt.get<SuwayomiBackend>().requireSession(sessionId)
            val bytes = session.image(requireNotNull(address.queryParameter("asset")))
            Injekt.get<SuwayomiBackend>().requireSession(sessionId)
            return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), null, DataSource.NETWORK)
        }
    }
}
// KMK <--
