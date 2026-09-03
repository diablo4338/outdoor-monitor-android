@file:Suppress("DEPRECATION")

package com.example.metrics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val weatherViewModel = ViewModelProvider(this)[WeatherViewModel::class.java]
        setContent {
            MetricsApp(weatherViewModel)
        }
    }
}

private const val AUTH_PREFS = "weather_auth"
private const val LEGACY_JWT_KEY = "backend_jwt"
private const val JWT_KEY_PREFIX = "backend_jwt:"
private const val AUTH_PROVIDER_KEY = "auth_provider"
private const val AUTH_PROVIDER_GOOGLE = "google"
private const val AUTH_PROVIDER_PASSWORD = "password"
private const val PRIMARY_WEATHER_PATH = "/api/v1/weather/primary/latest"
private const val EXTERNAL_WEATHER_PATH = "/api/v1/weather/external/latest"
private const val APP_LATEST_PATH = "/api/v1/app/latest"
private const val GOOGLE_AUTH_PATH = "/auth/google"
private const val PASSWORD_AUTH_PATH = "/auth/password"
private const val LOGOUT_PATH = "/auth/logout"

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
private val backendBaseUrls = listOfNotNull(
    BuildConfig.API_BASE_URL.toHttpUrl(),
    BuildConfig.API_FALLBACK_BASE_URL.takeIf { it.isNotBlank() }?.toHttpUrl(),
).distinctBy { it.origin() }
private val domainTokens = ConcurrentHashMap<String, String>()
private val retryInterceptor = RetryInterceptor(
    primaryBaseUrl = backendBaseUrls.first(),
    fallbackBaseUrl = backendBaseUrls.getOrNull(1),
    tokenProvider = { domainTokens[it.origin()] },
)

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .addInterceptor(retryInterceptor)
    .addNetworkInterceptor(RequestTimingNetworkInterceptor())
    .build()

private fun apiUrl(path: String): String = BuildConfig.API_BASE_URL.trimEnd('/') + path

