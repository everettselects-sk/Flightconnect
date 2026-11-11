package com.flightconnect.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.ConnectivityManager
import android.location.LocationManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
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
    private enum class SetupDialog { LOCATION_PERMISSION, APP_SETTINGS, DEVICE_LOCATION }
    private enum class PermissionRequest { LOCATION, NOTIFICATIONS }

    private val setupPreferences by lazy { getSharedPreferences("startup_permissions", MODE_PRIVATE) }
    private var discovered by mutableStateOf(emptyList<String>())
    private var autoStatus by mutableStateOf("Preparing automatic discovery…")
    private var setupDialog by mutableStateOf<SetupDialog?>(null)
    private var permissionRequestInFlight by mutableStateOf<PermissionRequest?>(null)
    private var settingsInFlight by mutableStateOf(false)
    private var locationAttempted = false
    private var locationSettingsOffered = false
    private var discoveryStarted = false
    private val setupInteractionInProgress: Boolean
        get() = permissionRequestInFlight != null || settingsInFlight || setupDialog != null

    private val locationRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequestInFlight = null
        // Read the current grant, including approximate-only choices and changes during recreation.
        if (!hasPreciseLocation()) {
            setupDialog = if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION))
                SetupDialog.LOCATION_PERMISSION else SetupDialog.APP_SETTINGS
        }
        continueSetup()
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRequestInFlight = null
        continueSetup()
    }
    private val settingsRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        settingsInFlight = false
        discoveryStarted = false
        continueSetup()
    }
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                refreshScanResults()
            } else {
                // Device Location or Wi-Fi can change while this activity remains visible.
                discoveryStarted = false
                continueSetup()
            }
        }
    }

    private fun hasPreciseLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun deviceLocationEnabled(): Boolean =
        LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))

    private fun discoveryAvailable(): Boolean = hasPreciseLocation() && deviceLocationEnabled()

    private fun refreshScanResults(scanAccepted: Boolean? = null) {
        when {
            !hasPreciseLocation() -> {
                discoveryStarted = false
                discovered = emptyList()
                autoStatus = "Location access is needed to see nearby Wi-Fi names. Allow precise location to scan."
            }
            !deviceLocationEnabled() -> {
                discoveryStarted = false
                discovered = emptyList()
                autoStatus = "Device Location is off. Turn it on to discover airline Wi-Fi."
            }
            !wm.isWifiEnabled -> {
                discovered = emptyList()
                autoStatus = "Wi-Fi is off. Turn it on in Wi-Fi settings to discover airline networks."
            }
            else -> {
                discovered = scanAirlines()
                discovered.firstOrNull()?.let(::notifyReady)
                autoStatus = when {
                    discovered.isNotEmpty() -> "Found: " + discovered.joinToString()
                    scanAccepted == false -> "Android did not start a new scan. Using available results; wait a moment and retry."
                    else -> "Searching for airline Wi-Fi…"
                }
            }
        }
    }

    private fun continueSetup() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        // Settings can also be changed outside the in-app shortcut while a prompt is visible.
        if (hasPreciseLocation() && (setupDialog == SetupDialog.LOCATION_PERMISSION || setupDialog == SetupDialog.APP_SETTINGS)) {
            setupDialog = null
        }
        if (deviceLocationEnabled() && setupDialog == SetupDialog.DEVICE_LOCATION) {
            setupDialog = null
        }
        refreshScanResults()
        while (true) {
            val action = nextStartupAction(StartupState(
                resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                interactionInProgress = setupInteractionInProgress,
                preciseLocationGranted = hasPreciseLocation(),
                deviceLocationEnabled = deviceLocationEnabled(),
                locationAttempted = locationAttempted,
                locationRequestedBefore = setupPreferences.getBoolean("location_requested", false),
                showLocationRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION),
                locationSettingsOffered = locationSettingsOffered,
                discoveryStarted = discoveryStarted,
                notificationsNeeded = Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !setupPreferences.getBoolean("notifications_requested", false),
            ))
            when (action) {
                StartupAction.REQUEST_LOCATION -> {
                    requestLocationAccess()
                    return
                }
                StartupAction.SHOW_APP_SETTINGS -> {
                    locationAttempted = true
                    setupDialog = SetupDialog.APP_SETTINGS
                    return
                }
                StartupAction.SHOW_LOCATION_SETTINGS -> {
                    locationSettingsOffered = true
                    setupDialog = SetupDialog.DEVICE_LOCATION
                    return
                }
                StartupAction.START_DISCOVERY -> {
                    discoveryStarted = true
                    refreshScanResults(requestScan())
                }
                StartupAction.REQUEST_NOTIFICATIONS -> {
                    permissionRequestInFlight = PermissionRequest.NOTIFICATIONS
                    setupPreferences.edit().putBoolean("notifications_requested", true).apply()
                    notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                StartupAction.WAIT, StartupAction.DONE -> return
            }
        }
    }

    private fun requestLocationAccess() {
        if (permissionRequestInFlight != null || settingsInFlight) return
        locationAttempted = true
        permissionRequestInFlight = PermissionRequest.LOCATION
        setupPreferences.edit().putBoolean("location_requested", true).apply()
        locationRequest.launch(arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ))
    }

    private fun openSetupSettings(intent: Intent) {
        setupDialog = null
        settingsInFlight = true
        settingsRequest.launch(intent)
    }

    private fun retryDiscovery() {
        if (setupInteractionInProgress) return
        locationAttempted = false
        locationSettingsOffered = false
        discoveryStarted = false
        continueSetup()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, discoveryReceiver, IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPostResume() {
        super.onPostResume()
        // AndroidX dispatches ON_RESUME after this callback on newer Android releases.
        // Posting also covers permission/settings results delivered before the resumed state.
        window.decorView.post { continueSetup() }
    }

    override fun onStop() {
        unregisterReceiver(discoveryReceiver)
        discoveryStarted = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("locationAttempted", locationAttempted)
        outState.putBoolean("locationSettingsOffered", locationSettingsOffered)
        outState.putBoolean("settingsInFlight", settingsInFlight)
        outState.putString("permissionRequestInFlight", permissionRequestInFlight?.name)
        outState.putString("setupDialog", setupDialog?.name)
        super.onSaveInstanceState(outState)
    }

    private var lastNotifiedSsid: String? = null
    private fun notifyReady(ssid: String) {
        if (lastNotifiedSsid == ssid) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("wifi_ready", "Airline Wi-Fi ready", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("connect_ssid", ssid)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 22, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "wifi_ready")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Airline Wi-Fi ready")
            .setContentText("$ssid found. Tap Connect to request access.")
            .setContentIntent(pending)
            .addAction(Notification.Action.Builder(null, "Connect", pending).build())
            .setAutoCancel(true).build()
        manager.notify(220, notification)
        lastNotifiedSsid = ssid
    }
    private val wm by lazy { applicationContext.getSystemService(WifiManager::class.java) }
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private fun wifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    private fun requestScan(): Boolean = try {
        discoveryAvailable() && wm.startScan()
    } catch (_: SecurityException) { false }
    private fun scanAirlines(): List<String> {
        if (!discoveryAvailable()) return emptyList()
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
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            locationAttempted = it.getBoolean("locationAttempted")
            locationSettingsOffered = it.getBoolean("locationSettingsOffered")
            settingsInFlight = it.getBoolean("settingsInFlight")
            permissionRequestInFlight = it.getString("permissionRequestInFlight")?.let(PermissionRequest::valueOf)
            setupDialog = it.getString("setupDialog")?.let(SetupDialog::valueOf)
        }
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
                setupDialog?.let { dialog ->
                    val dismiss = {
                        setupDialog = null
                        continueSetup()
                    }
                    AlertDialog(
                        onDismissRequest = dismiss,
                        title = { Text(when (dialog) {
                            SetupDialog.LOCATION_PERMISSION -> "Allow location for Wi-Fi discovery"
                            SetupDialog.APP_SETTINGS -> "Enable location access in app settings"
                            SetupDialog.DEVICE_LOCATION -> "Turn on device Location"
                        }) },
                        text = { Text(when (dialog) {
                            SetupDialog.LOCATION_PERMISSION -> "Android needs precise location permission to show nearby Wi-Fi names. Approximate location is not enough for Wi-Fi scans."
                            SetupDialog.APP_SETTINGS -> "Android is no longer showing the location prompt. Open Permissions → Location, allow access while using FlightConnect, and enable precise location if shown."
                            SetupDialog.DEVICE_LOCATION -> "Android requires the device Location switch to be on for Wi-Fi scans. Turn it on, then return to FlightConnect. Discovery will resume automatically."
                        }) },
                        confirmButton = {
                            TextButton(onClick = {
                                when (dialog) {
                                    SetupDialog.LOCATION_PERMISSION -> {
                                        setupDialog = null
                                        requestLocationAccess()
                                    }
                                    SetupDialog.APP_SETTINGS -> openSetupSettings(Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null),
                                    ))
                                    SetupDialog.DEVICE_LOCATION -> openSetupSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }
                            }) { Text(if (dialog == SetupDialog.LOCATION_PERMISSION) "Try again" else "Open settings") }
                        },
                        dismissButton = { TextButton(onClick = dismiss) { Text("Not now") } },
                    )
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
                        Text("Beta 0.8 • Automatic setup and discovery")
                        Text(autoStatus, color = MaterialTheme.colorScheme.secondary)
                        if (intent?.getStringExtra("connect_ssid") != null) {
                            val target = intent.getStringExtra("connect_ssid")!!
                            Button(onClick = {
                                status = suggestNetwork(target)
                                record(status)
                                intent.removeExtra("connect_ssid")
                            }, modifier = Modifier.fillMaxWidth()) { Text("Connect to $target") }
                        }
                        Text("Find aircraft Wi-Fi", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { retryDiscovery() }, enabled = !setupInteractionInProgress,
                            modifier = Modifier.fillMaxWidth()) { Text("Find Airline Wi-Fi") }
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
