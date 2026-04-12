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
    *   **Live UI:** Displays a large digital timer, visual indicators of current connection state (Connected, Reconnecting), and live spark-line graphs of RSRP and RTT/Jitter.
    *   **End Test:** A bold, swipe-to-stop or long-press-to-stop button ensures the user doesn't accidentally terminate the test over a bump in the road.

### E. Settings & Configuration
*   **Location and Filtering:**
    *   **Vehicle Profile:** Allows the user to select the type of vehicle used for the measurement (Train, Car, Walking, Generic). This tunes a mathematical Kalman Filter that smooths out raw GPS coordinates and limits physically impossible sudden changes in velocity caused by multi-path interference or weak GPS signals.
    *   **Sampling Interval & Accuracy:** Lets the user tune how often location and radio metrics are queried and recorded.
*   **Export Preferences:** Configures default file formats (CSV or JSONL) and whether metadata metrics should be appended into CSV headers.