private suspend fun loadLatestRelease(): AppRelease? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(apiUrl(APP_LATEST_PATH))
            .tag(IgnoreBackendAvailability::class.java, IgnoreBackendAvailability)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body.string()
            val json = JSONObject(body)
            AppRelease(
                versionName = json.getString("version_name"),
                versionCode = json.getInt("version_code"),
                downloadUrl = json.getString("download_url"),
            )
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun openDownload(context: Context, downloadUrl: String) {
    val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun getStoredJwt(context: Context): String? {
    val preferences = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
    backendBaseUrls.forEach { baseUrl ->
        preferences.getString(JWT_KEY_PREFIX + baseUrl.origin(), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { domainTokens[baseUrl.origin()] = it }
    }
    if (domainTokens.isEmpty()) {
        preferences.getString(LEGACY_JWT_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { legacyToken ->
                val primaryOrigin = backendBaseUrls.first().origin()
                domainTokens[primaryOrigin] = legacyToken
                preferences.edit {
                    putString(JWT_KEY_PREFIX + primaryOrigin, legacyToken)
                    remove(LEGACY_JWT_KEY)
                }
            }
    }
    return domainTokens.values.firstOrNull()
}

private fun saveAuthSession(context: Context, tokens: Map<String, String>, provider: String) {
    domainTokens.putAll(tokens)
    context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit {
            tokens.forEach { (origin, token) -> putString(JWT_KEY_PREFIX + origin, token) }
            putString(AUTH_PROVIDER_KEY, provider)
        }
}

private fun getStoredAuthProvider(context: Context): String? {
    return context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .getString(AUTH_PROVIDER_KEY, null)
        ?.takeIf { it.isNotBlank() }
}

private fun clearAuthSession(context: Context) {
    domainTokens.clear()
    context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit {
            backendBaseUrls.forEach { remove(JWT_KEY_PREFIX + it.origin()) }
            remove(LEGACY_JWT_KEY)
            remove(AUTH_PROVIDER_KEY)
        }
}

@Composable
private fun MetricsApp(weatherViewModel: WeatherViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = (context as ComponentActivity).lifecycle
    val allBackendsUnavailable by retryInterceptor.allBackendsUnavailable.collectAsState()
    var jwt by remember(context) { mutableStateOf(getStoredJwt(context)) }
    val weatherState = weatherViewModel.state
    var activeDevice by remember { mutableStateOf(SensorDevice.Primary) }
    var showRequestLog by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<AppRelease?>(null) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var tokenRevision by remember { mutableStateOf(0L) }
    val tokenRefreshMutex = remember { Mutex() }
    val snackbarHostState = remember { SnackbarHostState() }
    val availableUpdate = latestRelease?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    suspend fun load(
        device: SensorDevice,
        jwtForRequest: String? = jwt,
        allowGoogleRefresh: Boolean = true,
        requestTokenRevision: Long = tokenRevision,
    ) {
        if (jwtForRequest.isNullOrBlank()) {
            weatherViewModel.reset()
            return
        }
        weatherViewModel.update { it.withLoading(device, true) }
        val requestTrace = RequestTimingTrace()
        val requestStartedAtMillis = System.currentTimeMillis()
        val requestStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val result = loadWeather(jwtForRequest, device, requestTrace)
        val requestElapsedMillis =
            (SystemClock.elapsedRealtimeNanos() - requestStartedAtNanos) / 1_000_000
        val requestFinishedAtMillis = System.currentTimeMillis()
        weatherViewModel.update {
            it.withRequestLog(
                ClientRequestLog(
                    device = device,
                    path = if (device == SensorDevice.Primary) {
                        PRIMARY_WEATHER_PATH
                    } else {
                        EXTERNAL_WEATHER_PATH
                    },
                    startedAtMillis = requestStartedAtMillis,
                    finishedAtMillis = requestFinishedAtMillis,
                    elapsedMillis = requestElapsedMillis,
                    attempts = requestTrace.snapshot(),
                    result = result.logLabel(),
                )
            )
        }
        when (result) {
            is WeatherResult.Success -> {
                weatherViewModel.update { it.withSnapshot(device, result.snapshot) }
            }
            is WeatherResult.Unauthorized -> {
                if (allowGoogleRefresh && getStoredAuthProvider(context) == AUTH_PROVIDER_GOOGLE) {
                    val refreshedToken = tokenRefreshMutex.withLock {
                        if (tokenRevision != requestTokenRevision) {
                            jwt
                        } else {
                            refreshGoogleBackendToken(context).also {
                                tokenRevision += 1
                                jwt = it
                            }
                        }
                    }
                    if (refreshedToken != null) {
                        load(
                            device = device,
                            jwtForRequest = refreshedToken,
                            allowGoogleRefresh = false,
                            requestTokenRevision = tokenRevision,
                        )
                        return
                    }
                }
                clearAuthSession(context)
                jwt = null
                weatherViewModel.reset()
            }
            is WeatherResult.Failure -> {
                weatherViewModel.update {
                    it.withIssue(
                        device = device,
                        message = result.message,
                        warning = result.warning,
                        clearData = result.clearData,
                    )
                }
            }
        }
    }

    LaunchedEffect(jwt, lifecycle, activeDevice) {
        val pollingJwt = jwt?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val currentStatus = weatherViewModel.state.cardFor(activeDevice).status
            if (currentStatus != SensorCardStatus.Loading) {
                load(activeDevice, pollingJwt)
            }
            while (true) {
                delay(BuildConfig.POLL_INTERVAL_SECONDS.seconds)
                load(activeDevice, pollingJwt)
            }
        }
    }

    LaunchedEffect(Unit) {
        latestRelease = loadLatestRelease()
    }

    LaunchedEffect(availableUpdate?.versionCode) {
        val release = availableUpdate ?: return@LaunchedEffect
        val result = withTimeoutOrNull(3.seconds) {
            snackbarHostState.showSnackbar(
                message = "Доступна версия ${release.versionName} - ${release.versionCode}",
                actionLabel = "Скачать",
                withDismissAction = true,
            )
        }
        if (result == SnackbarResult.ActionPerformed) {
            openDownload(context, release.downloadUrl)
        }
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
    if (jwt.isNullOrBlank()) {
        LoginScreen(
            onPasswordLogin = { username, password ->
                scope.launch {
                    weatherViewModel.reset(WeatherState(loading = true))
                    when (val result = loginWithPassword(username, password)) {
                        AuthResult.InvalidCredentials -> {
                            weatherViewModel.reset(WeatherState(error = "Неверный логин или пароль"))
                            return@launch
                        }
                        is AuthResult.Failure -> {
                            weatherViewModel.reset(WeatherState(error = result.message))
                            return@launch
                        }
                        is AuthResult.Success -> {
                            saveAuthSession(context, result.tokens, AUTH_PROVIDER_PASSWORD)
                            weatherViewModel.reset()
                            jwt = result.tokens.values.first()
                            tokenRevision += 1
                        }
                    }
                }
            },
            onGoogleLogin = { idToken ->
                scope.launch {
                    weatherViewModel.reset(WeatherState(loading = true))
                    when (val result = loginWithGoogle(idToken)) {
                        is AuthResult.Success -> {
                            saveAuthSession(context, result.tokens, AUTH_PROVIDER_GOOGLE)
                            weatherViewModel.reset()
                            jwt = result.tokens.values.first()
                            tokenRevision += 1
                        }
                        AuthResult.InvalidCredentials -> {
                            signOutGoogle(context)
                            clearAuthSession(context)
                            jwt = null
                            weatherViewModel.reset(WeatherState(error = "Google token отклонен backend"))
                            return@launch
                        }
                        is AuthResult.Failure -> {
                            signOutGoogle(context)
                            clearAuthSession(context)
                            jwt = null
                            weatherViewModel.reset(WeatherState(error = result.message))
                            return@launch
                        }
                    }
                }
            },
            error = weatherState.error,
            loading = weatherState.loading,
        )
    } else {
        WeatherScreen(
            state = weatherState,
            allBackendsUnavailable = allBackendsUnavailable,
            showRequestLog = showRequestLog,
            onActiveDeviceChanged = { activeDevice = it },
        )
    }

            if (!jwt.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Лог",
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp,
                    )
                    Checkbox(
                        checked = showRequestLog,
                        onCheckedChange = { showRequestLog = it },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                IconButton(onClick = { showVersionDialog = true }) {
                    Text(
                        text = "ⓘ",
                        color = Color(0xFFAAAAAA),
                        fontSize = 28.sp,
                    )
                }
                if (!jwt.isNullOrBlank()) {
                    IconButton(
                        onClick = {
                            val tokensForLogout = domainTokens.toMap()
                            clearAuthSession(context)
                            jwt = null
                            tokenRevision += 1
                            weatherViewModel.reset()
                            scope.launch {
                                logout(tokensForLogout)
                                signOutGoogle(context)
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_logout),
                            contentDescription = "Выйти",
                            tint = Color(0xFFAAAAAA),
                        )
                    }
                }
            }

            if (showVersionDialog) {
                VersionDialog(
                    latestRelease = latestRelease,
                    updateAvailable = availableUpdate != null,
                    onDownload = availableUpdate?.let { release ->
                        { openDownload(context, release.downloadUrl) }
                    },
                    onDismiss = { showVersionDialog = false },
                )
            }
        }
    }
}

@Composable
private fun VersionDialog(
    latestRelease: AppRelease?,
    updateAvailable: Boolean,
    onDownload: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("О приложении") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Текущая версия: ${BuildConfig.VERSION_NAME} - ${BuildConfig.VERSION_CODE}")
                if (updateAvailable && latestRelease != null) {
                    Text("Последняя: ${latestRelease.versionName} - ${latestRelease.versionCode}")
                }
                if (latestRelease == null) {
                    Text("Не удалось получить последнюю версию")
                }
                if (latestRelease != null && !updateAvailable) {
                    Text("Установлена актуальная версия")
                }
            }
        },
        confirmButton = {
            if (onDownload != null) {
                TextButton(onClick = onDownload) { Text("Скачать") }
            } else {
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
        dismissButton = if (onDownload != null) {
            { TextButton(onClick = onDismiss) { Text("Закрыть") } }
        } else {
            null
        },
    )
}

