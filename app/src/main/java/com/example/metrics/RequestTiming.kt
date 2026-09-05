package com.example.metrics

import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

internal data class ClientRequestAttempt(
    val host: String,
    val status: String,
    val elapsedMillis: Long,
)

internal class RequestTimingTrace {
    private val attempts = mutableListOf<ClientRequestAttempt>()

    @Synchronized
    fun add(attempt: ClientRequestAttempt) {
        attempts += attempt
    }

    @Synchronized
    fun snapshot(): List<ClientRequestAttempt> = attempts.toList()
}

internal class RequestTimingNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val trace = request.tag(RequestTimingTrace::class.java)
        val startedAtNanos = System.nanoTime()
        try {
            val response = chain.proceed(request)
            trace?.add(
                ClientRequestAttempt(
                    host = request.url.toString().hostLabel(),
                    status = "HTTP ${response.code}",
                    elapsedMillis = elapsedMillisSince(startedAtNanos),
                )
            )
            return response
        } catch (error: IOException) {
            trace?.add(
                ClientRequestAttempt(
                    host = request.url.toString().hostLabel(),
                    status = error.javaClass.simpleName,
                    elapsedMillis = elapsedMillisSince(startedAtNanos),
                )
            )
            throw error
        }
    }
}

private fun elapsedMillisSince(startedAtNanos: Long): Long {
    return (System.nanoTime() - startedAtNanos) / 1_000_000
}

private fun String.hostLabel(): String {
    return toHttpUrlOrNull()?.let { url ->
        val defaultPort = (url.scheme == "http" && url.port == 80) ||
            (url.scheme == "https" && url.port == 443)
        if (defaultPort) url.host else "${url.host}:${url.port}"
    } ?: "backend"
}
