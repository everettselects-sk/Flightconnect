package com.flightconnect.app

/** Only observations of a physical Wi-Fi network belong here, never cellular or VPN state. */
data class WifiObservation(val validated: Boolean, val captivePortal: Boolean, val ssid: String? = null)

enum class WifiServiceStatus {
    DISCONNECTED, CHECKING, WAITING_FOR_INTERNET, SIGN_IN_REQUIRED, ONLINE, MULTIPLE_NETWORKS, UNAVAILABLE,
}

fun wifiServiceStatus(observations: Collection<WifiObservation?>): WifiServiceStatus = when {
    observations.isEmpty() -> WifiServiceStatus.DISCONNECTED
    observations.size > 1 -> WifiServiceStatus.MULTIPLE_NETWORKS
    observations.single() == null -> WifiServiceStatus.CHECKING
    // A portal observation must not be described as working internet, even during a transition.
    observations.single()!!.captivePortal -> WifiServiceStatus.SIGN_IN_REQUIRED
    observations.single()!!.validated -> WifiServiceStatus.ONLINE
    else -> WifiServiceStatus.WAITING_FOR_INTERNET
}

/** Keeps callback data by network identity so a lost old network cannot clear a newer one. */
class WifiConnectionTracker<K> {
    private val networks = linkedMapOf<K, WifiObservation?>()
    var revision: Long = 0
        private set
    val status: WifiServiceStatus get() = wifiServiceStatus(networks.values)
    val selectedNetwork: K? get() = networks.keys.singleOrNull()
    val selectedSsid: String? get() = networks.values.singleOrNull()?.ssid

    fun available(network: K) {
        if (!networks.containsKey(network)) {
            networks[network] = null
            revision++
        }
    }

    fun capabilities(network: K, observation: WifiObservation) {
        if (!networks.containsKey(network) || networks[network] != observation) {
            networks[network] = observation
            revision++
        }
    }

    fun lost(network: K) {
        if (networks.containsKey(network)) {
            networks.remove(network)
            revision++
        }
    }

    fun clear() {
        networks.clear()
        revision++
    }

    fun isCurrent(network: K, capturedRevision: Long): Boolean =
        network == selectedNetwork && capturedRevision == revision
}

fun WifiServiceStatus.title(): String = when (this) {
    WifiServiceStatus.DISCONNECTED -> "Not connected to Wi-Fi"
    WifiServiceStatus.CHECKING -> "Wi-Fi connected · Checking internet"
    WifiServiceStatus.WAITING_FOR_INTERNET -> "Wi-Fi connected · Internet not confirmed yet"
    WifiServiceStatus.SIGN_IN_REQUIRED -> "Wi-Fi connected · Sign-in required"
    WifiServiceStatus.ONLINE -> "Wi-Fi connected · Internet available"
    WifiServiceStatus.MULTIPLE_NETWORKS -> "Multiple Wi-Fi connections detected"
    WifiServiceStatus.UNAVAILABLE -> "Connection status unavailable"
}

fun WifiServiceStatus.detail(): String = when (this) {
    WifiServiceStatus.DISCONNECTED -> "Join the aircraft Wi-Fi in Wi-Fi settings. Finding its name nearby does not mean you are connected."
    WifiServiceStatus.CHECKING -> "Waiting for Android's internet check on this Wi-Fi connection."
    WifiServiceStatus.WAITING_FOR_INTERNET -> "The Wi-Fi connection is up, but Android has not confirmed internet access. Service may not have started, sign-in may be needed, or connectivity may be interrupted."
    WifiServiceStatus.SIGN_IN_REQUIRED -> "Android detected a sign-in portal. Open the airline portal to check access; internet service is not confirmed yet."
    WifiServiceStatus.ONLINE -> "Android has validated internet access over this Wi-Fi connection. Individual sites may still be restricted."
    WifiServiceStatus.MULTIPLE_NETWORKS -> "FlightConnect cannot identify which connection is the aircraft Wi-Fi. Check Wi-Fi settings before testing internet access."
    WifiServiceStatus.UNAVAILABLE -> "FlightConnect could not observe network changes. Use Wi-Fi settings to check the connection."
}