@Composable
private fun WeatherScreen(
    state: WeatherState,
    allBackendsUnavailable: Boolean,
    showRequestLog: Boolean,
    onActiveDeviceChanged: (SensorDevice) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    var timestampFormatTick by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60.seconds)
            timestampFormatTick = System.currentTimeMillis()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onActiveDeviceChanged(
            if (pagerState.currentPage == 0) SensorDevice.Primary else SensorDevice.External
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                val device = if (page == 0) SensorDevice.Primary else SensorDevice.External
                val card = state.cardFor(device)
                val cardStatus = card.status
                val lastUpdate = remember(page, card.lastUpdateEpochSeconds, timestampFormatTick) {
                    card.lastUpdateEpochSeconds?.let(::formatSnapshotTimestamp)
                }
                val errorText = when {
                    cardStatus is SensorCardStatus.Error -> cardStatus.message
                    cardStatus == SensorCardStatus.SensorUnavailable &&
                        device == SensorDevice.Primary ->
                        "Сенсор не отвечает - показания не обновлены"
                    cardStatus == SensorCardStatus.SensorUnavailable ->
                        "Дополнительный датчик не отвечает"
                    else -> null
                }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (page == 0) {
                        PrimarySensorCard(card)
                    } else {
                        ExternalSensorCard(card)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Обновлено: ${lastUpdate ?: "-"}",
                        color = Color(0xFF777777),
                        fontSize = 18.sp,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (errorText != null) {
                            ErrorText(
                                text = errorText,
                                color = if (
                                    (cardStatus as? SensorCardStatus.Error)?.warning == true
                                ) {
                                    Color(0xFFD19A55)
                                } else {
                                    Color.Red
                                },
                            )
                        }
                    }
                }
            }

            if (showRequestLog) {
                ClientRequestLogPanel(state.requestLogs)
                Spacer(modifier = Modifier.height(12.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .fillMaxHeight(0.1f),
                contentAlignment = Alignment.Center,
            ) {
                if (allBackendsUnavailable) {
                    CircularProgressIndicator(color = Color(0xFFAAAAAA))
                }
            }
    }
}

