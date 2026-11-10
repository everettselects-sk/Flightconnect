package com.flightconnect.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private val wm by lazy { applicationContext.getSystemService(WifiManager::class.java) }
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private fun wifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    private fun requestScan(): Boolean = try {
        wm.startScan()
    } catch (_: SecurityException) { false }
    private fun scanAirlines(): List<String> {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return emptyList()
        return try {
            wm.scanResults.map { it.SSID }.filter { it.isNotBlank() }
                .filter { s -> listOf("alaska", "gogo", "delta", "wifi-onboard", "inflight", "aainflight", "southwestwifi").any { s.contains(it, ignoreCase = true) } }
                .distinct()
        } catch (_: SecurityException) { emptyList() }
    }
    private fun suggestNetwork(ssid: String): String {
        if (android.os.Build.VERSION.SDK_INT < 29) return "Use Wi-Fi settings to join $ssid."
        return try {
            val suggestion = WifiNetworkSuggestion.Builder().setSsid(ssid).build()
            val result = wm.addNetworkSuggestions(listOf(suggestion))
            if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS)
                "Requested Android to consider $ssid. Approve the system prompt if shown; connection is not guaranteed."
            else "Android rejected suggestion ($result). Open Wi-Fi settings and select $ssid."
        } catch (e: Exception) { "Connection request failed: ${e.javaClass.simpleName}" }
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
    private suspend fun recoveryCheck(): String = withContext(Dispatchers.IO) {
        val net = wifi() ?: return@withContext "Aircraft Wi-Fi not connected. Open Wi-Fi settings and join the onboard network."
        val endpoints = listOf("http://connectivitycheck.gstatic.com/generate_204", "https://www.google.com/generate_204")
        val results = endpoints.map { endpoint ->
            try {
                val connection = net.openConnection(URL(endpoint)) as HttpURLConnection
                connection.connectTimeout = 4500
                connection.readTimeout = 4500
                connection.instanceFollowRedirects = false
                try {
                    val code = connection.responseCode
                    val redirect = connection.getHeaderField("Location")
                    "$endpoint → HTTP $code" + (redirect?.let { " (redirect: $it)" } ?: "")
                } finally { connection.disconnect() }
            } catch (e: Exception) { "$endpoint → ${e.javaClass.simpleName}" }
        }
        results.joinToString("\\n") + "\\n" + "If blocked, open the Alaska portal. No phone restart attempted."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val flightColors = darkColorScheme(
                primary = Color(0xFF8E6BE8),
                onPrimary = Color.White,
                secondary = Color(0xFF49E5A1),
                background = Color(0xFF0B1020),
                onBackground = Color(0xFFF5F4FA),
                surface = Color(0xFF151B2D),
                onSurface = Color(0xFFF5F4FA),
                surfaceVariant = Color(0xFF232A3E),
                onSurfaceVariant = Color(0xFFD6D3E2),
                outline = Color(0xFF687088)
            )
            MaterialTheme(colorScheme = flightColors) {
                var status by remember { mutableStateOf("Tap Diagnose to check your connection.") }
                var airline by remember { mutableStateOf("Alaska") }
                var discovered by remember { mutableStateOf(emptyList<String>()) }
                var autoStatus by remember { mutableStateOf("Preparing automatic discovery…") }
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            discovered = scanAirlines()
                            autoStatus = if (discovered.isEmpty()) "Searching for airline Wi-Fi…" else "Found: " + discovered.joinToString()
                        }
                    }
                    val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    onDispose { unregisterReceiver(receiver) }
                }
                LaunchedEffect(Unit) {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        requestScan()
                        discovered = scanAirlines()
                        autoStatus = if (discovered.isEmpty()) "Searching for airline Wi-Fi…" else "Found: " + discovered.joinToString()
                    } else autoStatus = "Allow location access once to enable Wi-Fi discovery."
                }
                val locationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    status = if (granted) "Permission granted. Starting discovery." else "Location permission is needed to see nearby Wi-Fi names."
                    if (granted) { requestScan(); discovered = scanAirlines() }
                }
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
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("FlightConnect", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.secondary)
                        Text("Beta 0.5 • Automatic discovery")
                        Text(autoStatus, color = MaterialTheme.colorScheme.secondary)
                        Text("Find aircraft Wi-Fi", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = {
                            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                locationRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                requestScan()
                                discovered = scanAirlines()
                                status = if (discovered.isEmpty()) "No known airline SSIDs in available scan results. Turn on Location and Wi-Fi, wait, then retry." else "Found: " + discovered.joinToString()
                                record(status)
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Find Airline Wi-Fi") }
                        discovered.forEach { ssid ->
                            OutlinedButton(onClick = { status = suggestNetwork(ssid); record(status) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Request connection: $ssid")
                            }
                        }
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
                        Button(onClick = {
                            busy = true
                            scope.launch { status = recoveryCheck(); record(status); busy = false }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Run Recovery Check") }
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
                            val report = "FlightConnect Beta 0.3\n" + history.joinToString("\n---\n")
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
