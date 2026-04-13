## 4. Required Device Permissions & OS Interactions
To achieve this behavior in Android 15, the app will request the following from the user upon first launch:
1.  **Precise Location:** For accurate GPS plotting on the map (`ACCESS_FINE_LOCATION`). Allow "All the Time" or "While in Use" based on foreground service mapping.
2.  **Notification Permission:** Android 13+ requires explicit permission to post the Foreground Service indicator (`POST_NOTIFICATIONS`).
3.  **Radio/Network States (NetMonster):** To efficiently and reliably read network types (5G/LTE), Cell IDs, frequency bands, and signal strengths, the app relies on the **NetMonster Core** library instead of the standard Android Telephony API (which is often inconsistent across device manufacturers). Still requires `READ_PHONE_STATE` and `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION` permissions.
4.  **Disable Battery Optimization:** The app will optimally prompt the user to exempt it from Android's "App Standby Buckets" to ensure the network I/O loop is never throttled (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

### Third-Party Service Requirements
- **Google Maps API Key:** In order to render the map for GPS plotting, a valid Google Maps API Key must be embedded in the app at build time (e.g., in `local.properties` or Android Manifest).
