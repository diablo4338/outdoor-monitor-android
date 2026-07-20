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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
private const val WEATHER_PATH = "/api/v1/weather/latest"
private const val GOOGLE_AUTH_PATH = "/auth/google"
private const val PASSWORD_AUTH_PATH = "/auth/password"

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

private fun saveJwt(context: Context, jwt: String) {
    context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(JWT_KEY, jwt)
        .apply()
}

private fun clearJwt(context: Context) {
    context
        .getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(JWT_KEY)
        .apply()
}

@Composable
fun MetricsApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var jwt by remember { mutableStateOf<String?>(null) }
    var needsAuth by remember { mutableStateOf(true) }
    var weatherState by remember { mutableStateOf(WeatherState(loading = true)) }

    suspend fun load(jwtForRequest: String? = jwt) {
        if (jwtForRequest.isNullOrBlank()) {
            needsAuth = true
            weatherState = WeatherState()
            return
        }
        weatherState = weatherState.copy(
            loading = weatherState.snapshot == null,
        )
        val result = loadWeather(context, jwtForRequest)
        when (result) {
            is WeatherResult.Success -> {
                needsAuth = false
                weatherState = WeatherState(snapshot = result.snapshot)
            }
            WeatherResult.Unauthorized -> {
                clearJwt(context)
                jwt = null
                needsAuth = true
                weatherState = WeatherState()
            }
            WeatherResult.NoInternet -> {
                weatherState = WeatherState(
                    snapshot = weatherState.snapshot,
                    error = "Нет интернета - данные не обновлены",
                )
            }
            WeatherResult.ServerError -> {
                weatherState = WeatherState(
                    snapshot = weatherState.snapshot,
                    error = "Сервер с метриками не отвечает",
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

    LaunchedEffect(Unit) {
        val storedJwt = getStoredJwt(context)
        jwt = storedJwt
        needsAuth = storedJwt == null
        if (storedJwt == null) {
            weatherState = WeatherState()
        }
    }

    LaunchedEffect(needsAuth, jwt) {
        if (needsAuth) return@LaunchedEffect
        while (true) {
            load(jwt)
            delay(BuildConfig.POLL_INTERVAL_SECONDS * 1_000L)
        }
    }

    if (needsAuth) {
        LoginScreen(
            onPasswordLogin = { username, password ->
                scope.launch {
                    weatherState = WeatherState(loading = true)
                    val token = loginWithPassword(context, username, password)
                    if (token == null) {
                        weatherState = WeatherState(error = "Неверный логин или пароль")
                        return@launch
                    }
                    jwt = token
                    load(token)
                }
            },
            onGoogleLogin = { idToken ->
                scope.launch {
                    weatherState = WeatherState(loading = true)
                    val token = loginWithGoogle(context, idToken)
                    if (token == null) {
                        weatherState = WeatherState(error = "Не удалось войти через Google")
                        return@launch
                    }
                    jwt = token
                    needsAuth = false
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
                clearJwt(context)
                jwt = null
                needsAuth = true
                weatherState = WeatherState()
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
                .weight(1f),
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

        Spacer(modifier = Modifier.height(14.dp))

        if (state.error != null) {
            ErrorText(state.error)
        }

        if (pagerState.currentPage == 1 && snapshot?.externalSensorOk == false) {
            ErrorText("Дополнительный датчик не отвечает")
        }

        if (hasToken) {
            Spacer(modifier = Modifier.height(40.dp))
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

    val context = LocalContext.current
    val googleClient = remember {
        val optionsBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            optionsBuilder.requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
        }
        GoogleSignIn.getClient(context, optionsBuilder.build())
    }

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
            enabled = !loading,
            singleLine = true,
            label = { Text("Имя") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            enabled = !loading,
            singleLine = true,
            label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = {
                localError = null
                onPasswordLogin(username.trim(), password)
            },
            enabled = !loading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Вхожу..." else "Войти")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                    localError = "Google OAuth client id не настроен"
                    return@Button
                }
                localError = null
                signInLauncher.launch(googleClient.signInIntent)
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Войти через Google")
        }

        val message = localError ?: error
        if (message != null) {
            Spacer(modifier = Modifier.height(18.dp))
            ErrorText(message)
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        color = Color.Red,
        fontSize = 18.sp,
        textAlign = TextAlign.Center
    )
}

private suspend fun loadWeather(context: Context, jwt: String?): WeatherResult {
    if (!hasInternet(context)) return WeatherResult.NoInternet

    return when (val response = fetchWeatherMetrics(jwt)) {
        FetchResponse.Unauthorized -> WeatherResult.Unauthorized
        FetchResponse.NetworkError -> WeatherResult.ServerError
        FetchResponse.Timeout -> WeatherResult.ServerError
        is FetchResponse.Success -> {
            val json = response.json
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
            val ts = if (json.isNull("timestamp")) null else json.getLong("timestamp")
            val externalTs = if (json.isNull("external_timestamp")) {
                null
            } else {
                json.getLong("external_timestamp")
            }
            val sensorOk = json.optBoolean(
                "sensor_ok",
                temp != null && hum != null && ts != null
            )
            val externalSensorOk = json.optBoolean(
                "external_sensor_ok",
                externalTemp != null && externalHum != null && externalTs != null
            )

            if (!sensorOk || temp == null || hum == null || ts == null) {
                WeatherResult.SensorError
            } else {
                val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                WeatherResult.Success(
                    WeatherSnapshot(
                        temp = temp,
                        hum = hum,
                        externalTemp = externalTemp,
                        externalHum = externalHum,
                        externalSensorOk = externalSensorOk,
                        lastUpdate = sdf.format(Date(ts * 1000)),
                        externalLastUpdate = externalTs?.let { sdf.format(Date(it * 1000)) },
                    )
                )
            }
        }
    }
}

private suspend fun fetchWeatherMetrics(jwt: String?): FetchResponse {
    return withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url(apiUrl(WEATHER_PATH))
                .get()

            if (!jwt.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $jwt")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 401 -> FetchResponse.Unauthorized
                    !response.isSuccessful -> FetchResponse.NetworkError
                    else -> {
                        val text = response.body?.string()
                        if (text.isNullOrBlank()) FetchResponse.NetworkError
                        else FetchResponse.Success(JSONObject(text))
                    }
                }
            }
        } catch (_: java.io.InterruptedIOException) {
            FetchResponse.Timeout
        } catch (_: Exception) {
            FetchResponse.NetworkError
        }
    }
}

private suspend fun loginWithGoogle(context: Context, idToken: String): String? {
    val payload = JSONObject()
        .put("id_token", idToken)
        .toString()
    return exchangeToken(context, GOOGLE_AUTH_PATH, payload)
}

private suspend fun loginWithPassword(context: Context, username: String, password: String): String? {
    val payload = JSONObject()
        .put("username", username)
        .put("password", password)
        .toString()
    return exchangeToken(context, PASSWORD_AUTH_PATH, payload)
}

private suspend fun exchangeToken(context: Context, path: String, payload: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl(path))
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val text = response.body?.string()
                if (text.isNullOrBlank()) return@withContext null
                val jwt = JSONObject(text).optString("access_token").takeIf { it.isNotBlank() }
                if (jwt != null) saveJwt(context, jwt)
                jwt
            }
        } catch (_: Exception) {
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

private fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
    val externalSensorOk: Boolean,
    val lastUpdate: String,
    val externalLastUpdate: String?,
)

private sealed interface WeatherResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherResult
    data object Unauthorized : WeatherResult
    data object NoInternet : WeatherResult
    data object ServerError : WeatherResult
    data object SensorError : WeatherResult
}

private sealed interface FetchResponse {
    data class Success(val json: JSONObject) : FetchResponse
    data object Unauthorized : FetchResponse
    data object Timeout : FetchResponse
    data object NetworkError : FetchResponse
}
