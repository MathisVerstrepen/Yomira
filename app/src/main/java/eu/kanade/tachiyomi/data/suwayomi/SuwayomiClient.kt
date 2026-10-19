// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** An isolated HTTP/cookie pool per connection. No source interceptors or HTTP body logging. */
internal class SuwayomiClient(
    initial: StoredSession,
    private val persist: (StoredSession) -> Unit,
) {
    val url = SuwayomiUrl(initial.baseUrl)

    @Volatile private var credentials = initial
    private val refreshMutex = Mutex()
    private val contractMutex = Mutex()
    private var contractChecked = false
    private val cookies = mutableListOf<Cookie>().apply {
        initial.cookies.mapNotNullTo(this) { Cookie.parse(url.base, it) }
    }
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                if (!this@SuwayomiClient.url.owns(url)) emptyList() else cookies.filter { it.matches(url) }
            }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (!this@SuwayomiClient.url.owns(url)) return
                synchronized(this@SuwayomiClient.cookies) {
                    cookies.forEach { cookie ->
                        this@SuwayomiClient.cookies.removeAll { it.name == cookie.name && it.path == cookie.path && it.domain == cookie.domain }
                        this@SuwayomiClient.cookies.add(cookie)
                    }
                    updateCredentials(credentials.copy(cookies = this@SuwayomiClient.cookies.map(Cookie::toString)))
                }
            }
        })
        .build()

    internal val apiContract: SuwayomiApiContract get() = credentials.apiContract

    /** Inspect capabilities, not version ordering: stable and preview expose different library models. */
    suspend fun negotiateContract() = contractMutex.withLock {
        if (contractChecked) return@withLock
        val schema = try {
            graphql("query { __type(name: \"MangaType\") { fields { name } } }")
        } catch (e: SuwayomiException) {
            // Some newer installations disable introspection. Their normal connection probes still run.
            if (e.failure == SuwayomiFailure.CONTRACT) {
                contractChecked = true
                return@withLock
            } else {
                throw e
            }
        }
        val fields = (schema["__type"] as? JsonObject)?.get("fields") as? JsonArray
            ?: throw SuwayomiException(SuwayomiFailure.UNSUPPORTED_SERVER)
        val names = fields.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }.toSet()
        val contract = when {
            "user" in names -> SuwayomiApiContract.PER_USER
            names.containsAll(setOf("inLibrary", "inLibraryAt", "unreadCount", "downloadCount", "lastReadChapter")) -> SuwayomiApiContract.SHARED_LIBRARY
            else -> throw SuwayomiException(SuwayomiFailure.UNSUPPORTED_SERVER)
        }
        if (credentials.userId != null && apiContract != contract) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
        updateCredentials(credentials.copy(apiContract = contract))
        contractChecked = true
    }

    internal fun attachUser(userId: Int) = updateCredentials(credentials.copy(userId = userId))

    suspend fun login() {
        when (credentials.mode) {
            SuwayomiAuthMode.UI_LOGIN -> {
                val tokens = rawGraphql(
                    "mutation Login(\$input: LoginInput!) { login(input: \$input) { accessToken refreshToken } }",
                    variables(
                        "input" to buildJsonObject {
                            put("username", credentials.username)
                            put("password", credentials.password)
                        },
                    ),
                    authenticate = false,
                ).getValue("login").jsonObject
                updateCredentials(
                    credentials.copy(
                        password = "",
                        accessToken = tokens.getValue("accessToken").jsonPrimitive.content,
                        refreshToken = tokens.getValue("refreshToken").jsonPrimitive.content,
                    ),
                )
            }
            SuwayomiAuthMode.SIMPLE_LOGIN -> simpleLogin()
            else -> Unit
        }
    }

    private suspend fun simpleLogin() {
        val form = FormBody.Builder().add("user", credentials.username).add("pass", credentials.password).build()
        http.newCall(Request.Builder().url(url.login).post(form).build()).read {
            if (it.code != 303) throw SuwayomiException(SuwayomiFailure.AUTHENTICATION_REQUIRED)
        }
    }

    private fun updateCredentials(value: StoredSession) {
        credentials = value
        persist(value)
    }

    private fun requestBuilder(target: HttpUrl, authenticate: Boolean = true): Request.Builder {
        require(url.owns(target))
        return Request.Builder().url(target).apply {
            if (authenticate) {
                when (credentials.mode) {
                    SuwayomiAuthMode.BASIC_AUTH -> header("Authorization", Credentials.basic(credentials.username, credentials.password))
                    SuwayomiAuthMode.UI_LOGIN -> if (credentials.accessToken.isNotBlank()) header("Authorization", "Bearer ${credentials.accessToken}")
                    else -> Unit
                }
            }
        }
    }

    private suspend fun refresh(observedToken: String) = refreshMutex.withLock {
        if (credentials.mode == SuwayomiAuthMode.UI_LOGIN) {
            if (credentials.accessToken != observedToken) return@withLock
            if (credentials.refreshToken.isBlank()) throw SuwayomiException(SuwayomiFailure.AUTHENTICATION_REQUIRED)
            val result = rawGraphql(
                "mutation Refresh(\$input: RefreshTokenInput!) { refreshToken(input: \$input) { accessToken } }",
                variables("input" to buildJsonObject { put("refreshToken", credentials.refreshToken) }),
                authenticate = false,
            ).getValue("refreshToken").jsonObject
            updateCredentials(credentials.copy(accessToken = result.getValue("accessToken").jsonPrimitive.content))
        } else if (credentials.mode == SuwayomiAuthMode.SIMPLE_LOGIN) {
            // A second caller must test the now-refreshed session before issuing another login.
            try {
                rawGraphql("query { user { id } }")
            } catch (e: SuwayomiException) {
                if (e.failure != SuwayomiFailure.AUTHENTICATION_REQUIRED) throw e
                simpleLogin()
            }
        } else {
            throw SuwayomiException(SuwayomiFailure.AUTHENTICATION_REQUIRED)
        }
    }

    suspend fun graphql(query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val token = credentials.accessToken
        return try {
            rawGraphql(query, variables)
        } catch (e: SuwayomiException) {
            if (e.failure != SuwayomiFailure.AUTHENTICATION_REQUIRED) throw e
            refresh(token)
            rawGraphql(query, variables)
        }
    }

    suspend inline fun <reified T> field(query: String, field: String, variables: JsonObject = JsonObject(emptyMap())): T {
        val data = graphql(query, variables)[field] ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)
        if (data == JsonNull) throw SuwayomiException(SuwayomiFailure.CONTRACT)
        return try {
            json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), data)
        } catch (_: kotlinx.serialization.SerializationException) {
            throw SuwayomiException(SuwayomiFailure.CONTRACT)
        }
    }

    private suspend fun rawGraphql(
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        authenticate: Boolean = true,
    ): JsonObject = withContext(Dispatchers.IO) {
        val shared = apiContract == SuwayomiApiContract.SHARED_LIBRARY
        val userProbe = shared && SharedSuwayomiApi.isUserQuery(query)
        // The shared API has no user endpoint. Authenticate a protected read before projecting its
        // single library identity; a public aboutServer response would also succeed after expiry.
        val logicalQuery = if (userProbe) "query { mangas(first: 1) { nodes { id } } }" else query
        val wireQuery = if (shared) SharedSuwayomiApi.query(logicalQuery) else logicalQuery
        val wireVariables = if (shared) SharedSuwayomiApi.variables(logicalQuery, variables) else variables
        val body = buildJsonObject {
            put("query", wireQuery)
            put("variables", wireVariables)
        }
        val request = requestBuilder(url.graphql, authenticate)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).read { response ->
            checkStatus(response)
            val envelope = try {
                json.parseToJsonElement(response.body.string()).jsonObject
            } catch (_: Exception) {
                throw SuwayomiException(SuwayomiFailure.CONTRACT)
            }
            val errors = envelope["errors"] as? JsonArray
            if (!errors.isNullOrEmpty()) {
                // The inspected server sends stack traces in messages. Classify; never expose them.
                val messages = errors.map { it.jsonObject["message"]?.jsonPrimitive?.content.orEmpty() }
                val firstLines = messages.map { it.lineSequence().first().trim() }
                val partial = (envelope["data"] as? JsonObject)?.values?.any { it != JsonNull } == true
                val refreshRejected = query.contains("refreshToken(") && messages.any {
                    it.contains("com.auth0.jwt.exceptions.") || it.contains("Token revoked by a password change") ||
                        it.contains("Cannot use access token to refresh") || it.contains("Token intended for different audience")
                }
                val failure = when {
                    partial -> SuwayomiFailure.SERVER
                    firstLines.any { it == "Unauthorized" || it.endsWith(": Unauthorized") || it.endsWith("Incorrect username or password.") } || refreshRejected -> SuwayomiFailure.AUTHENTICATION_REQUIRED
                    firstLines.any { it.contains("Forbidden") || it.contains("Missing permission") } -> SuwayomiFailure.PERMISSION_DENIED
                    errors.any { it.jsonObject["path"] == null } -> SuwayomiFailure.CONTRACT
                    else -> SuwayomiFailure.SERVER
                }
                throw SuwayomiException(failure)
            }
            val data = envelope["data"] as? JsonObject ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)
            when {
                userProbe -> buildJsonObject {
                    put(
                        "user",
                        buildJsonObject {
                            put("id", 1)
                            put("username", credentials.username)
                            put("permissions", JsonArray(emptyList()))
                            put("roles", JsonArray(listOf(JsonPrimitive("ADMIN"))))
                        },
                    )
                }
                shared -> SharedSuwayomiApi.data(data)
                else -> data
            }
        }
    }

    suspend fun image(path: String): ByteArray = fetchImage(path) { it.bytes() }

    suspend fun imageFile(path: String, destination: File, beforeWrite: (Int) -> Unit = {}) = fetchImage(path) { body ->
        destination.outputStream().use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    beforeWrite(count)
                    output.write(buffer, 0, count)
                }
            }
        }
        Unit
    }

    private suspend fun <T> fetchImage(path: String, consume: (ResponseBody) -> T): T {
        val target = url.asset(path)
        val token = credentials.accessToken
        suspend fun fetch(): T = http.newCall(requestBuilder(target).get().build()).read {
            checkStatus(it)
            if (it.body.contentType()?.type != "image") throw SuwayomiException(SuwayomiFailure.CONTRACT)
            consume(it.body)
        }
        return try {
            fetch()
        } catch (e: SuwayomiException) {
            if (e.failure != SuwayomiFailure.AUTHENTICATION_REQUIRED) throw e
            refresh(token)
            fetch()
        }
    }

    /** Both handshake credentials and UI_LOGIN's connection_init token are required. */
    fun subscribe(query: String) = callbackFlow<JsonObject> {
        val shared = apiContract == SuwayomiApiContract.SHARED_LIBRARY
        val wireQuery = if (shared) SharedSuwayomiApi.query(query) else query
        val socket = http.newWebSocket(
            requestBuilder(url.graphql).header("Sec-WebSocket-Protocol", "graphql-transport-ws").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        buildJsonObject {
                            put("type", "connection_init")
                            put(
                                "payload",
                                buildJsonObject {
                                    if (credentials.mode == SuwayomiAuthMode.UI_LOGIN) put("token", credentials.accessToken)
                                },
                            )
                        }.toString(),
                    )
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = json.parseToJsonElement(text).jsonObject
                        when (message["type"]?.jsonPrimitive?.content) {
                            "connection_ack" -> webSocket.send(
                                buildJsonObject {
                                    put("id", "1")
                                    put("type", "subscribe")
                                    put("payload", buildJsonObject { put("query", wireQuery) })
                                }.toString(),
                            )
                            "ping" -> webSocket.send(
                                buildJsonObject {
                                    put("type", "pong")
                                    message["payload"]?.let { put("payload", it) }
                                }.toString(),
                            )
                            "next" -> {
                                val payload = message.getValue("payload").jsonObject
                                if (payload["errors"]?.jsonArray?.isNotEmpty() == true) {
                                    close(SuwayomiException(SuwayomiFailure.SERVER))
                                } else {
                                    val data = payload.getValue("data").jsonObject
                                    trySend(if (shared) SharedSuwayomiApi.data(data) else data)
                                }
                            }
                            "error" -> close(SuwayomiException(SuwayomiFailure.SERVER))
                            "complete" -> close()
                        }
                    } catch (_: Exception) {
                        close(SuwayomiException(SuwayomiFailure.CONTRACT))
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(SuwayomiException(SuwayomiFailure.NETWORK))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    close(if (code == 4401 || code == 4403) SuwayomiException(SuwayomiFailure.AUTHENTICATION_REQUIRED) else null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close(if (code == 4401 || code == 4403) SuwayomiException(SuwayomiFailure.AUTHENTICATION_REQUIRED) else null)
                }
            },
        )
        awaitClose { socket.cancel() }
    }

    fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
    }

    private fun checkStatus(response: Response) {
        if (response.isSuccessful) return
        throw SuwayomiException(
            when (response.code) {
                401 -> SuwayomiFailure.AUTHENTICATION_REQUIRED
                403 -> SuwayomiFailure.PERMISSION_DENIED
                else -> SuwayomiFailure.SERVER
            },
        )
    }
}

internal fun variables(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

/** Consume responses on OkHttp's worker and retain cancellation until the entire body is read. */
private suspend fun <T> Call.read(consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(SuwayomiException(SuwayomiFailure.NETWORK))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(consume)
                if (continuation.isActive) continuation.resumeWith(Result.success(value))
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        if (e is IOException) SuwayomiException(SuwayomiFailure.NETWORK) else e,
                    )
                }
            }
        }
    })
}
// KMK <--
