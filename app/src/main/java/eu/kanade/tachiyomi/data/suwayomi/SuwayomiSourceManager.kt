// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/** No APK discovery, extension loading, built-in sources or direct source execution on Android. */
class SuwayomiSourceManager : SourceManager {
    private val remote = StubSource(SuwayomiMode.SOURCE_ID, "all", "Suwayomi")
    override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
    override val sources = MutableStateFlow<List<Source>>(listOf(remote))
    override fun get(sourceKey: Long): Source? = remote.takeIf { sourceKey == it.id }
    override fun getOrStub(sourceKey: Long): Source = get(sourceKey) ?: StubSource(sourceKey, "", "")
    override fun getAll(): List<Source> = listOf(remote)
    override fun getOnlineSources(): List<HttpSource> = emptyList()
    override fun getVisibleOnlineSources(): List<HttpSource> = emptyList()
    override fun getVisibleSources(): List<Source> = listOf(remote)
    override fun getStubSources(): List<StubSource> = listOf(remote)
    override suspend fun getMergedSources(mangaId: Long): List<Source> = emptyList()
}
// KMK <--