@Composable
private fun ClientRequestLogPanel(entries: List<ClientRequestLog>) {
    val scrollState = rememberScrollState()

    LaunchedEffect(entries.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 180.dp)
            .border(1.dp, Color(0xFF444444))
            .background(Color(0xFF101010))
            .padding(10.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Запросы клиента",
            color = Color(0xFFAAAAAA),
            fontSize = 14.sp,
        )
        if (entries.isEmpty()) {
            Text(
                text = "Запросов пока нет",
                color = Color(0xFF777777),
                fontSize = 13.sp,
            )
        } else {
            entries.forEach { entry ->
                val deviceLabel = when (entry.device) {
                    SensorDevice.Primary -> "основной"
                    SensorDevice.External -> "дополнительный"
                }
                Text(
                    text = buildString {
                        append("→ ")
                        append(formatClientLogTimestamp(entry.startedAtMillis))
                        append("  GET ")
                        append(entry.path)
                        append("  [")
                        append(deviceLabel)
                        append("]")
                        entry.attempts.forEachIndexed { index, attempt ->
                            append("\n  #")
                            append(index + 1)
                            append(" ")
                            append(attempt.host)
                            append("  ")
                            append(attempt.status)
                            append("  ")
                            append(attempt.elapsedMillis)
                            append(" мс")
                        }
                        append("\n← ")
                        append(formatClientLogTimestamp(entry.finishedAtMillis))
                        append("  ")
                        append(entry.result)
                        append("  •  ")
                        append(entry.elapsedMillis)
                        append(" мс")
                    },
                    color = Color(0xFFCCCCCC),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PrimarySensorCard(card: SensorCardState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Основной датчик",
            color = Color(0xFF777777),
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(30.dp))

        Text(
            text = "Температура: ${card.temp ?: "..."} °C",
            color = Color(0xFFEEEEEE),
            fontSize = 36.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Влажность: ${card.hum ?: "..."} %",
            color = Color(0xFFCCCCCC),
            fontSize = 36.sp
        )
    }
}

@Composable
private fun ExternalSensorCard(card: SensorCardState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Дополнительный датчик",
            color = Color(0xFF777777),
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(30.dp))

        Text(
            text = "Температура: ${card.temp ?: "..."} °C",
            color = Color(0xFFEEEEEE),
            fontSize = 36.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Влажность: ${card.hum ?: "..."} %",
            color = Color(0xFFCCCCCC),
            fontSize = 36.sp
        )
    }
}

