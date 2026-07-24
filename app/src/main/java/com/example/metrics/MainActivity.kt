@file:Suppress("DEPRECATION")

package com.example.metrics

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MetricsApp()
        }
    }
}

private const val AUTH_PREFS = "weather_auth"
private const val JWT_KEY = "backend_jwt"
private const val AUTH_PROVIDER_KEY = "auth_provider"
private const val AUTH_PROVIDER_GOOGLE = "google"
private const val AUTH_PROVIDER_PASSWORD = "password"
private const val WEATHER_PATH = "/api/v1/weather/latest"
private const val GOOGLE_AUTH_PATH = "/auth/google"
private const val PASSWORD_AUTH_PATH = "/auth/password"
private const val LOGOUT_PATH = "/auth/logout"

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

private fun apiUrl(path: String): String = BuildConfig.API_BASE_URL.trimEnd('/') + path

private fun getStoredJwt(context: Context): String? {
    return context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .getString(JWT_KEY, null)
        ?.takeIf { it.isNotBlank() }
}

private fun saveAuthSession(context: Context, jwt: String, provider: String) {
    context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(JWT_KEY, jwt)
        .putString(AUTH_PROVIDER_KEY, provider)
        .apply()
}

private fun getStoredAuthProvider(context: Context): String? {
    return context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .getString(AUTH_PROVIDER_KEY, null)
        ?.takeIf { it.isNotBlank() }
}

private fun clearAuthSession(context: Context) {
    context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(JWT_KEY)
        .remove(AUTH_PROVIDER_KEY)
        .apply()
}

@Composable
fun MetricsApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = (context as ComponentActivity).lifecycle

    var jwt by remember(context) { mutableStateOf(getStoredJwt(context)) }
    var weatherState by remember { mutableStateOf(WeatherState()) }

    suspend fun load(jwtForRequest: String? = jwt, allowGoogleRefresh: Boolean = true) {
        if (jwtForRequest.isNullOrBlank()) {
            weatherState = WeatherState()
            return
        }
        weatherState = weatherState.copy(
            loading = weatherState.snapshot == null,
        )
        val result = loadWeather(context, jwtForRequest)
        when (result) {
            is WeatherResult.Success -> {
                weatherState = WeatherState(snapshot = result.snapshot)
            }
            WeatherResult.Unauthorized -> {
                if (allowGoogleRefresh && getStoredAuthProvider(context) == AUTH_PROVIDER_GOOGLE) {
                    val refreshedToken = refreshGoogleBackendToken(context)
                    if (refreshedToken != null) {
                        jwt = refreshedToken
                        load(refreshedToken, allowGoogleRefresh = false)
                        return
                    }
                }
                clearAuthSession(context)
                jwt = null
                weatherState = WeatherState()
            }
            is WeatherResult.Failure -> {
                weatherState = WeatherState(
                    snapshot = weatherState.snapshot,
                    error = result.message,
                )
            }
            WeatherResult.SensorError -> {
                weatherState = WeatherState(
                    snapshot = weatherState.snapshot,
                    error = "Сенсор не отвечает - показания не обновлены",
                )
            }
        }
    }

    LaunchedEffect(jwt, lifecycle) {
        val pollingJwt = jwt?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                load(pollingJwt)
                delay(BuildConfig.POLL_INTERVAL_SECONDS * 1_000L)
            }
        }
    }

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
                            saveAuthSession(context, result.token, AUTH_PROVIDER_PASSWORD)
                            jwt = result.token
                            load(result.token)
                        }
                    }
                }
            },
            onGoogleLogin = { idToken ->
                scope.launch {
                    weatherState = WeatherState(loading = true)
                    when (val result = loginWithGoogle(idToken)) {
                        is AuthResult.Success -> {
                            saveAuthSession(context, result.token, AUTH_PROVIDER_GOOGLE)
                            jwt = result.token
                        }
                        AuthResult.InvalidCredentials,
                        is AuthResult.Failure -> {
                            val message = when (result) {
                                AuthResult.InvalidCredentials -> "Google token отклонен backend"
                                is AuthResult.Failure -> result.message
                                is AuthResult.Success -> "Не удалось войти через Google"
                            }
                            signOutGoogle(context)
                            clearAuthSession(context)
                            jwt = null
                            weatherState = WeatherState(error = message)
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
            hasToken = jwt != null,
            onLogout = {
                val token = jwt
                scope.launch {
                    if (!token.isNullOrBlank()) {
                        logout(token)
                    }
                    signOutGoogle(context)
                    clearAuthSession(context)
                    jwt = null
                    weatherState = WeatherState()
                }
            },
        )
    }
}

@Composable
private fun WeatherScreen(
    state: WeatherState,
    hasToken: Boolean,
    onLogout: () -> Unit,
) {
    val snapshot = state.snapshot
    val pagerState = rememberPagerState(pageCount = { 2 })

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
            val errorText = state.error
                ?: if (
                    pagerState.currentPage == 1
                    && snapshot != null
                    && (snapshot.externalTemp == null || snapshot.externalHum == null)
                ) {
                    "Дополнительный датчик не отвечает"
                } else {
                    null
                }

            if (errorText != null) {
                ErrorText(errorText)
            }
        }

        if (hasToken) {
            TextButton(onClick = onLogout, enabled = !state.loading) {
                Text("Выйти")
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
private fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = Color.Red,
        fontSize = 18.sp,
        textAlign = TextAlign.Center
    )
}

private suspend fun loadWeather(context: Context, jwt: String?): WeatherResult {
    return when (val response = fetchWeatherMetrics(jwt)) {
        FetchResponse.Unauthorized -> WeatherResult.Unauthorized
        is FetchResponse.Failure -> WeatherResult.Failure(response.toUserMessage(context))
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
                if (temp == null || hum == null) {
                    WeatherResult.SensorError
                } else {
                    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                    WeatherResult.Success(
                        WeatherSnapshot(
                            temp = temp,
                            hum = hum,
                            externalTemp = externalTemp,
                            externalHum = externalHum,
                            lastUpdate = metricTimestamp?.let { sdf.format(Date(it * 1000)) },
                            externalLastUpdate = externalMetricTimestamp?.let {
                                sdf.format(Date(it * 1000))
                            },
                        )
                    )
                }
            } catch (_: JSONException) {
                WeatherResult.Failure("Backend вернул некорректные данные")
            }
        }
    }
}

