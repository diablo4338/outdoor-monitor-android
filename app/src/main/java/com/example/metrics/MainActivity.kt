package com.example.metrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MetricsScreen()
        }
    }
}

/* ----------- UDP CLIENT ----------- */

suspend fun fetchUdpMetrics(
    host: String = "94.142.137.204",
    port: Int = 32934
): JSONObject? {
    return withContext(Dispatchers.IO) {

        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket()
            socket.soTimeout = 5000   // timeout = 5 sec

            val sendPacket = DatagramPacket(
                "GET".toByteArray(),
                3,
                InetAddress.getByName(host),
                port
            )
            socket.send(sendPacket)

            val buf = ByteArray(2048)
            val receivePacket = DatagramPacket(buf, buf.size)

            socket.receive(receivePacket)

            val text = String(receivePacket.data, 0, receivePacket.length)
            if (text.isBlank()) return@withContext null

            JSONObject(text)

        } catch (e: java.net.SocketTimeoutException) {
            JSONObject("""{"error":"timeout"}""")
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }
}

/* ----------- INTERNET CHECK ----------- */

fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/* ----------- UI ----------- */

@Composable
fun MetricsScreen() {

    var temp by remember { mutableStateOf<String?>(null) }
    var hum by remember { mutableStateOf<String?>(null) }

    var lastUpdate by remember { mutableStateOf("—") }

    var internetError by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf(false) }
    var sensorError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun tsToString(ts: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(ts * 1000))
    }

    suspend fun loadData() {

        loading = true
        internetError = false
        serverError = false
        sensorError = false

        // интернет
        if (!hasInternet(context)) {
            internetError = true
            loading = false
            return
        }

        val json = fetchUdpMetrics()

        if (json == null) {
            serverError = true
            loading = false
            return
        }

        if (json.optString("error") == "timeout") {
            serverError = true
            loading = false
            return
        }

        // Парсим
        val t = if (json.isNull("temp")) null else json.get("temp").toString()
        val h = if (json.isNull("hum")) null else json.get("hum").toString()
        val ts = if (json.isNull("timestamp")) null else json.getLong("timestamp")

        if (t == null || h == null || ts == null) {
            sensorError = true
            loading = false
            return
        }

        // Данные валидные — обновляем
        temp = t
        hum = h
        lastUpdate = tsToString(ts)

        loading = false
    }

    // первый запуск
    LaunchedEffect(Unit) {
        loadData()
    }

    // ---------- UI ----------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "Температура: ${temp ?: "…"} °C",
            color = Color(0xFFEEEEEE),
            fontSize = 36.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Влажность: ${hum ?: "…"} %",
            color = Color(0xFFCCCCCC),
            fontSize = 36.sp
        )

        Spacer(modifier = Modifier.height(30.dp))

        Text(
            text = "Обновлено: $lastUpdate",
            color = Color(0xFF777777),
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (internetError) {
            Text(
                text = "Нет интернета — данные не обновлены",
                color = Color.Red,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }

        if (serverError && !internetError) {
            Text(
                text = "Сервер с метриками не отвечает",
                color = Color.Red,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }

        if (sensorError && !internetError && !serverError) {
            Text(
                text = "Сенсор не отвечает — показания не обновлены",
                color = Color.Red,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = { scope.launch { loadData() } }
        ) {
            Text(if (loading) "Жду ответа..." else "Обновить")
        }
    }
}
