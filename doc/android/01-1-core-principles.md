## 1. Core Principles
*   **Exclusive to Android 15:** We will aggressively use modern API 35 features (like modern Foreground Service types, predictive back gestures, and granular location permissions) without touching legacy compat libraries.
*   **Uninterrupted Execution:** Network latency/loss measurements are extremely sensitive to OS battery optimizations. The app will utilize absolute wake-locking and foreground persistence to guarantee the CPU and modem never cycle down during a test.
*   **Data Integrity First:** Even if the UI crashes, the underlying measurement session must survive.

