# Bug & Inconsistency Report — RANMT

Codebase reviewed: Rust core (`shared`, `client`, `server`) + Kotlin Android app.

---

## Bugs

### 1. Kalman filter Doppler speed fusion has incorrect state update

**File:** `kotlin/.../service/GpsKalmanFilter.kt:261-262`

The `fuseDopplerSpeed` method multiplies the state update by the heading components (`hx`, `hy`), which are already accounted for in the Kalman gain (`k2`, `k3`). The standard Kalman update is `x += K * innov`:

```kotlin
// Current (wrong):
vx += k2 * innov * hx
vy += k3 * innov * hy

// Should be:
vx += k2 * innov
vy += k3 * innov
```

The covariance update on lines 265-268 is correct — it does NOT have the extra `* hx` / `* hy`. This mismatch between state update and covariance update means the filter's internal consistency is broken. The practical effect is that Doppler speed corrections are under-applied (scaled by ~0.7 when heading northeast), making the filter slower to converge on the true speed.

### 2. Loss percentage uses wrong denominator for Uplink tests

**File:** `kotlin/.../service/MeasurementService.kt:241-262`

The loss computation uses `transport?.rxPackets` as the denominator. `rxPackets` in `TransportStats` maps to the server's `txPackets` (packets the server sent = what the client received). This is the correct denominator for **Downlink** tests, where the server sends traffic. But for **Uplink** tests, the client sends traffic, and the relevant "sent" count is `txPackets` (server received = client sent).

```kotlin
// Current (always uses rxPackets regardless of direction):
val currentTotalSent = transport?.rxPackets ?: 0L

// Should be direction-aware:
val currentTotalSent = if (config.direction == "Uplink")
    transport?.txPackets ?: 0L   // client sent = server received
else
    transport?.rxPackets ?: 0L   // client received = server sent
```

The variable name `currentTotalSent` is also misleading since it reads `rxPackets`.

### 3. "Lost Packets" UI label shows a percentage

**File:** `kotlin/.../ui/screens/RunningScreen.kt:140`

The transport section labels the value as `"Lost Packets"` but displays `lossPct` formatted as a percentage (e.g., `"2.50 %"`). The label should be `"Packet Loss"` or `"Loss %"`.

### 4. Network type strings from NetMonster don't survive FFI round-trip

**File:** `rust/client/src/ffi.rs:128-134`

The FFI `network_type` conversion matches exact lowercase strings `"5g"`, `"lte"`, `"3g"`, `"2g"`. But NetMonster produces granular strings like `"5G-NSA"`, `"5G-SA"`, `"LTE-CA"`, `"HSPA/UMTS"`, `"GSM/EDGE"`. After `to_lowercase()`, `"5g-nsa"` doesn't match `"5g"`, `"lte-ca"` doesn't match `"lte"`, etc. These all become `NetworkType::Unknown`, so the server records `network_type: "unknown"` for most real-world connections.

The fix would use `startsWith` semantics (e.g., strings starting with "5g" → FiveG, "lte" → Lte) or add explicit matches for the sub-variants.

### 5. Race condition in `stopRustClient` — leaked handle

**File:** `kotlin/.../service/MeasurementService.kt:344-356`

`rustHandle` is set to `null` synchronously while `RustClient.stop(handle)` is launched asynchronously on `Dispatchers.IO`. If `startRustClient`'s coroutine is mid-flight and completes after `rustHandle` is nulled, the new handle is written to `rustHandle` but the stop coroutine is also running. The `rustStartJob?.cancel()` is cooperative and may not take effect if `RustClient.start()` doesn't check for cancellation.

Sequence:
1. `startRustClient` launches coroutine, calls `RustClient.start()` (suspend)
2. `stopRustClient` cancels `rustStartJob`, sets `rustHandle = null`, launches stop on old handle (null → no-op)
3. `RustClient.start()` completes, coroutine sets `rustHandle = newHandle`, starts polling
4. Leaked handle: stop was never called on `newHandle`

### 6. `cell_id` string→integer conversion silently returns 0 for non-numeric values

**File:** `rust/client/src/ffi.rs:135`

The Kotlin side produces `cellId` strings from NetMonster that may be non-numeric (e.g., `"12345"` for LTE ECI but also potentially formatted strings). The FFI conversion `value.cell_id.parse().unwrap_or(0)` returns 0 for any non-numeric string, and the server records `cell_id: 0`. While most cell IDs are numeric, any non-numeric cell identifier is silently lost.

---

## Inconsistencies

### 7. `ConnectionState.Buffering` is defined but never set

**Files:** `kotlin/.../data/Models.kt:73-77`, `kotlin/.../service/MeasurementService.kt:359-366`

The `ConnectionState` enum has three values: `Connected`, `Buffering`, `Reconnecting`. The UI in `RunningScreen.kt` handles all three. However, `mapConnectionState` maps `RustConnectionState.Disconnected` → `ConnectionState.Reconnecting`, and `Buffering` is never assigned anywhere. The `Buffering` state appears to be intended for tunnel scenarios but is dead code.

