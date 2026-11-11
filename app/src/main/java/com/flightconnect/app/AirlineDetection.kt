package com.flightconnect.app

import java.util.Locale

data class AirlinePortal(val name: String, val url: String)

fun normalizedSsid(raw: String?): String? = raw?.trim()?.removeSurrounding("\"")
    ?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }

/** Exact known names only. Never treat an SSID as a URL or a proof of network authenticity. */
fun detectAirline(ssid: String?): AirlinePortal? = when (normalizedSsid(ssid)?.lowercase(Locale.ROOT)) {
    "alaskawifi.com", "free wifi at alaskawifi.com" -> AirlinePortal("Alaska", "https://alaskawifi.com")
    "deltawifi.com" -> AirlinePortal("Delta", "https://deltawifi.com")
    "aainflight.com", "aa-inflight" -> AirlinePortal("American", "https://aainflight.com")
    "southwestwifi" -> AirlinePortal("Southwest", "https://southwestwifi.com")
    else -> null
}

fun isAircraftWifiCandidate(ssid: String): Boolean = detectAirline(ssid) != null ||
    normalizedSsid(ssid)?.lowercase(Locale.ROOT) in setOf("gogoinflight", "wifi-onboard")

enum class ConnectionActionKind { CONNECT, OPEN_PORTAL, WIFI_SETTINGS }

data class ConnectionAction(
    val kind: ConnectionActionKind,
    val label: String,
    val ssid: String? = null,
    val portal: AirlinePortal? = null,
)

fun connectionAction(
    status: WifiServiceStatus,
    connectedSsid: String?,
    nearbySsids: List<String>,
    notificationSsid: String? = null,
): ConnectionAction {
    val connected = status in setOf(WifiServiceStatus.CHECKING, WifiServiceStatus.WAITING_FOR_INTERNET,
        WifiServiceStatus.SIGN_IN_REQUIRED, WifiServiceStatus.ONLINE)
    if (connected) {
        val portal = detectAirline(connectedSsid)
        if (portal != null) return ConnectionAction(ConnectionActionKind.OPEN_PORTAL,
            "Open ${portal.name} Wi-Fi site", portal = portal)
        // Public Android APIs do not let third-party apps launch the system's captive portal app.
        return ConnectionAction(ConnectionActionKind.WIFI_SETTINGS,
            if (status == WifiServiceStatus.SIGN_IN_REQUIRED) "Open Wi-Fi sign-in settings" else "Open Wi-Fi settings")
    }
    if (status == WifiServiceStatus.DISCONNECTED) {
        val candidates = nearbySsids.filter(::isAircraftWifiCandidate).distinct()
        // Honor a tapped notification only while that exact network is still in the current scan.
        val target = notificationSsid?.takeIf { it in candidates } ?: candidates.singleOrNull()
        if (target != null) return ConnectionAction(ConnectionActionKind.CONNECT,
            "Connect to ${detectAirline(target)?.name ?: target} Wi-Fi", ssid = target)
    }
    return ConnectionAction(ConnectionActionKind.WIFI_SETTINGS, "Open Wi-Fi settings")
}
