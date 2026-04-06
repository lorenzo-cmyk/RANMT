# RANMT TODO

Based on comparing current implementation against `doc/protocol.md`,
`doc/datatypes.md`, `doc/architecture.md`, and `doc/implementation-details.md`.
Last checked: 2026-04-06

---

## 1. Shared crate (`shared/src/lib.rs`)

- [x] All wire protocol constants defined (ALPN, sizes, intervals, timeouts)
- [x] Structs: `Handshake`, `HandshakeAck`, `HandshakeStatus`, `Goodbye`, `GoodbyeReason`, `ServerStats`, `QuicStats`, `ClientTelemetry`, `NetworkType`
- [x] `WireMessage` enum with `#[serde(tag = "type")]`
- [x] `encode_traffic_payload` / `decode_traffic_payload` — binary wire format
- [x] `TrafficPacer` — sync payload generator
- [x] `LossJitterTracker` — loss + jitter EWMA
- [x] `SessionState` — lifecycle, dormant tracking, JSONL writer
- [x] `MockTelemetry` — deterministic sinusoidal osc
- [x] Utility: `current_epoch_ms()`, `resolve_ipv4()`, `calc_traffic_interval()`

---

## 2. Client (`client/src/lib.rs` + `client/src/bin/client.rs`)

- [ ] **Missing `openssl` / `openssl-sys` dependency** — spec calls for `conn.set_verify(SslVerifyMode::NONE)` for insecure mode via openssl. Current code uses `quic_cfg.verify_peer(false)` which may work — verify this is sufficient
- [ ] **`display_stats` incomplete** — protocol §5 says display RTT, TX, RX, CWND, lost, send_rate. Current code only logs TX, RX, lost. Need to add RTT, CWND, send_rate
- [ ] **UL datagram loss/jitter tracking on server** — when client sends UL datagrams, the server must track loss/jitter but has no CLI output for it. At minimum log it at INFO level (currently only DEBUG)
- [x] CLI args match spec (§4.2): server, direction, port, bitrate, duration, insecure, seed
- [x] Reconnection loop: 2s delay, session_id persistence
- [x] Telemetry generation at 1 Hz with VecDeque backlog
- [x] Store-and-forward: backlog drained after handshake
- [x] QUIC timeout handling with fallback
- [x] `flush_quic` after connection build
- [x] No `std::process::exit`, `std::thread`, or `uniffi` blockers

---

## 3. Server (`server/src/main.rs`)

- [ ] **Handshake validation missing** — protocol §3.1 says server must parse and validate all fields (direction, bitrate range 1k-1Mbps, client_version semver). Current code accepts any values without validation. Should reject invalid handshakes with `HandshakeAck { status: "error", message: "..." }`
- [ ] **Error handshake handling** — protocol §10 says validation fail should send error `HandshakeAck` then close stream. Not implemented
- [ ] **`QUIC v1` protocol version** — spec §1.1 requires QUIC v1 (RFC 9000). Code uses `quiche::PROTOCOL_VERSION` which is v1 — verify this matches
- [ ] **`Idle timeout unit`** — protocol §1.1 says 30 000 ms. `set_max_idle_timeout` in quiche expects microseconds. Spec code shows `config.set_max_idle_timeout(30_000_000)` (microseconds). Current code: `IDLE_TIMEOUT_MS * 1000` — but `IDLE_TIMEOUT_MS = 30_000`, so `30_000 * 1000 = 30_000_000` microseconds = 30s. This is correct but the constant name is misleading. Consider renaming to `IDLE_TIMEOUT_US` or fixing the multiplication.
- [ ] **`conn.stats()` RTT mapping** — `extract_quic_stats` returns `rtt_ms: 0.0` (hardcoded). Should use `s.rtt.as_secs_f64() * 1000.0`. Similarly `cwnd: 0` hardcoded. These come from `conn.stats()` — need to check which fields are available in quiche 0.22
- [ ] **`enable_dgram` parameters** — spec §1.1 and `implementation-details.md` §1 call for `config.enable_dgram(true, 1200, 1200)` matching `MAX_DGRAM_SIZE`. Current code uses `1024`. **This may cause datagrams > 1024 bytes to be silently dropped**, breaking the 1200-byte traffic payloads
- [ ] **`send_rate_bps` extraction** — always 0. Spec lists it as required field. quiche 0.22 may not expose this — if so, document as known limitation
- [ ] **Server `stats_interval` uses `MissedTickBehavior::Skip`** — fine for accept path, but `process_connection_periodic` reuses the same interval for periodic sending which may skip ticks during busy periods
- [ ] **`process_stream_messages` missing `Ok(_)` wildcard in match** — currently matches `Handshake`, `Goodbye`, `ClientTelemetry`, and `Err(_)`. WireMessage also has `ServerStats` and `HandshakeAck` which fall to the `_ => {}` arm (correct but undocumented)
- [ ] **No `goodbye` reason logging** — `_reason` pattern discards the reason value. Should log the actual reason variant

