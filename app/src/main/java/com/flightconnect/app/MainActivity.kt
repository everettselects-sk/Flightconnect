package com.flightconnect.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private fun wifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    private fun diagnosis(): String {
        val active = cm.activeNetwork?.let(cm::getNetworkCapabilities)
        val wifiCaps = wifi()?.let(cm::getNetworkCapabilities)
        val route = when {
            active == null -> "No active default network"
            active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (possible fallback)"
            else -> "Other network"
        }
        return "Default route: $route\nWi-Fi connected: ${wifiCaps != null}\nWi-Fi validated: ${wifiCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}\nCaptive portal detected: ${wifiCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true}"
    }
    private suspend fun probe(): String = withContext(Dispatchers.IO) {
        val network = wifi() ?: return@withContext "No Wi-Fi network detected. Join the aircraft Wi-Fi first."
        try {
            val connection = network.openConnection(URL("https://www.google.com/generate_204")) as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            try {
                val code = connection.responseCode
                val redirect = connection.getHeaderField("Location")
                "Wi-Fi-only HTTP $code" + (redirect?.let { "\nRedirect: $it" } ?: "") +
                    if (code == 204) "\nInternet verified" else "\nPortal or network restriction possible"
            } finally { connection.disconnect() }
        } catch (e: Exception) {
            "Wi-Fi-only check failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("Tap Diagnose to check your connection.") }
                var airline by remember { mutableStateOf("Alaska") }
                var busy by remember { mutableStateOf(false) }
                val history = remember { mutableStateListOf<String>() }
                val scope = rememberCoroutineScope()
                fun record(message: String) {
                    history.add(0, message)
                    if (history.size > 40) history.removeAt(history.lastIndex)
                }
                fun open(url: String) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    record("Opened $url")
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("FlightConnect", style = MaterialTheme.typography.headlineMedium)
                        Text("Beta 0.1 • Airline Wi-Fi diagnostics")
                        Text("Recovery is guided; Android does not permit silent hardware resets.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { status = diagnosis(); record(status) }, modifier = Modifier.fillMaxWidth()) {
                            Text("1. Diagnose Connection")
                        }
                        Button(onClick = {
                            busy = true
                            scope.launch { status = probe(); record(status); busy = false }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Checking…" else "2. Verify Internet Over Wi-Fi")
                        }
                        Text(status)
                        HorizontalDivider()
                        Text("3. Recover Connection", style = MaterialTheme.typography.titleMedium)
                        Text("Reconnect to aircraft Wi-Fi if necessary, then reopen its sign-in portal.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = airline == "Alaska", onClick = { airline = "Alaska" }, label = { Text("Alaska") })
                            FilterChip(selected = airline == "Delta", onClick = { airline = "Delta" }, label = { Text("Delta") })
                        }
                        Button(onClick = {
                            open(if (airline == "Alaska") "https://wifi.alaskaair.com" else "https://wifi.delta.com")
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open $airline Portal") }
                        OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); record("Opened Wi-Fi settings")
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open Wi-Fi Settings") }
                        OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)); record("Opened network settings")
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open Network Settings") }
                        OutlinedButton(onClick = {
                            val report = "FlightConnect Beta 0.1\n" + history.joinToString("\n---\n")
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
                            }, "Share diagnostics"))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Share Diagnostic Log") }
                        Text("Recent diagnostics", style = MaterialTheme.typography.titleMedium)
                        history.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
