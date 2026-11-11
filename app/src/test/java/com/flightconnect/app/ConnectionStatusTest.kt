package com.flightconnect.app

import org.junit.Assert.*
import org.junit.Test

class ConnectionStatusTest {
    private val waiting = WifiObservation(false, false)
    private val portal = WifiObservation(false, true)
    private val online = WifiObservation(true, false)

    @Test fun `no Wi-Fi is disconnected regardless of cellular internet`() {
        assertEquals(WifiServiceStatus.DISCONNECTED, wifiServiceStatus(emptyList()))
    }
    @Test fun `available before capabilities is checking not online`() {
        assertEquals(WifiServiceStatus.CHECKING, wifiServiceStatus(listOf(null)))
    }
    @Test fun `connected but unvalidated means internet not confirmed`() {
        assertEquals(WifiServiceStatus.WAITING_FOR_INTERNET, wifiServiceStatus(listOf(waiting)))
    }
    @Test fun `portal is separate from unavailable service`() {
        assertEquals(WifiServiceStatus.SIGN_IN_REQUIRED, wifiServiceStatus(listOf(portal)))
    }
    @Test fun `validated Wi-Fi shows internet available`() {
        assertEquals(WifiServiceStatus.ONLINE, wifiServiceStatus(listOf(online)))
    }
    @Test fun `contradictory transition flags never report working internet`() {
        assertEquals(WifiServiceStatus.SIGN_IN_REQUIRED, wifiServiceStatus(listOf(WifiObservation(true, true))))
    }
    @Test fun `multiple Wi-Fi connections do not choose an online one arbitrarily`() {
        assertEquals(WifiServiceStatus.MULTIPLE_NETWORKS, wifiServiceStatus(listOf(waiting, online)))
    }
    @Test fun `waiting service can become available without reconnecting`() {
        val tracker = WifiConnectionTracker<String>()
        tracker.available("aircraft")
        assertEquals(WifiServiceStatus.CHECKING, tracker.status)
        tracker.capabilities("aircraft", waiting)
        assertEquals(WifiServiceStatus.WAITING_FOR_INTERNET, tracker.status)
        tracker.capabilities("aircraft", portal)
        assertEquals(WifiServiceStatus.SIGN_IN_REQUIRED, tracker.status)
        tracker.capabilities("aircraft", online)
        assertEquals(WifiServiceStatus.ONLINE, tracker.status)
        tracker.capabilities("aircraft", waiting)
        assertEquals(WifiServiceStatus.WAITING_FOR_INTERNET, tracker.status)
    }
    @Test fun `old lost callback does not clear replacement connection`() {
        val tracker = WifiConnectionTracker<String>()
        tracker.capabilities("old", waiting)
        tracker.capabilities("new", online)
        tracker.lost("old")
        assertEquals("new", tracker.selectedNetwork)
        assertEquals(WifiServiceStatus.ONLINE, tracker.status)
        tracker.lost("old")
        assertEquals(WifiServiceStatus.ONLINE, tracker.status)
    }
    @Test fun `probe result invalidated by disconnection or replacement`() {
        val tracker = WifiConnectionTracker<String>()
        tracker.capabilities("old", waiting)
        val revision = tracker.revision
        assertTrue(tracker.isCurrent("old", revision))
        tracker.lost("old")
        tracker.capabilities("new", online)
        assertFalse(tracker.isCurrent("old", revision))
    }
    @Test fun `probe result invalidated by service change while same SSID connected`() {
        val tracker = WifiConnectionTracker<String>()
        tracker.capabilities("aircraft", waiting)
        val revision = tracker.revision
        tracker.capabilities("aircraft", portal)
        assertFalse(tracker.isCurrent("aircraft", revision))
    }
    @Test fun `unchanged capabilities do not invalidate a probe`() {
        val tracker = WifiConnectionTracker<String>()
        tracker.capabilities("aircraft", waiting)
        val revision = tracker.revision
        tracker.capabilities("aircraft", waiting)
        assertTrue(tracker.isCurrent("aircraft", revision))
    }
    @Test fun `stopping and resuming discards old results and identity`() {
        val tracker = WifiConnectionTracker<String>()
        tracker.capabilities("aircraft", online.copy(ssid = "DeltaWiFi.com"))
        val revision = tracker.revision
        tracker.clear()
        assertNull(tracker.selectedSsid)
        tracker.capabilities("aircraft", online)
        assertFalse(tracker.isCurrent("aircraft", revision))
    }
    @Test fun `multiple Wi-Fi connections do not expose a misleading SSID`() {
        val tracker = WifiConnectionTracker<String>()
        tracker.capabilities("one", waiting.copy(ssid = "AlaskaWiFi.com"))
        tracker.capabilities("two", online.copy(ssid = "Home"))
        assertNull(tracker.selectedNetwork)
        assertNull(tracker.selectedSsid)
    }
}
