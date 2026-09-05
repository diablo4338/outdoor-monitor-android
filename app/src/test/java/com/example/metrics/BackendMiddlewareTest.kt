package com.example.metrics

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackendMiddlewareTest {
    private val primary = "https://primary.example/"
    private val fallback = "https://fallback.example/"

    @Test
    fun routesRequestsAndUsesEachBackendsOwnToken() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        middleware { request ->
            calls += request.url.host
            val expected = if (request.url.host == "primary.example") "primary-token" else "fallback-token"
            assertEquals("Bearer $expected", request.header("Authorization"))
            assertEquals("/metrics?limit=1", request.url.encodedPath + "?" + request.url.encodedQuery)
            response(request, if (request.url.host == "primary.example") 502 else 200)
        }.use { backend ->
            backend.request("/metrics?limit=1").close()
            backend.request("/metrics?limit=1").close()
            assertEquals(listOf("primary.example", "fallback.example", "fallback.example"), calls)
            assertFalse(backend.allBackendsUnavailable.value)
        }
    }

    @Test
    fun networkFailureSwitchesAndUnauthorizedDoesNotSwitch() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        middleware { request ->
            calls += request.url.host
            if (request.url.host == "primary.example") throw IOException("Offline")
            response(request, 401)
        }.use { backend ->
            backend.request("/metrics").use { assertEquals(401, it.code) }
            backend.request("/metrics").close()
            assertEquals(listOf("primary.example", "fallback.example", "fallback.example"), calls)
        }
        calls.clear()
        middleware { request ->
            calls += request.url.host
            response(request, 403)
        }.use { backend ->
            backend.request("/metrics").use { assertEquals(403, it.code) }
            assertEquals(listOf("primary.example"), calls)
        }
    }

    @Test
    fun probesPrimaryInBackgroundAndStopsAfterRecovery() = runBlocking {
        val probes = AtomicInteger()
        val recovered = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        middleware(probeIntervalMillis = 20) { request ->
            if (request.url.encodedPath == "/health") {
                assertEquals("primary.example", request.url.host)
                assertNull(request.header("Authorization"))
                val count = probes.incrementAndGet()
                if (count == 2) recovered.complete(Unit)
                response(request, if (count == 1) 503 else 200)
            } else {
                calls += request.url.host
                response(request, 200)
            }
        }.use { backend ->
            backend.switchBackend(BackendMiddleware.Backend.Fallback)
            withTimeout(3.seconds) { recovered.await() }
            withTimeout(3.seconds) {
                do {
                    backend.request("/metrics").close()
                    if (calls.last() != "primary.example") delay(10.milliseconds)
                } while (calls.last() != "primary.example")
            }
            delay(80.milliseconds)
            assertEquals(2, probes.get())
        }
    }

    @Test
    fun fallbackOutageDoesNotSendUserRequestsBackToPrimary() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        middleware { request ->
            calls += request.url.host
            response(request, 503)
        }.use { backend ->
            backend.request("/metrics").close()
            backend.request("/metrics").close()
            assertEquals(listOf("primary.example", "fallback.example", "fallback.example"), calls)
            assertTrue(backend.allBackendsUnavailable.value)
        }
    }

    @Test
    fun rejectsAbsoluteRoutesAndNeverSendsOtherDomainsTokenWhenMissing() = runBlocking {
        val store = MemoryTokens(mapOf("https://primary.example:443" to "primary-token"))
        val calls = AtomicInteger()
        middleware(store = store) { request ->
            calls.incrementAndGet()
            assertNull(request.header("Authorization"))
            response(request, 401)
        }.use { backend ->
            backend.switchBackend(BackendMiddleware.Backend.Fallback)
            backend.request("/metrics").use { assertEquals(401, it.code) }
            for (route in listOf("https://evil.example/token", "//evil.example/token")) {
                try {
                    backend.request(route)
                    fail("Absolute URL accepted")
                } catch (_: IllegalArgumentException) {
                }
            }
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun timeoutThen401RequiresExplicitLoginAndPreservesPrimaryToken() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val store = MemoryTokens(mapOf("https://primary.example:443" to "primary-token"))
        middleware(store = store) { request ->
            calls += request.url.host + request.url.encodedPath
            when {
                request.url.host == "primary.example" -> throw java.net.SocketTimeoutException("Timeout")
                request.url.encodedPath == "/auth/google" -> {
                    assertNull(request.header("Authorization"))
                    response(request, 200, "fallback-token")
                }
                else -> response(request, if (request.header("Authorization") == "Bearer fallback-token") 200 else 401)
            }
        }.use { backend ->
            backend.request("/metrics").use { assertEquals(401, it.code) }
            assertEquals(listOf("primary.example/metrics", "fallback.example/metrics"), calls)
            assertEquals("primary-token", store.read("https://primary.example:443"))
            assertFalse(backend.hasStoredToken())

            // Only the caller initiates login after handling the 401.
            backend.request("/auth/google", "{}", authenticated = false).use { response ->
                backend.saveToken(response, response.body.string())
            }
            backend.request("/metrics").use { assertEquals(200, it.code) }
            assertEquals(listOf(
                "primary.example/metrics", "fallback.example/metrics",
                "fallback.example/auth/google", "fallback.example/metrics",
            ), calls)
            assertEquals("primary-token", store.read("https://primary.example:443"))
            assertEquals("fallback-token", store.read("https://fallback.example:443"))
        }
    }

    @Test
    fun savesTokenForRespondingBackendEvenIfActiveBackendChanged() = runBlocking {
        val store = MemoryTokens(emptyMap())
        middleware(store = store) { response(it, 200) }.use { backend ->
            backend.request("/auth/google", "{}", authenticated = false).use { response ->
                backend.switchBackend(BackendMiddleware.Backend.Fallback)
                backend.saveToken(response, "primary-token")
            }
            assertEquals("primary-token", store.read("https://primary.example:443"))
            assertNull(store.read("https://fallback.example:443"))
            backend.clearSession()
            assertTrue(store.values.isEmpty())
        }
    }

    private fun middleware(
        store: MemoryTokens = MemoryTokens(),
        probeIntervalMillis: Long = 10_000,
        respond: (Request) -> Response,
    ) = BackendMiddleware(
        primary, fallback, store,
        client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build(),
        probeIntervalMillis = probeIntervalMillis,
    )

    private class MemoryTokens(
        initial: Map<String, String> = mapOf(
            "https://primary.example:443" to "primary-token",
            "https://fallback.example:443" to "fallback-token",
        ),
        private val onWrite: (String) -> Unit = {},
    ) : BackendTokenStore {
        val values = ConcurrentHashMap(initial)
        override fun read(origin: String) = values[origin]
        override fun write(origin: String, token: String?) {
            if (token == null) values.remove(origin) else {
                values[origin] = token
                onWrite(origin)
            }
        }
    }

    private fun response(request: Request, status: Int, body: String = "") = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("Test response")
        .body(body.toResponseBody())
        .build()
}
