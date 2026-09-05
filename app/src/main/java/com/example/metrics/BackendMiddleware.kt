package com.example.metrics

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

internal interface BackendTokenStore {
    fun read(origin: String): String?
    fun write(origin: String, token: String?)
}

/** Owns routing, domain-specific sessions, failover and primary recovery. */
internal class BackendMiddleware(
    primaryUrl: String,
    fallbackUrl: String?,
    private val tokenStore: BackendTokenStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .addNetworkInterceptor(RequestTimingNetworkInterceptor())
        .build(),
    private val probeIntervalMillis: Long = 10_000,
) : Closeable {
    enum class Backend { Primary, Fallback }

    private val urls = buildMap {
        put(Backend.Primary, primaryUrl.toHttpUrl())
        fallbackUrl?.takeIf { it.isNotBlank() }?.toHttpUrl()?.let {
            if (it != get(Backend.Primary)) put(Backend.Fallback, it)
        }
    }
    private val lock = Any()
    private val tokens = urls.mapNotNull { (backend, url) ->
        tokenStore.read(url.origin())?.takeIf { it.isNotBlank() }?.let { backend to it }
    }.toMap().toMutableMap()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val healthClient = client.newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
    private var active = Backend.Primary
    private var routingRevision = 0L
    private var probeJob: Job? = null
    private val availability = BackendAvailabilityTracker()
    private val requestSequence = AtomicLong()
    val allBackendsUnavailable: StateFlow<Boolean> = availability.unavailable

    init {
        require(probeIntervalMillis > 0)
    }

    fun switchBackend(backend: Backend): Unit = synchronized(lock) {
        require(backend in urls) { "Backend is not configured" }
        if (active == backend) return@synchronized
        active = backend
        routingRevision++
        probeJob?.cancel()
        probeJob = null
        if (backend == Backend.Fallback) {
            val revision = routingRevision
            probeJob = scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    delay(probeIntervalMillis.milliseconds)
                    try {
                        val healthy = send(Backend.Primary, "/health").use { it.isSuccessful }
                        if (healthy) {
                            synchronized(lock) {
                                if (routingRevision == revision) switchBackend(Backend.Primary)
                            }
                            return@launch
                        }
                    } catch (_: IOException) {
                        // Stay on fallback and retry at the next interval.
                    }
                }
            }.also { it.start() }
        }
    }

    suspend fun request(
        route: String,
        body: String? = null,
        authenticated: Boolean = true,
        trace: RequestTimingTrace? = null,
        trackAvailability: Boolean = true,
    ): Response = withContext(Dispatchers.IO) {
        val requestId = requestSequence.incrementAndGet()
        val (first, revision) = synchronized(lock) { active to routingRevision }
        val candidates = if (first == Backend.Primary && Backend.Fallback in urls) {
            listOf(first, Backend.Fallback)
        } else listOf(first)
        for ((index, backend) in candidates.withIndex()) {
            try {
                val token = synchronized(lock) { if (authenticated) tokens[backend] else null }
                val response = send(backend, route, body, token, trace)
                if (response.code !in 500..599) {
                    if (trackAvailability) availability.report(requestId, true)
                    return@withContext response
                }
                failOver(backend, revision)
                if (index == candidates.lastIndex) {
                    if (trackAvailability) availability.report(requestId, false)
                    return@withContext response
                }
                response.close()
            } catch (error: IOException) {
                failOver(backend, revision)
                if (index == candidates.lastIndex) {
                    if (trackAvailability) availability.report(requestId, false)
                    throw error
                }
            }
        }
        error("No backend candidates")
    }

    private fun failOver(backend: Backend, revision: Long) = synchronized(lock) {
        if (backend == Backend.Primary && Backend.Fallback in urls && routingRevision == revision) {
            switchBackend(Backend.Fallback)
        }
    }

    fun hasStoredToken(): Boolean = synchronized(lock) { tokens[active] != null }

    fun saveToken(response: Response, token: String) = synchronized(lock) {
        require(token.isNotBlank())
        val backend = urls.keys.first { urls.getValue(it).origin() == response.request.url.origin() }
        tokens[backend] = token
        tokenStore.write(urls.getValue(backend).origin(), token)
    }

    fun clearSession() = synchronized(lock) {
        tokens.clear()
        urls.values.forEach { tokenStore.write(it.origin(), null) }
    }

    private suspend fun send(
        backend: Backend,
        route: String,
        body: String? = null,
        token: String? = null,
        trace: RequestTimingTrace? = null,
    ): Response {
        require(route.startsWith("/") && !route.startsWith("//") && route.none { it.code == 92 }) {
            "Expected a relative API route"
        }
        val baseUrl = urls.getValue(backend)
        val url = requireNotNull(baseUrl.resolve(route))
        require(url.origin() == baseUrl.origin()) { "Route must stay on the selected backend" }
        val request = Request.Builder().url(url).tag(RequestTimingTrace::class.java, trace).apply {
            if (body != null) post(body.toRequestBody(JSON))
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = clientFor(route).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
    }

    private fun clientFor(route: String) = if (route == "/health") healthClient else client

    override fun close() {
        scope.cancel()
    }


    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        @Volatile private var instance: BackendMiddleware? = null

        fun getInstance(context: Context): BackendMiddleware = instance ?: synchronized(this) {
            instance ?: run {
                val primary = BuildConfig.API_BASE_URL.toHttpUrl()
                val preferences = context.applicationContext.getSharedPreferences("weather_auth", Context.MODE_PRIVATE)
                val store = object : BackendTokenStore {
                    override fun read(origin: String): String? =
                        preferences.getString("backend_jwt:$origin", null)
                            ?: if (origin == primary.origin()) preferences.getString("backend_jwt", null) else null

                    override fun write(origin: String, token: String?) {
                        preferences.edit {
                            if (token == null) remove("backend_jwt:$origin")
                            else putString("backend_jwt:$origin", token)
                            remove("backend_jwt")
                        }
                    }
                }
                BackendMiddleware(BuildConfig.API_BASE_URL, BuildConfig.API_FALLBACK_BASE_URL, store)
            }.also { instance = it }
        }
    }
}


private fun HttpUrl.origin(): String = "$scheme://$host:$port"

internal class BackendAvailabilityTracker {
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable
    private var lastRequestId = 0L

    @Synchronized
    fun report(requestId: Long, available: Boolean) {
        if (requestId < lastRequestId) return
        lastRequestId = requestId
        _unavailable.value = !available
    }
}