@Composable
private fun LoginScreen(
    onPasswordLogin: (String, String) -> Unit,
    onGoogleLogin: (String) -> Unit,
    error: String?,
    loading: Boolean,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val fieldColors = TextFieldDefaults.colors(
        focusedTextColor = Color(0xFFEEEEEE),
        unfocusedTextColor = Color(0xFFEEEEEE),
        disabledTextColor = Color(0xFF777777),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        cursorColor = Color(0xFFEEEEEE),
        focusedIndicatorColor = Color(0xFFEEEEEE),
        unfocusedIndicatorColor = Color(0xFF777777),
        disabledIndicatorColor = Color(0xFF444444),
        focusedLabelColor = Color(0xFFEEEEEE),
        unfocusedLabelColor = Color(0xFFAAAAAA),
        disabledLabelColor = Color(0xFF777777),
    )
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFE0E0E0),
        contentColor = Color.Black,
        disabledContainerColor = Color(0xFF333333),
        disabledContentColor = Color(0xFF888888),
    )

    val context = LocalContext.current
    val googleClient = remember { buildGoogleSignInClient(context) }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val token = readGoogleIdToken(result.data)
        if (token == null) {
            localError = "Не удалось выбрать Google аккаунт"
            return@rememberLauncherForActivityResult
        }
        localError = null
        onGoogleLogin(token)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Вход",
            color = Color(0xFFEEEEEE),
            fontSize = 32.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (BuildConfig.DEBUG) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text("Имя") },
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text("Пароль") },
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    if (loading) return@Button
                    localError = null
                    onPasswordLogin(username.trim(), password)
                },
                enabled = username.isNotBlank() && password.isNotBlank(),
                colors = buttonColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Войти")
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = {
                if (loading) return@Button
                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                    localError = "Google OAuth client id не настроен"
                    return@Button
                }
                localError = null
                signInLauncher.launch(googleClient.signInIntent)
            },
            enabled = true,
            colors = buttonColors,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Войти через Google")
        }

        val message = localError ?: error
        val messageAlpha by animateFloatAsState(
            targetValue = if (message != null) 1f else 0f,
            label = "loginErrorAlpha",
        )
        Spacer(modifier = Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            ErrorText(
                text = message ?: " ",
                modifier = Modifier.alpha(messageAlpha),
            )
        }
    }
}

@Composable
private fun ErrorText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Red,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = 18.sp,
        textAlign = TextAlign.Center
    )
}

private suspend fun loadWeather(
    jwt: String?,
    device: SensorDevice,
    requestTrace: RequestTimingTrace,
): WeatherResult {
    return when (val response = fetchWeatherMetrics(jwt, device, requestTrace)) {
        FetchResponse.Unauthorized -> WeatherResult.Unauthorized
        is FetchResponse.Failure -> response.toWeatherFailure()
        is FetchResponse.Success -> {
            val json = response.json
            try {
                val temp = if (json.isNull("temp")) null else formatMetricValue(json.getDouble("temp"))
                val hum = if (json.isNull("hum")) null else formatMetricValue(json.getDouble("hum"))
                val externalTemp = if (json.isNull("external_temp")) {
                    null
                } else {
                    formatMetricValue(json.getDouble("external_temp"))
                }
                val externalHum = if (json.isNull("external_hum")) {
                    null
                } else {
                    formatMetricValue(json.getDouble("external_hum"))
                }
                val metricTimestamp = if (json.isNull("metric_timestamp")) {
                    null
                } else {
                    json.getLong("metric_timestamp")
                }
                val externalMetricTimestamp = if (json.isNull("external_metric_timestamp")) {
                    null
                } else {
                    json.getLong("external_metric_timestamp")
                }
                WeatherResult.Success(
                    WeatherSnapshot(
                        temp = temp,
                        hum = hum,
                        externalTemp = externalTemp,
                        externalHum = externalHum,
                        lastUpdateEpochSeconds = metricTimestamp,
                        externalLastUpdateEpochSeconds = externalMetricTimestamp,
                    )
                )
            } catch (_: JSONException) {
                WeatherResult.Failure(
                    message = "Структура ответа backend несовместима с текущим клиентом",
                    warning = true,
                )
            }
        }
    }
}

private fun formatSnapshotTimestamp(epochSeconds: Long): String {
    val timestamp = Date(epochSeconds * 1000)
    val snapshotDay = Calendar.getInstance().apply { time = timestamp }
    val today = Calendar.getInstance()
    val isToday = snapshotDay.get(Calendar.ERA) == today.get(Calendar.ERA) &&
        snapshotDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        snapshotDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val pattern = if (isToday) "HH:mm:ss" else "dd.MM.yyyy HH:mm:ss"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(timestamp)
}

private fun formatClientLogTimestamp(epochMillis: Long): String {
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(epochMillis))
}