### 8. `MainViewModel.connectionState` is dead code

**File:** `kotlin/.../ui/MainViewModel.kt:47,112,132-134`

`connectionState` is a Compose mutable state that's set in `startMeasurement()` and `updateConnectionState()`, but it's never read by any UI composable. The `RunningScreen` reads connection state from `RunningSessionState.state.connectionState` instead. The ViewModel field serves no purpose.

### 9. `normalizeServer` is duplicated and one copy is dead code

**Files:** `kotlin/.../service/MeasurementService.kt:368-380`, `kotlin/.../rust/RustClient.kt:157-170`

The same "parse host:port from a string" logic is implemented in both `MeasurementService` and `RustClient`. The `MeasurementService` version is never called — `startRustClient` passes the raw config to `RustClient.start()`, which uses its own `normalizeServer`.

### 10. `SessionPrefs` defines unused keys

**File:** `kotlin/.../service/SessionPrefs.kt:68-69`

`KEY_BYTES_SENT` and `KEY_BYTES_RECEIVED` are declared but never read or written.

### 11. `session_id` parsing silently generates a new UUID on failure

**File:** `rust/client/src/lib.rs:250-254`

```rust
let session_id = config.session_id
    .as_ref()
    .and_then(|id| core::str::FromStr::from_str(id).ok())
    .unwrap_or_else(|| uuid::Uuid::new_v4());
```

If the `session_id` string can't be parsed as a UUID (corruption, typo, format change), the code silently creates a new random UUID. The server will then create a brand-new session instead of reconnecting, with no warning logged. The client logs the generated session ID but doesn't indicate it was a fallback.

### 12. Server doesn't persist UL datagram data to JSONL

**File:** `rust/server/src/main.rs:479-505`

When the server receives UL traffic datagrams, `process_datagrams` only logs the sequence number to tracing. Individual datagram data (seq, send timestamp) is NOT written to the session's JSONL file. For DL tests, traffic statistics are indirectly captured via periodic `ServerStats`. For UL tests, there is no per-datagram recording at all. The server has no way to reconstruct UL traffic patterns from the JSONL.

### 13. Client discards DL datagram send timestamps

**File:** `rust/client/src/lib.rs:467`

```rust
if let Some((seq, _send_ts)) = decode_traffic_payload(&dgram_buf[..n]) {
```

The server embeds its send timestamp in each DL datagram, and the client decodes it but ignores it (`_send_ts`). Per-datagram one-way latency could be computed from this field, but instead only aggregate QUIC RTT stats are used.

### 14. `connectionDrops` name is misleading

**Files:** `kotlin/.../service/MeasurementService.kt:593-601`, `kotlin/.../data/SessionMetrics.kt:17`

`connectionDrops` in `MetricsAccumulator` is incremented when `point.lossPct > 20.0`, counting the number of telemetry samples with high loss — NOT the number of actual connection drops. The UI labels this as `"Spikes (>20%)"` in `SessionDetailScreen` (which is accurate) but as `"connectionDrops"` in the data model and `SessionMetrics` (which is misleading). This creates confusion when reading the code vs. the UI.

### 15. Server `client_version` field is set but never used for logic

**File:** `rust/shared/src/lib.rs:286`, `rust/server/src/main.rs:409`

`SessionState.client_version` is set during handshake and written to JSONL via `write_handshake`, but it's never read or used for any server-side logic (e.g., version compatibility checks). The `validate_handshake` function checks the format but not the content of `client_version`.

### 16. Server `process_datagrams` takes an unused `&mut SessionState` parameter

**File:** `rust/server/src/main.rs:479`

```rust
fn process_datagrams(scid: &[u8; 16], entry: &mut ActiveConn, _session: &mut SessionState)
```

The `_session` parameter is passed in but never used (underscore-prefixed). The caller goes through the trouble of getting a mutable reference to the session for no reason.

---

## Minor / Cosmetic

### 17. `HandshakeStatus` has a manual `PartialEq` impl that's equivalent to derive

**File:** `rust/shared/src/lib.rs:66-70`

```rust
impl PartialEq for HandshakeStatus {
    fn eq(&self, other: &Self) -> bool {
        core::mem::discriminant(self) == core::mem::discriminant(other)
    }
}
```

For a fieldless enum, `#[derive(PartialEq)]` produces the same behavior. The manual impl is redundant.

### 18. CLI `client.rs` doesn't pass `session_id` to `ClientConfig`

**File:** `rust/client/src/bin/client.rs:57-67`

The standalone CLI binary hardcodes `session_id: None`, so every run generates a new UUID. There's no `--session-id` flag to support reconnection from the CLI. This is likely intentional but inconsistent with the FFI path where `session_id` can be provided.

### 19. `SessionPrefs.clearActive()` uses `.clear().apply()` which wipes all keys

**File:** `kotlin/.../service/SessionPrefs.kt:28`

`clearActive()` calls `prefs.edit().clear().apply()`, which removes every key in the SharedPreferences file. This is fine currently (all keys are session-related), but if any non-session key were added later, it would be unexpectedly cleared.
