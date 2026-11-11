package com.flightconnect.app

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Handler
import android.os.Looper

/** Passive observation only: no network requests, radio changes, or process-wide routing changes. */
class WifiConnectionMonitor(
    private val manager: ConnectivityManager,
    private val wifiManager: WifiManager,
    private val onChanged: (WifiServiceStatus, String, String?) -> Unit,
) {
    private val tracker = WifiConnectionTracker<Network>()
    private val handler = Handler(Looper.getMainLooper())
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var generation = 0
    private var defaultNetwork: Network? = null
    private var route = "Default route: checking…"
    val selectedNetwork: Network? get() = tracker.selectedNetwork
    val revision: Long get() = tracker.revision
    val status: WifiServiceStatus get() = tracker.status
    val selectedSsid: String? get() = tracker.selectedSsid
    fun isCurrent(network: Network, revision: Long): Boolean = tracker.isCurrent(network, revision)

    private fun publish() = onChanged(tracker.status, route, tracker.selectedSsid)

    @Suppress("DEPRECATION")
    private fun readSsid(caps: NetworkCapabilities): String? = try {
        normalizedSsid(if (Build.VERSION.SDK_INT >= 31) (caps.transportInfo as? WifiInfo)?.ssid
            else wifiManager.connectionInfo?.ssid)
    } catch (_: SecurityException) { null }

    private inner class WifiCallback : ConnectivityManager.NetworkCallback {
        private val session: Int
        constructor(session: Int) : super() { this.session = session }
        @RequiresApi(31)
        constructor(session: Int, flags: Int) : super(flags) { this.session = session }
            override fun onAvailable(network: Network) {
                if (session != generation) return
                // Do not query capabilities here: Android delivers the ordered snapshot next.
                tracker.available(network)
                publish()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (session != generation) return
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    tracker.capabilities(network, WifiObservation(
                        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                        ssid = readSsid(caps),
                    ))
                } else tracker.lost(network)
                publish()
            }
            override fun onLost(network: Network) {
                if (session != generation) return
                tracker.lost(network)
                publish()
            }
        }

    fun restartForIdentityAccessChange() {
        if (wifiCallback == null) return
        stop()
        start()
    }

    fun start() {
        if (wifiCallback != null) return
        tracker.clear()
        route = "Default route: none"
        val session = ++generation
        val wifi = if (Build.VERSION.SDK_INT >= 31)
            WifiCallback(session, ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        else WifiCallback(session)
        val default = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (session != generation) return
                defaultNetwork = network
                route = "Default route: checking…"
                publish()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (session != generation || network != defaultNetwork) return
                route = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "Default route: VPN. Its internet access does not prove aircraft Wi-Fi service."
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Default route: mobile data. Mobile internet does not prove aircraft Wi-Fi service."
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Default route: Wi-Fi"
                    else -> "Default route: another network"
                }
                publish()
            }
            override fun onLost(network: Network) {
                if (session != generation || network != defaultNetwork) return
                defaultNetwork = null
                route = "Default route: none"
                publish()
            }
        }
        try {
            // Deliberately do not require INTERNET or VALIDATED: waiting/portal Wi-Fi must be visible.
            manager.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), wifi, handler)
            wifiCallback = wifi
            manager.registerDefaultNetworkCallback(default, handler)
            defaultCallback = default
            publish()
        } catch (_: RuntimeException) {
            stop()
            onChanged(WifiServiceStatus.UNAVAILABLE, "Default route: unknown", null)
        }
    }

    fun stop() {
        generation++ // Ignore callbacks already queued from the previous activity session.
        wifiCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        defaultCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        wifiCallback = null
        defaultCallback = null
        defaultNetwork = null
        route = "Default route: checking…"
        tracker.clear() // Invalidates any in-flight manual probe.
    }
}