---

## 4. Protocol compliance gaps

- [ ] **Max datagram size mismatch** — `enable_dgram(true, 1024, 1024)` in both client and server, but `MAX_DGRAM_SIZE = 1200`. Datagram payloads are 1200 bytes but quiche is configured to only accept 1024 byte datagrams
- [ ] **No Handshake error path** — if a client sends a handshake with `bitrate_bps: 9999999` or invalid direction, server should respond with error `HandshakeAck`. Currently all handshakes are accepted
- [ ] **No `client_version` validation** on server — spec says semver check
- [ ] **Seq_num reset on reconnect** — client `MockTelemetry` is recreated each iteration of the outer reconnection loop, resetting `seq_num` to 0. Protocol §8.2 rule 4 says "seq_num is never reset, it continues incrementing from the last value"
- [ ] **Telemetry seq_num reset issue** — same as above; `mock_gen = MockTelemetry::new(test_start)` in the client resets seq_num
- [ ] **`Handshake` sent before `conn.is_established()` check** — client sends handshake when `!handshake_sent && conn.is_established()`. Per spec §3, client should send handshake "immediately after QUIC connection is established" — the `is_established()` check is correct but the handshake should be sent before any telemetry
- [ ] **UL traffic pacer check requires `handshake_done`** — in client, UL datagrams require `handshake_done`. This means the client won't send UL datagrams until it receives `HandshakeAck`. Per protocol flow, this is correct (client must wait for ack before starting traffic)
- [ ] **No Goodbye response handling on client** — client sends Goodbye with FIN but doesn't wait for server to acknowledge before closing. This is minor and expected for graceful shutdown
- [ ] **Telemetry backlog capacity** — spec says max 10 000 with FIFO overflow (oldest dropped). Current code silently doesn't push when full, which is a different behavior (newest dropped vs oldest). The spec says "Oldest entry is silently dropped (FIFO overflow)"

---

## 5. Infrastructure

- [ ] **`.gitignore`** — `target/`, `sessions/`, `.claude/` and dev cert temp files should be .gitignored
- [ ] **Server CLI port arg** — spec §4.1 says `--port <PORT>` is required with no default. Current code: `short = 'p', long` — has no default which is correct
- [ ] **`rcgen` version** — current Cargo says `rcgen = "0.13"`. `rcgen 0.13` API may change in the future to generate certs differently

---

## 6. Future (deferred)

- [ ] **UniFFI bindings** — client `lib.rs` structured for it (no `std::process::exit`, no threads), but actual `uniffi` build not done
- [ ] **Android / cdylib compilation**
- [ ] **Real modem integration** — currently mock telemetry only
- [ ] **Bidirectional traffic** — spec §3.1 rule 4 mentions future bidirectional tests
- [ ] **System CA trust store verification** — `--insecure` disables verification; proper production CA verification + `--cert-fingerprint` pinning not fully implemented
- [ ] **`openssl` dep for cert fingerprint pinning** — listed in spec but not in current dependencies
- [ ] **Post-processing / analysis tools**
- [ ] **Grafana / monitoring integration**

---

## Priority order

1. **CRITICAL**: Fix `enable_dgram` payload size from 1024 to 1200 — traffic payloads are 1200 bytes and may be silently dropped
2. **CRITICAL**: Fix telemetry seq_num persistence across reconnects (should not reset)
3. **HIGH**: Fix `extract_quic_stats` — RTT and CWND are hardcoded to 0
4. **HIGH**: Add Handshake validation and error response path on server
5. **MEDIUM**: Fix `display_stats` to show all fields (RTT, CWND, send_rate)
6. **LOW**: Add `openssl` dependency for cert fingerprint pinning
7. **LOW**: Telemetry backlog FIFO overflow behavior (oldest vs newest dropped)
8. **LOW**: Add `.gitignore`