private suspend fun fetchWeatherMetrics(
    jwt: String?,
    device: SensorDevice,
    requestTrace: RequestTimingTrace,
): FetchResponse {
    return withContext(Dispatchers.IO) {
        val requestUrl = apiUrl(
            if (device == SensorDevice.Primary) PRIMARY_WEATHER_PATH else EXTERNAL_WEATHER_PATH
        )
        try {
            val requestBuilder = Request.Builder()
                .url(requestUrl)
                .get()
                .tag(RequestTimingTrace::class.java, requestTrace)

            if (!jwt.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer routed-by-interceptor")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 401 -> FetchResponse.Unauthorized
                    !response.isSuccessful -> {
                        val text = response.body.string()
                        val controlled = if (response.code == 400 && text.isNotBlank()) {
                            try {
                                val json = JSONObject(text)
                                val message = json.optString("message").takeIf { it.isNotBlank() }
                                message?.let {
                                    FetchResponse.Failure.Controlled(
                                        message = it,
                                        clearData = json.optBoolean("clear_data", false),
                                    )
                                }
                            } catch (_: JSONException) {
                                null
                            }
                        } else {
                            null
                        }
                        controlled ?: FetchResponse.Failure.HttpStatus(response.code)
                    }
                    else -> {
                        val text = response.body.string()
                        if (text.isBlank()) {
                            FetchResponse.Failure.EmptyBody
                        } else {
                            try {
                                FetchResponse.Success(JSONObject(text))
                            } catch (_: JSONException) {
                                FetchResponse.Failure.InvalidJson
                            }
                        }
                    }
                }
            }
        } catch (_: UnknownHostException) {
            FetchResponse.Failure.UnknownHost(requestUrl.hostLabel())
        } catch (_: SocketTimeoutException) {
            FetchResponse.Failure.Timeout
        } catch (_: ConnectException) {
            FetchResponse.Failure.BackendUnavailable(requestUrl.hostLabel())
        } catch (_: NoRouteToHostException) {
            FetchResponse.Failure.BackendUnavailable(requestUrl.hostLabel())
        } catch (_: SSLException) {
            FetchResponse.Failure.Tls
        } catch (_: IOException) {
            FetchResponse.Failure.Io
        }
    }
}

private suspend fun loginWithGoogle(idToken: String): AuthResult {
    val payload = JSONObject()
        .put("id_token", idToken)
        .toString()
    return exchangeToken(GOOGLE_AUTH_PATH, payload)
}

private suspend fun loginWithPassword(username: String, password: String): AuthResult {
    val payload = JSONObject()
        .put("username", username)
        .put("password", password)
        .toString()
    return exchangeToken(PASSWORD_AUTH_PATH, payload)
}

private suspend fun exchangeToken(path: String, payload: String): AuthResult {
    return withContext(Dispatchers.IO) {
        val tokens = mutableMapOf<String, String>()
        var invalidCredentials = false
        var visibleFailure: String? = null
        backendBaseUrls.forEach { baseUrl ->
            try {
                val request = Request.Builder()
                    .url(baseUrl.resolve(path)!!)
                    .tag(PinnedBackend::class.java, PinnedBackend(baseUrl))
                    .tag(IgnoreBackendAvailability::class.java, IgnoreBackendAvailability)
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> invalidCredentials = true
                        response.code in 500..599 -> Unit
                        !response.isSuccessful -> visibleFailure = "Backend вернул HTTP ${response.code} при входе"
                        else -> {
                            val text = response.body.string()
                            val token = if (text.isBlank()) null else try {
                                JSONObject(text).optString("access_token").takeIf { it.isNotBlank() }
                            } catch (_: JSONException) {
                                null
                            }
                            if (token == null) {
                                visibleFailure = "Структура ответа backend несовместима с текущим клиентом"
                            } else {
                                tokens[response.request.url.origin()] = token
                            }
                        }
                    }
                }
            } catch (_: SSLException) {
                visibleFailure = "TLS/SSL ошибка при подключении к backend"
            } catch (_: IOException) {
            }
        }
        when {
            tokens.isNotEmpty() -> AuthResult.Success(tokens)
            invalidCredentials -> AuthResult.InvalidCredentials
            else -> AuthResult.Failure(visibleFailure)
        }
    }
}

private suspend fun logout(tokens: Map<String, String>) {
    withContext(Dispatchers.IO) {
        backendBaseUrls.filter { tokens.containsKey(it.origin()) }.forEach { baseUrl ->
            try {
                val request = Request.Builder()
                    .url(baseUrl.resolve(LOGOUT_PATH)!!)
                    .tag(PinnedBackend::class.java, PinnedBackend(baseUrl))
                    .tag(IgnoreBackendAvailability::class.java, IgnoreBackendAvailability)
                    .tag(
                        RoutedAuthorization::class.java,
                        RoutedAuthorization(tokens.getValue(baseUrl.origin())),
                    )
                    .post(ByteArray(0).toRequestBody())
                    .header("Authorization", "Bearer routed-by-interceptor")
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (_: IOException) {
            }
        }
    }
}

private fun buildGoogleSignInClient(context: Context): GoogleSignInClient {
    val optionsBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
    if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
        optionsBuilder.requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
    }
    return GoogleSignIn.getClient(context, optionsBuilder.build())
}

