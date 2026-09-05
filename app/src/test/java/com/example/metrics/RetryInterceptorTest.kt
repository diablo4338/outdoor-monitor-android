package com.example.metrics

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryInterceptorTest {
    private val primary = "https://primary.example/".toHttpUrl()
    private val fallback = "https://fallback.example/".toHttpUrl()

    @Test
    fun unauthorizedIsReturnedWithoutTryingUnavailableFallback() {
        val attempts = mutableListOf<String>()
        val retry = RetryInterceptor(primary, fallback, { "expired-token" })
        val client = OkHttpClient.Builder()
            .addInterceptor(retry)
            .addInterceptor { chain ->
                val request = chain.request()
                attempts += request.url.host
                response(request, if (request.url.host == primary.host) 401 else 502)
            }
            .build()

        client.newCall(weatherRequest()).execute().use {
            assertEquals(401, it.code)
        }
        assertEquals(listOf(primary.host), attempts)
        assertFalse(retry.allBackendsUnavailable.value)
    }

    @Test
    fun unauthorizedClearsPreviousUnavailableState() {
        val retry = RetryInterceptor(primary, fallback, { "expired-token" }, retryDelaysMs = emptyList())
        var status = 502
        val client = OkHttpClient.Builder()
            .addInterceptor(retry)
            .addInterceptor { chain -> response(chain.request(), status) }
            .build()

        val pinned = weatherRequest().newBuilder()
            .tag(PinnedBackend::class.java, PinnedBackend(primary))
            .build()
        client.newCall(pinned).execute().close()
        assertTrue(retry.allBackendsUnavailable.value)

        status = 401
        client.newCall(weatherRequest()).execute().use {
            assertEquals(401, it.code)
        }
        assertFalse(retry.allBackendsUnavailable.value)
    }

    private fun weatherRequest() = Request.Builder()
        .url(primary.resolve("/api/v1/weather/primary/latest")!!)
        .header("Authorization", "Bearer routed-by-interceptor")
        .build()

    private fun response(request: Request, status: Int) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("Test response")
        .body("".toResponseBody())
        .build()
}
