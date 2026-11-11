# Automatic Wi-Fi status and portal detection

## Scope

Beta 0.9 adds a passive connection-status card and one contextual Connect/Open Wi-Fi site action. Nearby scan results do not prove a connection or internet service. Only capabilities of the connected physical Wi-Fi network determine the displayed internet state. Cellular and VPN default routes are shown separately.

Supported exact airline SSIDs are Alaska (`Alaskawifi.com`, `Free WiFi at Alaskawifi.com`), Delta (`DeltaWiFi.com`), American (`aainflight.com`, `AA-Inflight`), and Southwest (`SouthwestWiFi`). Shared-provider names do not identify an airline. Unknown, hidden, or ambiguous names fall back to Wi-Fi settings (including the system sign-in controls). SSIDs are hints, not authenticated identities; no URL is constructed from an SSID.

The existing high-importance discovery notification, channel ID, permission flow, content intent, and Connect action remain. Copy now says a network was found rather than implying internet service is ready. Android still controls connection approval and portal launch.

This does not add background scanning, a foreground service, airline-app integration, automatic login, purchase, or radio/network resets. Discovery and the live card run while FlightConnect is visible. An existing posted notification can remain visible after leaving the app; sustained discovery while another app is open is not claimed.

## Automated checks

Run the repository's aggregate checks:

    gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

The new policy tests cover missing Wi-Fi, capability arrival, waiting/portal/online transitions without reconnecting, validation loss, old-network callback ordering, late manual-test results, lifecycle resets, ambiguous concurrent Wi-Fi networks, exact airline aliases, lookalike rejection, current-network precedence, shared providers, and stale notification targets. Existing startup-permission tests remain unchanged.

## Device checks still required

1. With airplane mode on, Wi-Fi enabled but not joined, open the app. It must show disconnected, discover the aircraft network, post the existing notification, and offer one Connect button.
2. Tap notification body and Connect action both with the activity open and after leaving it. Verify current scan revalidation, connection approval, and the successful portal path without an airline picker. Confirm high-importance pop-up behavior subject to the phone's notification settings and DND.
3. Join aircraft Wi-Fi before service is available. It must show connected with internet unconfirmed, not online or disconnected. Turn service on without reconnecting and verify the status updates.
4. With mobile data available as the default route, leave Wi-Fi unvalidated. The card must stay unconfirmed. A manual test must use the Wi-Fi Network rather than mobile data.
5. When a captive portal is detected, a known SSID must offer the matching airline site. An unknown SSID must open Wi-Fi settings for the system sign-in control. After login, return and confirm validated Wi-Fi state. The public Android SDK does not provide direct launching of its network-bound captive-portal app.
6. Verify known-airline fallback browser portal links with mobile data both on and off. A normal browser uses its own default route, so this fallback cannot guarantee access to an onboard-only portal while mobile remains default.
7. Verify the exact Alaska name `Free WiFi at AlaskaWiFi.com`, a known Delta name, a generic provider, hidden SSID permission, and two visible airline networks. Never infer an airline from a different nearby network.
8. On API 26–28, Connect must open Wi-Fi settings. On API 29–30, verify the legacy Wi-Fi SSID read. On API 31+, verify the location-aware callback after granting/revoking precise location and toggling device Location.
9. Rotate, background/resume, leave for a portal, deny permissions, and reconnect during a manual check. No stale positive result, callback leak, repeated settings prompt, or crash is acceptable.
10. Check the main button and status on narrow and foldable screens, large text, and work-managed devices. Open/close the diagnostics section repeatedly.

## Primary references

- Android network state and callback behavior: https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- Android location-aware network callbacks: https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
- Wi-Fi-bound connections: https://developer.android.com/reference/android/net/Network
- Alaska network names and portal: https://www.alaskaair.com/content/travel-info/flight-experience/inflight-entertainment/wifi
- Delta network name and portal: https://www.delta.com/us/en/onboard/inflight-entertainment/onboard-wifi
- American network names: https://entertainment.aa.com/en/how-to-connect
- Southwest network and portal: https://mobile.southwest.com/inflight-entertainment-portal/