private suspend fun signOutGoogle(context: Context) {
    withContext(Dispatchers.IO) {
        try {
            Tasks.await(buildGoogleSignInClient(context).signOut(), 5, TimeUnit.SECONDS)
        } catch (_: ExecutionException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: TimeoutException) {
        }
    }
}

private suspend fun refreshGoogleBackendToken(context: Context): String? {
    return withContext(Dispatchers.IO) {
        try {
            val account = Tasks.await(
                buildGoogleSignInClient(context).silentSignIn(),
                5,
                TimeUnit.SECONDS,
            )
            val idToken = account.idToken?.takeIf { it.isNotBlank() } ?: return@withContext null
            when (val result = loginWithGoogle(idToken)) {
                is AuthResult.Success -> {
                    saveAuthSession(context, result.tokens, AUTH_PROVIDER_GOOGLE)
                    result.tokens.values.first()
                }
                AuthResult.InvalidCredentials,
                is AuthResult.Failure -> null
            }
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: TimeoutException) {
            null
        }
    }
}

private fun readGoogleIdToken(data: Intent?): String? {
    return try {
        GoogleSignIn.getSignedInAccountFromIntent(data)
            .getResult(ApiException::class.java)
            .idToken
            ?.takeIf { it.isNotBlank() }
    } catch (_: ApiException) {
        null
    }
}

private fun FetchResponse.Failure.toWeatherFailure(): WeatherResult.Failure {
    return when (this) {
        FetchResponse.Failure.EmptyBody,
        FetchResponse.Failure.InvalidJson ->
            WeatherResult.Failure(
                message = "Структура ответа backend несовместима с текущим клиентом",
                warning = true,
            )
        is FetchResponse.Failure.Controlled -> WeatherResult.Failure(
            message = message,
            clearData = clearData,
            warning = true,
        )
        FetchResponse.Failure.Io,
        FetchResponse.Failure.Timeout,
        is FetchResponse.Failure.BackendUnavailable,
        is FetchResponse.Failure.UnknownHost -> WeatherResult.Failure(null)
        FetchResponse.Failure.Tls -> WeatherResult.Failure("TLS/SSL ошибка при подключении к backend")
        is FetchResponse.Failure.HttpStatus -> WeatherResult.Failure(
            if (code in 500..599) null else "Backend вернул HTTP $code"
        )
    }
}

private fun String.hostLabel(): String {
    return toHttpUrlOrNull()?.let { url ->
        val defaultPort = (url.scheme == "http" && url.port == 80) ||
            (url.scheme == "https" && url.port == 443)
        if (defaultPort) url.host else "${url.host}:${url.port}"
    } ?: BuildConfig.API_BASE_URL
}

private fun formatMetricValue(value: Double): String {
    return "%.0f".format(Locale.US, value)
}

internal class WeatherViewModel : ViewModel() {
    var state by mutableStateOf(WeatherState())
        private set

    fun update(transform: (WeatherState) -> WeatherState) {
        state = transform(state)
    }

    fun reset(newState: WeatherState = WeatherState()) {
        state = newState
    }
}

