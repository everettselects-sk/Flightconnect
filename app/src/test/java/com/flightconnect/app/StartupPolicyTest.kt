package com.flightconnect.app

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPolicyTest {
    private val fresh = StartupState(
        resumed = true,
        interactionInProgress = false,
        preciseLocationGranted = false,
        deviceLocationEnabled = true,
        locationAttempted = false,
        locationRequestedBefore = false,
        showLocationRationale = false,
        locationSettingsOffered = false,
        discoveryStarted = false,
        notificationsNeeded = true,
    )

    @Test fun `fresh install requests location before notifications`() {
        assertEquals(StartupAction.REQUEST_LOCATION, nextStartupAction(fresh))
    }

    @Test fun `no prompts launch before activity is resumed`() {
        assertEquals(StartupAction.WAIT, nextStartupAction(fresh.copy(resumed = false)))
    }

    @Test fun `permission dialog or settings in flight prevents a second launch`() {
        assertEquals(StartupAction.WAIT, nextStartupAction(fresh.copy(interactionInProgress = true)))
    }

    @Test fun `first denial with rationale can be retried`() {
        assertEquals(StartupAction.REQUEST_LOCATION, nextStartupAction(fresh.copy(
            locationRequestedBefore = true, showLocationRationale = true,
        )))
    }

    @Test fun `repeated denial routes to app settings`() {
        assertEquals(StartupAction.SHOW_APP_SETTINGS, nextStartupAction(fresh.copy(
            locationRequestedBefore = true, showLocationRationale = false,
        )))
    }

    @Test fun `denial never automatically repeats location request in same session`() {
        assertEquals(StartupAction.REQUEST_NOTIFICATIONS, nextStartupAction(fresh.copy(locationAttempted = true)))
    }

    @Test fun `approximate location alone cannot start discovery`() {
        assertEquals(StartupAction.DONE, nextStartupAction(fresh.copy(
            preciseLocationGranted = false, locationAttempted = true, notificationsNeeded = false,
        )))
    }

    @Test fun `precise grant with device Location off prompts to enable it`() {
        assertEquals(StartupAction.SHOW_LOCATION_SETTINGS, nextStartupAction(fresh.copy(
            preciseLocationGranted = true, deviceLocationEnabled = false,
        )))
    }

    @Test fun `return from Location settings unchanged does not reopen settings`() {
        assertEquals(StartupAction.DONE, nextStartupAction(fresh.copy(
            preciseLocationGranted = true, deviceLocationEnabled = false,
            locationSettingsOffered = true, notificationsNeeded = false,
        )))
    }

    @Test fun `return from app settings unchanged does not repeat prompt`() {
        assertEquals(StartupAction.DONE, nextStartupAction(fresh.copy(
            locationAttempted = true, locationRequestedBefore = true, notificationsNeeded = false,
        )))
    }

    @Test fun `granting precise location automatically starts discovery`() {
        assertEquals(StartupAction.START_DISCOVERY, nextStartupAction(fresh.copy(
            preciseLocationGranted = true, locationAttempted = true,
        )))
    }

    @Test fun `enabling device Location automatically starts discovery`() {
        assertEquals(StartupAction.START_DISCOVERY, nextStartupAction(fresh.copy(
            preciseLocationGranted = true, locationSettingsOffered = true,
        )))
    }

    @Test fun `notification approval is not required for discovery`() {
        assertEquals(StartupAction.START_DISCOVERY, nextStartupAction(fresh.copy(
            preciseLocationGranted = true, notificationsNeeded = false,
        )))
    }

    @Test fun `notifications follow discovery rather than overlap location prompt`() {
        assertEquals(StartupAction.REQUEST_NOTIFICATIONS, nextStartupAction(fresh.copy(
            preciseLocationGranted = true, discoveryStarted = true,
        )))
    }

    @Test fun `completed setup is idempotent`() {
        assertEquals(StartupAction.DONE, nextStartupAction(fresh.copy(
            preciseLocationGranted = true, discoveryStarted = true, notificationsNeeded = false,
        )))
    }

    @Test fun `Location switch off never starts discovery even after dismissal`() {
        assertEquals(StartupAction.REQUEST_NOTIFICATIONS, nextStartupAction(fresh.copy(
            preciseLocationGranted = true, deviceLocationEnabled = false, locationSettingsOffered = true,
        )))
    }
}
