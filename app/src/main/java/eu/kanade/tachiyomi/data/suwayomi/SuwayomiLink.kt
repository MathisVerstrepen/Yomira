// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Portable identity contains no session credentials and never selects a different server automatically. */
data class SuwayomiLink(val server: String, val mangaId: Int, val sourceId: Long, val mangaUrl: String) {
    fun encode(): String = "yomira://manga/$mangaId?server=${encodeValue(server)}&source=$sourceId&url=${encodeValue(mangaUrl)}"
    fun matches(manga: ServerManga): Boolean = manga.id == mangaId && manga.sourceId == sourceId && manga.url == mangaUrl
    companion object {
        fun parse(value: String): SuwayomiLink {
            require(value.length <= 16384)
            val uri = URI(value)
            require(uri.scheme in setOf("yomira", "komikku-suwayomi") && uri.host == "manga" && uri.userInfo == null && uri.port == -1 && uri.fragment == null)
            val id = uri.path.removePrefix("/").toInt()
            require(id > 0)
            val fields = requireNotNull(uri.rawQuery).split('&').map { part ->
                val pair = part.split('=', limit = 2)
                require(pair.size == 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            require(fields.map { it.first }.toSet().size == fields.size)
            val query = fields.toMap()
            require(query.keys == setOf("server", "source", "url"))
            return SuwayomiLink(SuwayomiUrl(query.getValue("server")).base.toString(), id, query.getValue("source").toLong(), query.getValue("url").also { require(it.length <= 4096) })
        }
        private fun encodeValue(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
// KMK <--
