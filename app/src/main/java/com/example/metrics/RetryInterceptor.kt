package com.example.metrics

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class PinnedBackend(val baseUrl: HttpUrl)

internal class RetryInterceptor(
    private val primaryBaseUrl: HttpUrl,
    private val fallbackBaseUrl: HttpUrl?,
    private val tokenProvider: (HttpUrl) -> String?,
    private val retryDelaysMs: List<Long> = listOf(500L, 1_000L, 3_000L),
    private val primaryProbeIntervalMs: Long = 30_000L,
) : Interceptor {
    private val _allBackendsUnavailable = MutableStateFlow(false)
    val allBackendsUnavailable: StateFlow<Boolean> = _allBackendsUnavailable

    fun reportReachableBackend() {
        _allBackendsUnavailable.value = false
    }

    init {
        require(retryDelaysMs.all { it >= 0 })
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestId = requestSequence.incrementAndGet()
        val candidates = request.tag(PinnedBackend::class.java)?.let { listOf(it.baseUrl) } ?: synchronized(lock) {
            val now = System.currentTimeMillis()
            when {
                activeBaseUrl == primaryBaseUrl && fallbackBaseUrl != null ->
                    listOf(primaryBaseUrl, fallbackBaseUrl)
                fallbackBaseUrl != null && now - lastPrimaryProbeAtMs >= primaryProbeIntervalMs -> {
                    lastPrimaryProbeAtMs = now
                    listOf(primaryBaseUrl, fallbackBaseUrl)
                }
                else -> listOf(activeBaseUrl)
            }
        }

        for ((candidateIndex, baseUrl) in candidates.withIndex()) {
            val requestBuilder = request.newBuilder()
                .url(
                    request.url.newBuilder()
                        .scheme(baseUrl.scheme)
                        .host(baseUrl.host)
                        .port(baseUrl.port)
                        .build()
                )
            if (request.header("Authorization") != null) {
                requestBuilder.removeHeader("Authorization")
                tokenProvider(baseUrl)?.let { requestBuilder.header("Authorization", "Bearer $it") }
            }
            val routedRequest = requestBuilder.build()

            for (attempt in 0..retryDelaysMs.size) {
                try {
                    val response = chain.proceed(routedRequest)
                    if (response.code !in 500..599) {
                        markAvailable(requestId, baseUrl)
                        return response
                    }
                    if (attempt == retryDelaysMs.size) {
                        if (candidateIndex < candidates.lastIndex) {
                            response.close()
                            Log.w(TAG, "Backend ${baseUrl.redact()} unavailable; using fallback")
                            break
                        }
                        markUnavailable(requestId)
                        return response
                    }
                    response.close()
                    waitForRetry(attempt, routedRequest.url.redact(), "HTTP ${response.code}")
                } catch (error: IOException) {
                    if (attempt == retryDelaysMs.size) {
                        if (candidateIndex < candidates.lastIndex) break
                        markUnavailable(requestId)
                        throw error
                    }
                    waitForRetry(attempt, routedRequest.url.redact(), error.javaClass.simpleName)
                }
            }
        }
        error("Retry loop completed unexpectedly")
    }

    private fun markAvailable(requestId: Long, baseUrl: HttpUrl) = synchronized(lock) {
        _allBackendsUnavailable.value = false
        if (requestId > lastCompletedRequestId) {
            activeBaseUrl = baseUrl
            lastCompletedRequestId = requestId
        }
    }

    private fun markUnavailable(requestId: Long) = synchronized(lock) {
        if (requestId >= lastCompletedRequestId) {
            _allBackendsUnavailable.value = true
        }
    }

    private fun waitForRetry(attempt: Int, url: String, reason: String) {
        val delayMs = retryDelaysMs[attempt]
        Log.w(TAG, "Retrying $url after $reason in ${delayMs}ms")
        try {
            Thread.sleep(delayMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Retry interrupted", error)
        }
    }

    private companion object {
        const val TAG = "OutdoorMonitorAPI"
    }

    private val requestSequence = AtomicLong()
    private val lock = Any()
    private var lastCompletedRequestId = 0L
    private var lastPrimaryProbeAtMs = 0L
    private var activeBaseUrl = primaryBaseUrl
}
