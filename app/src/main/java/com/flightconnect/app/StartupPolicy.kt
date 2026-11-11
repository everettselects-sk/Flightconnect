package com.flightconnect.app

/** Pure startup ordering, kept separate from Android permission and settings launchers. */
internal data class StartupState(
    val resumed: Boolean,
    val interactionInProgress: Boolean,
    val preciseLocationGranted: Boolean,
    val deviceLocationEnabled: Boolean,
    val locationAttempted: Boolean,
    val locationRequestedBefore: Boolean,
    val showLocationRationale: Boolean,
    val locationSettingsOffered: Boolean,
    val discoveryStarted: Boolean,
    val notificationsNeeded: Boolean,
)

internal enum class StartupAction {
    WAIT, REQUEST_LOCATION, SHOW_APP_SETTINGS, SHOW_LOCATION_SETTINGS,
    START_DISCOVERY, REQUEST_NOTIFICATIONS, DONE,
}

internal fun nextStartupAction(state: StartupState): StartupAction = with(state) {
    when {
        !resumed || interactionInProgress -> StartupAction.WAIT
        !preciseLocationGranted && !locationAttempted ->
            if (locationRequestedBefore && !showLocationRationale) StartupAction.SHOW_APP_SETTINGS
            else StartupAction.REQUEST_LOCATION
        preciseLocationGranted && !deviceLocationEnabled && !locationSettingsOffered ->
            StartupAction.SHOW_LOCATION_SETTINGS
        preciseLocationGranted && deviceLocationEnabled && !discoveryStarted ->
            StartupAction.START_DISCOVERY
        notificationsNeeded -> StartupAction.REQUEST_NOTIFICATIONS
        else -> StartupAction.DONE
    }
}