internal data class WeatherState(
    val error: String? = null,
    val loading: Boolean = false,
    val primaryCard: SensorCardState = SensorCardState(),
    val externalCard: SensorCardState = SensorCardState(),
    val requestLogs: List<ClientRequestLog> = emptyList(),
) {
    fun cardFor(device: SensorDevice): SensorCardState = when (device) {
        SensorDevice.Primary -> primaryCard
        SensorDevice.External -> externalCard
    }

    fun withLoading(device: SensorDevice, value: Boolean): WeatherState {
        val card = cardFor(device)
        if (!value || card.status != SensorCardStatus.Initial) return this
        return when (device) {
            SensorDevice.Primary -> copy(primaryCard = card.copy(status = SensorCardStatus.Loading))
            SensorDevice.External -> copy(externalCard = card.copy(status = SensorCardStatus.Loading))
        }
    }

    fun withIssue(
        device: SensorDevice,
        message: String?,
        warning: Boolean,
        clearData: Boolean,
    ): WeatherState {
        // Backend availability is global and must not mutate a sensor card state.
        if (message == null) return this
        val card = cardFor(device)
        val updatedCard = card.copy(
            temp = if (clearData) null else card.temp,
            hum = if (clearData) null else card.hum,
            lastUpdateEpochSeconds = if (clearData) null else card.lastUpdateEpochSeconds,
            status = SensorCardStatus.Error(message, warning),
        )
        return when (device) {
            SensorDevice.Primary -> copy(primaryCard = updatedCard)
            SensorDevice.External -> copy(externalCard = updatedCard)
        }
    }

    fun withSnapshot(device: SensorDevice, update: WeatherSnapshot): WeatherState {
        val updatedCard = when (device) {
            SensorDevice.Primary -> SensorCardState(
                temp = update.temp,
                hum = update.hum,
                lastUpdateEpochSeconds = update.lastUpdateEpochSeconds,
                status = statusForValues(update.temp, update.hum),
            )
            SensorDevice.External -> SensorCardState(
                temp = update.externalTemp,
                hum = update.externalHum,
                lastUpdateEpochSeconds = update.externalLastUpdateEpochSeconds,
                status = statusForValues(update.externalTemp, update.externalHum),
            )
        }
        return when (device) {
            SensorDevice.Primary -> copy(primaryCard = updatedCard)
            SensorDevice.External -> copy(externalCard = updatedCard)
        }
    }

    fun withRequestLog(entry: ClientRequestLog): WeatherState {
        return copy(requestLogs = (requestLogs + entry).takeLast(20))
    }

    private fun statusForValues(temp: String?, hum: String?): SensorCardStatus =
        if (temp == null || hum == null) {
            SensorCardStatus.SensorUnavailable
        } else {
            SensorCardStatus.Ready
        }
}

internal data class SensorCardState(
    val temp: String? = null,
    val hum: String? = null,
    val lastUpdateEpochSeconds: Long? = null,
    val status: SensorCardStatus = SensorCardStatus.Initial,
)

internal sealed interface SensorCardStatus {
    data object Initial : SensorCardStatus
    data object Loading : SensorCardStatus
    data object Ready : SensorCardStatus
    data object SensorUnavailable : SensorCardStatus
    data class Error(val message: String, val warning: Boolean) : SensorCardStatus
}

internal enum class SensorDevice {
    Primary,
    External,
}

internal data class ClientRequestLog(
    val device: SensorDevice,
    val path: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val elapsedMillis: Long,
    val attempts: List<ClientRequestAttempt>,
    val result: String,
)

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

private class RequestTimingNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val trace = request.tag(RequestTimingTrace::class.java)
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
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
    return (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000
}

private data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
)

internal data class WeatherSnapshot(
    val temp: String?,
    val hum: String?,
    val externalTemp: String?,
    val externalHum: String?,
    val lastUpdateEpochSeconds: Long?,
    val externalLastUpdateEpochSeconds: Long?,
)

private sealed interface WeatherResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherResult
    data object Unauthorized : WeatherResult
    data class Failure(
        val message: String?,
        val clearData: Boolean = false,
        val warning: Boolean = false,
    ) : WeatherResult
}

private fun WeatherResult.logLabel(): String = when (this) {
    is WeatherResult.Success -> "получен ответ: OK"
    WeatherResult.Unauthorized -> "получен ответ: HTTP 401"
    is WeatherResult.Failure -> message?.let { "ошибка: $it" } ?: "ошибка соединения"
}

private sealed interface FetchResponse {
    data class Success(val json: JSONObject) : FetchResponse
    data object Unauthorized : FetchResponse
    sealed interface Failure : FetchResponse {
        data object EmptyBody : Failure
        data object InvalidJson : Failure
        data object Io : Failure
        data object Timeout : Failure
        data object Tls : Failure
        data class Controlled(val message: String, val clearData: Boolean) : Failure
        data class BackendUnavailable(val host: String) : Failure
        data class HttpStatus(val code: Int) : Failure
        data class UnknownHost(val host: String) : Failure
    }
}

private sealed interface AuthResult {
    data class Success(val tokens: Map<String, String>) : AuthResult
    data object InvalidCredentials : AuthResult
    data class Failure(val message: String?) : AuthResult
}

private fun okhttp3.HttpUrl.origin(): String = "$scheme://$host:$port"
