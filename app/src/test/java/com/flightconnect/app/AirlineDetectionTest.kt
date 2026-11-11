package com.flightconnect.app

import org.junit.Assert.*
import org.junit.Test

class AirlineDetectionTest {
    private val alaska = "Free WiFi at AlaskaWiFi.com"
    @Test fun `field tested Alaska network maps automatically to official portal`() {
        assertEquals(AirlinePortal("Alaska", "https://alaskawifi.com"), detectAirline(alaska))
    }
    @Test fun `quoted Android SSID and case differences are handled`() {
        assertEquals("Alaska", detectAirline("\"ALASKAWIFI.COM\"")?.name)
        assertEquals("Delta", detectAirline("DeltaWiFi.com")?.name)
        assertEquals("American", detectAirline("AA-Inflight")?.name)
        assertEquals("American", detectAirline("aainflight.com")?.name)
        assertEquals("Southwest", detectAirline("SouthwestWiFi")?.name)
    }
    @Test fun `redacted missing or unrelated SSIDs never default to Alaska`() {
        listOf(null, "", "<unknown ssid>", "Home", "gogoinflight", "wifi-onboard").forEach {
            assertNull(detectAirline(it))
        }
    }
    @Test fun `substring and domain lookalikes cannot choose an airline portal`() {
        listOf("Delta WiFi Fast", "AlaskaWiFi.com.evil.test", "Free WiFi at AlaskaWiFi.com extra", "my-delta").forEach {
            assertNull(detectAirline(it))
        }
    }
    @Test fun `connected airline wins over nearby different airline`() {
        val action = connectionAction(WifiServiceStatus.ONLINE, "DeltaWiFi.com", listOf(alaska), alaska)
        assertEquals(ConnectionActionKind.OPEN_PORTAL, action.kind)
        assertEquals("Delta", action.portal?.name)
    }
    @Test fun `known captive network opens its detected airline site without a picker`() {
        val action = connectionAction(WifiServiceStatus.SIGN_IN_REQUIRED, alaska, emptyList())
        assertEquals(ConnectionActionKind.OPEN_PORTAL, action.kind)
        assertEquals("Open Alaska Wi-Fi site", action.label)
    }
    @Test fun `unrecognized captive network still offers sign-in without choosing airline`() {
        val action = connectionAction(WifiServiceStatus.SIGN_IN_REQUIRED, null, listOf(alaska))
        assertEquals(ConnectionActionKind.WIFI_SETTINGS, action.kind)
        assertNull(action.portal)
    }
    @Test fun `unknown connected network cannot inherit airline from nearby scan`() {
        val action = connectionAction(WifiServiceStatus.WAITING_FOR_INTERNET, "Home", listOf(alaska))
        assertEquals(ConnectionActionKind.WIFI_SETTINGS, action.kind)
    }
    @Test fun `single detected network produces one connect action`() {
        val action = connectionAction(WifiServiceStatus.DISCONNECTED, null, listOf(alaska))
        assertEquals(ConnectionActionKind.CONNECT, action.kind)
        assertEquals(alaska, action.ssid)
        assertEquals("Connect to Alaska Wi-Fi", action.label)
    }
    @Test fun `shared provider never implies Alaska`() {
        val action = connectionAction(WifiServiceStatus.DISCONNECTED, null, listOf("gogoinflight"))
        assertEquals(ConnectionActionKind.CONNECT, action.kind)
        assertNull(action.portal)
        assertFalse(action.label.contains("Alaska"))
    }
    @Test fun `ambiguous nearby networks ask Android instead of choosing first`() {
        assertEquals(ConnectionActionKind.WIFI_SETTINGS, connectionAction(
            WifiServiceStatus.DISCONNECTED, null, listOf(alaska, "DeltaWiFi.com")).kind)
    }
    @Test fun `tapped notification selects only its still present network`() {
        val action = connectionAction(WifiServiceStatus.DISCONNECTED, null,
            listOf(alaska, "DeltaWiFi.com"), alaska)
        assertEquals(alaska, action.ssid)
    }
    @Test fun `stale notification cannot force nonexistent or arbitrary network`() {
        val action = connectionAction(WifiServiceStatus.DISCONNECTED, null, emptyList(), alaska)
        assertEquals(ConnectionActionKind.WIFI_SETTINGS, action.kind)
    }
    @Test fun `multiple active Wi-Fi networks never expose guessed portal`() {
        assertEquals(ConnectionActionKind.WIFI_SETTINGS, connectionAction(
            WifiServiceStatus.MULTIPLE_NETWORKS, null, listOf(alaska)).kind)
    }
}
