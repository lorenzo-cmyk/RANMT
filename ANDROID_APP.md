# RANMT Android App: Product Specification

## 1. Core Principles
*   **Exclusive to Android 15:** We will aggressively use modern API 35 features (like modern Foreground Service types, predictive back gestures, and granular location permissions) without touching legacy compat libraries.
*   **Uninterrupted Execution:** Network latency/loss measurements are extremely sensitive to OS battery optimizations. The app will utilize absolute wake-locking and foreground persistence to guarantee the CPU and modem never cycle down during a test.
*   **Data Integrity First:** Even if the UI crashes, the underlying measurement session must survive.

## 2. App Navigation & Structure
The app will use a simple, bottom-navigation or tabbed layout with two primary destinations:
1.  **History (Dashboard):** The default landing screen.
2.  **New Measurement:** The configuration and launch screen for a new drive test.

## 3. Feature Breakdown

### A. History (Measurements List)
*   **Behavior:** When the app opens, the user sees a chronological list of all historically recorded sessions stored on the device.
*   **List Item Content:** 
    *   Date & Time of the test.
    *   Short UUID representation (e.g., `Session: ...40400`).
    *   Duration of the test (e.g., `12m 45s`).
    *   High-level summary metrics (e.g., Average Jitter, Total Packet Loss %, primary RAT like "5G SA").
*   **Interactions:** 
    *   **Tap:** Opens the **Session Details** view.
    *   **Swipe/Long Press:** Option to delete the session from the device.

### B. Session Details & Map View (Post-Measurement)
When a user opens a specific past session, they are presented with a scrollable view split into two major sections:
*   **1. Overall Metrics Panel:** Displays aggregate statistics calculated over the whole session (e.g., Max/Min/Avg RSRP, total connection drops, total bytes sent/received, peak relative jitter).
*   **2. Interactive Map (The Route):** A full-width map component showing the specific route driven during the test based on the telemetry `lat`/`lon`.
    *   **Color-Coded Pathing:** The route line is visually segmented and color-coded based on network conditions (e.g., Green = 0% loss, Yellow = Jitter spikes, Red = Outage/Loss of Signal).
    *   **Data-Point Inspection:** Tapping anywhere on the colored route drops a pin and reveals a bottom sheet showing the exact radio metrics at that timestamp (Cell ID, PCI, EARFCN, RSRP, RSRQ, SINR, packet loss).

### C. Data Export
*   **Behavior:** Inside the Session Details view, there is a prominent "Export" button in the Top App Bar.
*   **User Flow:** 
    *   Tapping the button packages the raw session data (likely formatting the telemetry and server stats into a unified CSV or JSONL file).
    *   It triggers the native Android 15 **Share Sheet**.
    *   The user can then seamlessly push the file to Google Drive, email it, send it via Slack, or save it to local Android downloads.

### D. Starting a New Measurement (The Active Test)
*   **Behavior:** Tapping the "Start Measurement" tab brings up a configuration form.
*   **Pre-test Config:** Target Server IPv4 Address & Port, Direction (Uplink/Downlink), Target Bitrate (bps).
*   **The "Running" State:**
    *   Upon pressing **"Start"**, the app transitions to a "Live Dashboard". 
    *   **Anti-Sleep Guarantee:** The screen brightness is locked on (Screen Wakelock) so the tester can glance at it while driving. The app will declare a native Android 15 Foreground Service for `location` and `dataSync`, pinning a persistent notification in the status bar. If the user accidentally locks the screen, the test continues flawlessly in the background without the CPU dozing off.
    *   **Live UI:** Displays a large digital timer, visual indicators of current connection state (Connected, Buffer-Recording for Tunnels, Reconnecting), and live spark-line graphs of RSRP and RTT/Jitter.
    *   **End Test:** A bold, swipe-to-stop or long-press-to-stop button ensures the user doesn't accidentally terminate the test over a bump in the road.

## 4. Required Device Permissions & OS Interactions
To achieve this behavior in Android 15, the app will request the following from the user upon first launch:
1.  **Precise Location:** For accurate GPS plotting on the map (`ACCESS_FINE_LOCATION`). Allow "All the Time" or "While in Use" based on foreground service mapping.
2.  **Notification Permission:** Android 13+ requires explicit permission to post the Foreground Service indicator (`POST_NOTIFICATIONS`).
3.  **Radio/Telephony States:** To natively read network types (5G/LTE), Cell IDs, frequency bands (EARFCN), and signal strengths directly from the device's modem (`READ_PHONE_STATE` and `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION`).
4.  **Disable Battery Optimization:** The app will optimally prompt the user to exempt it from Android's "App Standby Buckets" to ensure the network I/O loop is never throttled (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

---

## 5. Rust Client Integration (UniFFI)

The Android app calls the Rust QUIC client via UniFFI-generated Kotlin bindings and a native `.so`.

### 5.1 Build the native library

Use `cargo-ndk` to build the Rust `cdylib` for the desired Android ABI:

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk -t arm64-v8a -o android_client/app/src/main/jniLibs build -p ranmt-client --features ffi --release
```

This writes `libranmt_client.so` into:

```
android_client/app/src/main/jniLibs/arm64-v8a/
```

Optional emulator build:

```bash
cargo ndk -t x86_64 -o android_client/app/src/main/jniLibs build -p ranmt-client --features ffi --release
```

### 5.2 Generate Kotlin bindings

Generate Kotlin sources from the built library:

```bash
cargo install uniffi-bindgen
uniffi-bindgen generate \
    --library target/aarch64-linux-android/release/libranmt_client.so \
    --language kotlin \
    --out-dir android_client/app/src/main/java
```

This creates `uniffi/ranmt_client/*` and is loaded by the wrapper at:

```
android_client/app/src/main/java/dev/ranmt/rust/RustClient.kt
```

### 5.3 Android runtime dependency

The generated Kotlin bindings use JNA. The Android app includes the AAR so
`libjnidispatch.so` is packaged:

```
implementation("net.java.dev.jna:jna:5.14.0@aar")
```
