@file:Suppress("DEPRECATION")

package com.example.metrics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        setContent {
            MetricsApp()
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
    .build()

private fun apiUrl(path: String): String = BuildConfig.API_BASE_URL.trimEnd('/') + path

private suspend fun loadLatestRelease(): AppRelease? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(apiUrl(APP_LATEST_PATH))
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
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
fun MetricsApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = (context as ComponentActivity).lifecycle
    val allBackendsUnavailable by retryInterceptor.allBackendsUnavailable.collectAsState()

    var jwt by remember(context) { mutableStateOf(getStoredJwt(context)) }
    var weatherState by remember { mutableStateOf(WeatherState()) }
    var activeDevice by remember { mutableStateOf(SensorDevice.Primary) }
    var latestRelease by remember { mutableStateOf<AppRelease?>(null) }
    var showVersionDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val availableUpdate = latestRelease?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    suspend fun load(
        device: SensorDevice,
        jwtForRequest: String? = jwt,
        allowGoogleRefresh: Boolean = true,
    ) {
        if (jwtForRequest.isNullOrBlank()) {
            weatherState = WeatherState()
            return
        }
        weatherState = weatherState.withLoading(device, true)
        val result = loadWeather(context, jwtForRequest, device)
        when (result) {
            is WeatherResult.Success -> {
                weatherState = weatherState.withSnapshot(device, result.snapshot)
            }
            is WeatherResult.Unauthorized -> {
                if (allowGoogleRefresh && getStoredAuthProvider(context) == AUTH_PROVIDER_GOOGLE) {
                    val refreshedToken = refreshGoogleBackendToken(context)
                    if (refreshedToken != null) {
                        jwt = refreshedToken
                        load(device, refreshedToken, allowGoogleRefresh = false)
                        return
                    }
                }
                clearAuthSession(context)
                jwt = null
                weatherState = WeatherState()
            }
            is WeatherResult.Failure -> {
                weatherState = if (result.message == null) {
                    weatherState.withLoading(device, false)
                } else {
                    weatherState.withIssue(
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
            while (true) {
                load(activeDevice, pollingJwt)
                delay(BuildConfig.POLL_INTERVAL_SECONDS.seconds)
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
                    weatherState = WeatherState(loading = true)
                    when (val result = loginWithPassword(username, password)) {
                        AuthResult.InvalidCredentials -> {
                            weatherState = WeatherState(error = "Неверный логин или пароль")
                            return@launch
                        }
                        is AuthResult.Failure -> {
                            weatherState = WeatherState(error = result.message)
                            return@launch
                        }
                        is AuthResult.Success -> {
                            saveAuthSession(context, result.tokens, AUTH_PROVIDER_PASSWORD)
                            weatherState = WeatherState()
                            jwt = result.tokens.values.first()
                            load(SensorDevice.Primary, jwt)
                        }
                    }
                }
            },
            onGoogleLogin = { idToken ->
                scope.launch {
                    weatherState = WeatherState(loading = true)
                    when (val result = loginWithGoogle(idToken)) {
                        is AuthResult.Success -> {
                            saveAuthSession(context, result.tokens, AUTH_PROVIDER_GOOGLE)
                            weatherState = WeatherState()
                            jwt = result.tokens.values.first()
                        }
                        AuthResult.InvalidCredentials -> {
                            signOutGoogle(context)
                            clearAuthSession(context)
                            jwt = null
                            weatherState = WeatherState(error = "Google token отклонен backend")
                            return@launch
                        }
                        is AuthResult.Failure -> {
                            signOutGoogle(context)
                            clearAuthSession(context)
                            jwt = null
                            weatherState = WeatherState(error = result.message)
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
            onActiveDeviceChanged = { activeDevice = it },
        )
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
                            scope.launch {
                                logout()
                                signOutGoogle(context)
                                clearAuthSession(context)
                                jwt = null
                                weatherState = WeatherState()
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
    onActiveDeviceChanged: (SensorDevice) -> Unit,
) {
    val snapshot = state.snapshot
    val pagerState = rememberPagerState(pageCount = { 2 })

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
        verticalArrangement = Arrangement.Center
    ) {
        val displayedLastUpdate = if (pagerState.currentPage == 0) {
            snapshot?.lastUpdate
        } else {
            snapshot?.externalLastUpdate
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (page == 0) {
                    PrimarySensorCard(snapshot)
                } else {
                    ExternalSensorCard(snapshot)
                }
            }
        }

        Text(
            text = "Обновлено: ${displayedLastUpdate ?: "-"}",
            color = Color(0xFF777777),
            fontSize = 18.sp
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            contentAlignment = Alignment.Center,
        ) {
            val displayedDevice = if (pagerState.currentPage == 0) {
                SensorDevice.Primary
            } else {
                SensorDevice.External
            }
            val requestError = state.errorFor(displayedDevice)
            val requestWarning = state.isWarningFor(displayedDevice)
            val errorText = when {
                pagerState.isScrollInProgress -> null
                requestError != null -> requestError
                !state.hasLoaded(displayedDevice) -> null
                snapshot == null -> null
                pagerState.currentPage == 0 &&
                    (snapshot.temp == null || snapshot.hum == null) ->
                    "Сенсор не отвечает - показания не обновлены"
                pagerState.currentPage == 1 &&
                    (snapshot.externalTemp == null || snapshot.externalHum == null) ->
                    "Дополнительный датчик не отвечает"
                else -> null
            }

            if (allBackendsUnavailable && !pagerState.isScrollInProgress) {
                CircularProgressIndicator(color = Color(0xFFAAAAAA))
            } else if (errorText != null) {
                ErrorText(
                    text = errorText,
                    color = if (requestWarning) Color(0xFFD19A55) else Color.Red,
                )
            }
        }

    }
}

@Composable
private fun PrimarySensorCard(snapshot: WeatherSnapshot?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Основной датчик",
            color = Color(0xFF777777),
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(30.dp))

        Text(
            text = "Температура: ${snapshot?.temp ?: "..."} °C",
            color = Color(0xFFEEEEEE),
            fontSize = 36.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Влажность: ${snapshot?.hum ?: "..."} %",
            color = Color(0xFFCCCCCC),
            fontSize = 36.sp
        )
    }
}

@Composable
private fun ExternalSensorCard(snapshot: WeatherSnapshot?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Дополнительный датчик",
            color = Color(0xFF777777),
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(30.dp))

        Text(
            text = "Температура: ${snapshot?.externalTemp ?: "..."} °C",
            color = Color(0xFFEEEEEE),
            fontSize = 36.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Влажность: ${snapshot?.externalHum ?: "..."} %",
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
    context: Context,
    jwt: String?,
    device: SensorDevice,
): WeatherResult {
    return when (val response = fetchWeatherMetrics(jwt, device)) {
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
                        lastUpdate = metricTimestamp?.let(::formatSnapshotTimestamp),
                        externalLastUpdate = externalMetricTimestamp?.let(::formatSnapshotTimestamp),
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

private suspend fun fetchWeatherMetrics(jwt: String?, device: SensorDevice): FetchResponse {
    return withContext(Dispatchers.IO) {
        val requestUrl = apiUrl(
            if (device == SensorDevice.Primary) PRIMARY_WEATHER_PATH else EXTERNAL_WEATHER_PATH
        )
        try {
            val requestBuilder = Request.Builder()
                .url(requestUrl)
                .get()

            if (!jwt.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer routed-by-interceptor")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 401 -> FetchResponse.Unauthorized
                    !response.isSuccessful -> {
                        val text = response.body?.string()
                        val controlled = if (response.code == 400 && !text.isNullOrBlank()) {
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
                        val text = response.body?.string()
                        if (text.isNullOrBlank()) {
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
        var receivedNon5xx = false
        backendBaseUrls.forEach { baseUrl ->
            try {
                val request = Request.Builder()
                    .url(baseUrl.resolve(path)!!)
                    .tag(PinnedBackend::class.java, PinnedBackend(baseUrl))
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    receivedNon5xx = receivedNon5xx || response.code !in 500..599
                    when {
                        response.code == 401 || response.code == 403 -> invalidCredentials = true
                        response.code in 500..599 -> Unit
                        !response.isSuccessful -> visibleFailure = "Backend вернул HTTP ${response.code} при входе"
                        else -> {
                            val text = response.body?.string()
                            val token = if (text.isNullOrBlank()) null else try {
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
                Unit
            }
        }
        if (receivedNon5xx) retryInterceptor.reportReachableBackend()
        when {
            tokens.isNotEmpty() -> AuthResult.Success(tokens)
            invalidCredentials -> AuthResult.InvalidCredentials
            else -> AuthResult.Failure(visibleFailure)
        }
    }
}

private suspend fun logout() {
    withContext(Dispatchers.IO) {
        backendBaseUrls.filter { domainTokens.containsKey(it.origin()) }.forEach { baseUrl ->
            try {
                val request = Request.Builder()
                    .url(baseUrl.resolve(LOGOUT_PATH)!!)
                    .tag(PinnedBackend::class.java, PinnedBackend(baseUrl))
                    .post(ByteArray(0).toRequestBody())
                    .header("Authorization", "Bearer routed-by-interceptor")
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (_: IOException) {
                Unit
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

private data class WeatherState(
    val snapshot: WeatherSnapshot? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val primaryError: String? = null,
    val externalError: String? = null,
    val primaryWarning: Boolean = false,
    val externalWarning: Boolean = false,
    val primaryLoading: Boolean = false,
    val externalLoading: Boolean = false,
    val primaryLoaded: Boolean = false,
    val externalLoaded: Boolean = false,
) {
    fun errorFor(device: SensorDevice): String? = error ?: when (device) {
        SensorDevice.Primary -> primaryError
        SensorDevice.External -> externalError
    }

    fun isLoadingFor(device: SensorDevice): Boolean = when (device) {
        SensorDevice.Primary -> primaryLoading
        SensorDevice.External -> externalLoading
    }

    fun isWarningFor(device: SensorDevice): Boolean = when (device) {
        SensorDevice.Primary -> primaryWarning
        SensorDevice.External -> externalWarning
    }

    fun hasLoaded(device: SensorDevice): Boolean = when (device) {
        SensorDevice.Primary -> primaryLoaded
        SensorDevice.External -> externalLoaded
    }

    fun withLoading(device: SensorDevice, value: Boolean): WeatherState = when (device) {
        SensorDevice.Primary -> copy(primaryLoading = value, primaryError = null)
        SensorDevice.External -> copy(externalLoading = value, externalError = null)
    }

    fun withIssue(
        device: SensorDevice,
        message: String,
        warning: Boolean,
        clearData: Boolean,
    ): WeatherState {
        val updatedSnapshot = if (!clearData) snapshot else when (device) {
            SensorDevice.Primary -> snapshot?.copy(temp = null, hum = null, lastUpdate = null)
            SensorDevice.External -> snapshot?.copy(
                externalTemp = null,
                externalHum = null,
                externalLastUpdate = null,
            )
        }
        return when (device) {
            SensorDevice.Primary -> copy(
                snapshot = updatedSnapshot,
                primaryLoading = false,
                primaryError = message,
                primaryWarning = warning,
            )
            SensorDevice.External -> copy(
                snapshot = updatedSnapshot,
                externalLoading = false,
                externalError = message,
                externalWarning = warning,
            )
        }
    }

    fun withSnapshot(device: SensorDevice, update: WeatherSnapshot): WeatherState {
        val current = snapshot
        val merged = when (device) {
            SensorDevice.Primary -> WeatherSnapshot(
                temp = update.temp,
                hum = update.hum,
                externalTemp = current?.externalTemp,
                externalHum = current?.externalHum,
                lastUpdate = update.lastUpdate,
                externalLastUpdate = current?.externalLastUpdate,
            )
            SensorDevice.External -> WeatherSnapshot(
                temp = current?.temp,
                hum = current?.hum,
                externalTemp = update.externalTemp,
                externalHum = update.externalHum,
                lastUpdate = current?.lastUpdate,
                externalLastUpdate = update.externalLastUpdate,
            )
        }
        return when (device) {
            SensorDevice.Primary -> copy(
                snapshot = merged,
                primaryLoading = false,
                primaryLoaded = true,
                primaryError = null,
                primaryWarning = false,
            )
            SensorDevice.External -> copy(
                snapshot = merged,
                externalLoading = false,
                externalLoaded = true,
                externalError = null,
                externalWarning = false,
            )
        }
    }
}

private enum class SensorDevice {
    Primary,
    External,
}

private data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
)

private data class WeatherSnapshot(
    val temp: String?,
    val hum: String?,
    val externalTemp: String?,
    val externalHum: String?,
    val lastUpdate: String?,
    val externalLastUpdate: String?,
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
