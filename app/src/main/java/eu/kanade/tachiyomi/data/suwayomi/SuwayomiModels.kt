// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The cloud application has no executable Android source backend. */
object SuwayomiMode {
    val enabled: Boolean = true
    const val SOURCE_ID: Long = Long.MIN_VALUE

    // Negative presentation IDs cannot collide with rows from the old Android database.
    fun readerId(remoteId: Int): Long {
        require(remoteId > 0)
        return -remoteId.toLong() - 1
    }

    fun remoteId(readerId: Long): Int {
        require(readerId in (-Int.MAX_VALUE.toLong() - 1)..-2)
        return (-readerId - 1).toInt()
    }
}

/** Preserve a reverse proxy prefix for both API requests and root-relative server assets. */
class SuwayomiUrl(input: String) {
    val base: HttpUrl = input.trim().let {
        (if ("://" in it) it else "http://$it").toHttpUrl()
    }.also {
        require(it.username.isEmpty() && it.password.isEmpty())
        require(it.query == null && it.fragment == null)
    }.newBuilder().apply {
        if (!build().encodedPath.endsWith('/')) addPathSegment("")
    }.build()

    val graphql: HttpUrl = base.resolve("api/graphql")!!
    val login: HttpUrl = base.resolve("login.html")!!

    fun asset(path: String): HttpUrl {
        val resolved = when {
            path.startsWith("http://") || path.startsWith("https://") -> path.toHttpUrl()
            path.startsWith(base.encodedPath) -> base.resolve(path)!!
            else -> base.resolve(path.trimStart('/'))!!
        }
        require(owns(resolved)) { "Asset outside configured server" }
        return resolved
    }

    fun owns(url: HttpUrl): Boolean = url.scheme == base.scheme && url.host == base.host &&
        url.port == base.port && url.encodedPath.startsWith(base.encodedPath)
}

object ServerLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("ServerLong", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Long = if (decoder is JsonDecoder) {
        decoder.decodeJsonElement().jsonPrimitive.long
    } else {
        decoder.decodeString().toLong()
    }
}

@Serializable
enum class SuwayomiAuthMode { NONE, BASIC_AUTH, SIMPLE_LOGIN, UI_LOGIN }

enum class SuwayomiFailure {
    CONNECTION_REQUIRED,
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    NETWORK,
    SERVER,
    CONTRACT,
    SESSION_CHANGED,
}

/** Never include response bodies, passwords, tokens, URLs with credentials or server stack traces. */
class SuwayomiException(val failure: SuwayomiFailure) : Exception(failure.name)

@Serializable
data class ServerUser(val id: Int, val username: String, val permissions: List<String>, val roles: List<String>)

@Serializable
data class ServerInfo(val name: String, val version: String)

@Serializable
data class ServerPageInfo(val hasNextPage: Boolean, val endCursor: String? = null)

@Serializable
data class ServerConnection<T>(val nodes: List<T>, val pageInfo: ServerPageInfo, val totalCount: Int)

@Serializable
data class ServerMangaUser(val inLibrary: Boolean, val unreadCount: Int = 0, val downloadCount: Int = 0)

@Serializable
data class ServerManga(
    val id: Int,
    @Serializable(with = ServerLongSerializer::class) val sourceId: Long,
    val title: String,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String> = emptyList(),
    val status: String = "UNKNOWN",
    val user: ServerMangaUser,
)

@Serializable
data class ServerChapterUser(
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val lastPageRead: Int,
    @Serializable(with = ServerLongSerializer::class) val lastReadAt: Long,
    val isDownloaded: Boolean,
)

@Serializable
data class ServerChapter(
    val id: Int,
    val mangaId: Int,
    val name: String,
    val chapterNumber: Double,
    val sourceOrder: Int,
    val scanlator: String? = null,
    @Serializable(with = ServerLongSerializer::class) val uploadDate: Long = 0,
    val pageCount: Int,
    val user: ServerChapterUser,
)

@Serializable
data class ServerSyncConflict(val deviceName: String, val remotePage: Int)

@Serializable
data class ServerPages(val pages: List<String>, val chapter: ServerChapter, val syncConflict: ServerSyncConflict? = null)

@Serializable
data class ChapterPatch(val isRead: Boolean? = null, val lastPageRead: Int? = null, val isBookmarked: Boolean? = null)
// KMK <--
