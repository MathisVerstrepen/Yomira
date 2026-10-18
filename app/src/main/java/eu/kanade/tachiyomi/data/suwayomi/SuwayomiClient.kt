// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
        http.newCall(Request.Builder().url(url.login).post(form).build()).await().use {
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
    ): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val request = requestBuilder(url.graphql, authenticate)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).await().use { response ->
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
                val failure = when {
                    messages.any { it.contains("Unauthorized") } -> SuwayomiFailure.AUTHENTICATION_REQUIRED
                    messages.any { it.contains("Forbidden") || it.contains("Missing permission") } -> SuwayomiFailure.PERMISSION_DENIED
                    errors.any { it.jsonObject["path"] == null } -> SuwayomiFailure.CONTRACT
                    else -> SuwayomiFailure.SERVER
                }
                throw SuwayomiException(failure)
            }
            envelope["data"] as? JsonObject ?: throw SuwayomiException(SuwayomiFailure.CONTRACT)
        }
    }

    suspend fun image(path: String): ByteArray {
        val target = url.asset(path)
        val token = credentials.accessToken
        suspend fun fetch(): ByteArray = http.newCall(requestBuilder(target).get().build()).await().use {
            checkStatus(it)
            if (it.body.contentType()?.type != "image") throw SuwayomiException(SuwayomiFailure.CONTRACT)
            it.body.bytes()
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
                                    put("payload", buildJsonObject { put("query", query) })
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
                                    trySend(payload.getValue("data").jsonObject)
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

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(SuwayomiException(SuwayomiFailure.NETWORK))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
// KMK <--