private suspend fun fetchWeatherMetrics(jwt: String?): FetchResponse {
    return withContext(Dispatchers.IO) {
        val requestUrl = apiUrl(WEATHER_PATH)
        try {
            val requestBuilder = Request.Builder()
                .url(requestUrl)
                .get()

            if (!jwt.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $jwt")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 401 -> FetchResponse.Unauthorized
                    !response.isSuccessful -> FetchResponse.Failure.HttpStatus(response.code)
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
        } catch (_: IllegalArgumentException) {
            FetchResponse.Failure.InvalidUrl
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
        val requestUrl = apiUrl(path)
        try {
            val request = Request.Builder()
                .url(requestUrl)
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    return@withContext AuthResult.InvalidCredentials
                }
                if (!response.isSuccessful) {
                    return@withContext AuthResult.Failure("Backend вернул HTTP ${response.code} при входе")
                }
                val text = response.body?.string()
                if (text.isNullOrBlank()) {
                    return@withContext AuthResult.Failure("Backend вернул пустой ответ при входе")
                }
                val token = try {
                    JSONObject(text).optString("access_token")
                } catch (_: JSONException) {
                    return@withContext AuthResult.Failure("Backend вернул некорректный JSON при входе")
                }
                token.takeIf { it.isNotBlank() }
                    ?.let { AuthResult.Success(it) }
                    ?: AuthResult.Failure("Backend не вернул access_token")
            }
        } catch (_: UnknownHostException) {
            AuthResult.Failure("Backend host не найден: ${requestUrl.hostLabel()}")
        } catch (_: SocketTimeoutException) {
            AuthResult.Failure("Backend не ответил за 5 секунд")
        } catch (_: ConnectException) {
            AuthResult.Failure("Backend недоступен: ${requestUrl.hostLabel()}")
        } catch (_: NoRouteToHostException) {
            AuthResult.Failure("Нет маршрута до backend: ${requestUrl.hostLabel()}")
        } catch (_: SSLException) {
            AuthResult.Failure("TLS/SSL ошибка при подключении к backend")
        } catch (_: IOException) {
            AuthResult.Failure("Ошибка сети при подключении к backend")
        } catch (_: IllegalArgumentException) {
            AuthResult.Failure("Некорректный API_BASE_URL")
        }
    }
}

private suspend fun logout(jwt: String) {
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl(LOGOUT_PATH))
                .post(ByteArray(0).toRequestBody())
                .header("Authorization", "Bearer $jwt")
                .build()

            httpClient.newCall(request).execute().close()
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
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
                is AuthResult.Success -> result.token
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
    }?.also { token ->
        saveAuthSession(context, token, AUTH_PROVIDER_GOOGLE)
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

private fun hasValidatedInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun FetchResponse.Failure.toUserMessage(context: Context): String {
    return when (this) {
        FetchResponse.Failure.EmptyBody -> "Backend вернул пустой ответ"
        FetchResponse.Failure.InvalidJson -> "Backend вернул некорректный JSON"
        FetchResponse.Failure.InvalidUrl -> "Некорректный API_BASE_URL"
        FetchResponse.Failure.Io -> {
            if (hasValidatedInternet(context)) {
                "Ошибка сети при подключении к backend"
            } else {
                "Нет валидного интернета - данные не обновлены"
            }
        }
        FetchResponse.Failure.Timeout -> "Backend не ответил за 5 секунд"
        FetchResponse.Failure.Tls -> "TLS/SSL ошибка при подключении к backend"
        is FetchResponse.Failure.BackendUnavailable -> "Backend недоступен: $host"
        is FetchResponse.Failure.HttpStatus -> "Backend вернул HTTP $code"
        is FetchResponse.Failure.UnknownHost -> "Backend host не найден: $host"
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
)

private data class WeatherSnapshot(
    val temp: String,
    val hum: String,
    val externalTemp: String?,
    val externalHum: String?,
    val lastUpdate: String?,
    val externalLastUpdate: String?,
)

private sealed interface WeatherResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherResult
    data object Unauthorized : WeatherResult
    data class Failure(val message: String) : WeatherResult
    data object SensorError : WeatherResult
}

private sealed interface FetchResponse {
    data class Success(val json: JSONObject) : FetchResponse
    data object Unauthorized : FetchResponse
    sealed interface Failure : FetchResponse {
        data object EmptyBody : Failure
        data object InvalidJson : Failure
        data object InvalidUrl : Failure
        data object Io : Failure
        data object Timeout : Failure
        data object Tls : Failure
        data class BackendUnavailable(val host: String) : Failure
        data class HttpStatus(val code: Int) : Failure
        data class UnknownHost(val host: String) : Failure
    }
}

private sealed interface AuthResult {
    data class Success(val token: String) : AuthResult
    data object InvalidCredentials : AuthResult
    data class Failure(val message: String) : AuthResult
}
