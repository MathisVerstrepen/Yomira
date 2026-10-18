// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SuwayomiTransportTest {
    @Test
    fun `concurrent rejected access tokens trigger one refresh and bounded retries`() = runBlocking {
        val refreshes = AtomicInteger()
        withServer({ exchange ->
            val query = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject.getValue("query").jsonPrimitive.content
            when {
                "refreshToken(" in query -> {
                    refreshes.incrementAndGet()
                    exchange.json("""{"data":{"refreshToken":{"accessToken":"fresh"}}}""")
                }
                exchange.requestHeaders.getFirst("Authorization") != "Bearer fresh" -> exchange.json("""{"errors":[{"message":"Exception while fetching data: Unauthorized\r\nprivate server stack"}],"data":{"aboutServer":null}}""")
                else -> exchange.json("""{"data":{"aboutServer":{"name":"Suwayomi","version":"test"}}}""")
            }
        }) { base ->
            val client = SuwayomiClient(StoredSession(baseUrl = base, mode = SuwayomiAuthMode.UI_LOGIN, accessToken = "expired", refreshToken = "refresh")) {}
            try {
                val results = (1..12).map { async { client.field<ServerInfo>("query { aboutServer { name version } }", "aboutServer") } }.awaitAll()
                assertEquals(12, results.size)
                assertEquals(1, refreshes.get())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `simple login cookie authenticates both GraphQL and prefixed images`() = runBlocking {
        val protectedRequests = AtomicInteger()
        withServer({ exchange ->
            if (exchange.requestURI.path == "/prefix/login.html") {
                assertEquals("user=reader&pass=secret", exchange.requestBody.readAllBytes().decodeToString())
                exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=test-session; Path=/prefix/; HttpOnly")
                exchange.sendResponseHeaders(303, -1)
                exchange.close()
            } else {
                assertEquals("JSESSIONID=test-session", exchange.requestHeaders.getFirst("Cookie"))
                protectedRequests.incrementAndGet()
                if (exchange.requestURI.path.endsWith("/api/graphql")) {
                    exchange.json("""{"data":{"aboutServer":{"name":"Suwayomi","version":"test"}}}""")
                } else {
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, 3)
                    exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
                }
            }
        }) { base ->
            val client = SuwayomiClient(StoredSession(baseUrl = base, mode = SuwayomiAuthMode.SIMPLE_LOGIN, username = "reader", password = "secret")) {}
            try {
                client.login()
                client.field<ServerInfo>("query { aboutServer { name version } }", "aboutServer")
                assertEquals(3, client.image("/api/v1/manga/1/thumbnail").size)
                assertEquals(2, protectedRequests.get())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `partial data and server stack traces are never mistaken for a successful mutation`() = runBlocking {
        withServer({ exchange ->
            exchange.json("""{"data":{"updateChapter":{"chapter":{"id":4}}},"errors":[{"message":"secret stack with password"}]}""")
        }) { base ->
            val client = SuwayomiClient(StoredSession(baseUrl = base, mode = SuwayomiAuthMode.NONE)) {}
            try {
                val failure = runCatching { client.graphql("mutation { updateChapter { chapter { id } } }") }.exceptionOrNull() as SuwayomiException
                assertEquals(SuwayomiFailure.SERVER, failure.failure)
                assertFalse(failure.toString().contains("secret"))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `partial mutation success is never replayed to refresh authentication`() = runBlocking {
        val calls = AtomicInteger()
        withServer({ exchange ->
            calls.incrementAndGet()
            exchange.json("""{"data":{"updateManga":{"id":1}},"errors":[{"message":"Unauthorized\r\nprivate stack","path":["updateManga"]}]}""")
        }) { base ->
            val client = SuwayomiClient(StoredSession(baseUrl = base, mode = SuwayomiAuthMode.UI_LOGIN, accessToken = "expired", refreshToken = "refresh")) {}
            try {
                val error = runCatching { client.graphql("mutation { updateManga { id } }") }.exceptionOrNull() as SuwayomiException
                assertEquals(SuwayomiFailure.SERVER, error.failure)
                assertEquals(1, calls.get())
            } finally {
                client.close()
            }
        }
    }

    private suspend fun withServer(handler: (TestExchange) -> Unit, block: suspend (String) -> Unit) {
        val executor = Executors.newCachedThreadPool()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val failure = AtomicReference<Throwable?>(null)
        executor.submit {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: java.net.SocketException) {
                    break
                }
                executor.submit {
                    socket.use {
                        try {
                            handler(TestExchange(it))
                        } catch (e: Throwable) {
                            failure.set(e)
                        }
                    }
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}/prefix/")
            failure.get()?.let { throw it }
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    private fun TestExchange.json(value: String) {
        requestBody.close()
        val bytes = value.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** Minimal local HTTP fixture: known Content-Length requests and Connection: close replies. */
    private class TestExchange(private val socket: Socket) {
        val requestHeaders = Headers()
        val responseHeaders = Headers()
        val requestURI: URI
        val requestBody: ByteArrayInputStream
        val responseBody get() = socket.getOutputStream()

        init {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            fun line(): String = buildString {
                while (true) {
                    val ch = input.read()
                    check(ch >= 0)
                    if (ch == 10) break
                    if (ch != 13) append(ch.toChar())
                }
            }
            requestURI = URI(line().split(' ')[1])
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                requestHeaders.add(header.substringBefore(':'), header.substringAfter(':').trim())
            }
            requestBody = ByteArrayInputStream(input.readNBytes(requestHeaders.getFirst("Content-Length")?.toInt() ?: 0))
        }

        fun sendResponseHeaders(status: Int, length: Long) {
            val text = buildString {
                append("HTTP/1.1 $status Response\r\n")
                append("Content-Length: ${length.coerceAtLeast(0)}\r\nConnection: close\r\n")
                responseHeaders.values.forEach { (key, value) -> append("$key: $value\r\n") }
                append("\r\n")
            }
            responseBody.write(text.encodeToByteArray())
            responseBody.flush()
        }

        fun close() = socket.close()
    }

    private class Headers {
        val values = linkedMapOf<String, String>()
        fun add(key: String, value: String) {
            values[key.lowercase()] = value
        }
        fun getFirst(key: String): String? = values[key.lowercase()]
    }
}
// KMK <--
