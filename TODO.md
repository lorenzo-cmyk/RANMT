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

- [x] **Missing `openssl` / `openssl-sys` dependency** — spec calls for `conn.set_verify(SslVerifyMode::NONE)` for insecure mode via openssl. Current code uses `quic_cfg.verify_peer(false)` which may work — verify this is sufficient
- [x] **`display_stats` incomplete** — protocol §5 says display RTT, TX, RX, CWND, lost, send_rate. Now shows all fields using `path_stats()` from quiche 0.28
- [x] **UL datagram loss/jitter tracking on server** — server now logs UL datagram stats at INFO level instead of only DEBUG
- [x] CLI args match spec (§4.2): server, direction, port, bitrate, duration, insecure, seed
- [x] Reconnection loop: 2s delay, session_id persistence
- [x] Telemetry generation at 1 Hz with VecDeque backlog
- [x] Store-and-forward: backlog drained after handshake
- [x] QUIC timeout handling with fallback
- [x] `flush_quic` after connection build
- [x] No `std::process::exit`, `std::thread`, or `uniffi` blockers

---

## 3. Server (`server/src/main.rs`)

- [x] **Handshake validation** — validates bitrate range (1k-1Mbps) and 3-part semver X.Y.Z. Rejects with error `HandshakeAck`.
- [x] **Error handshake handling** — sends `HandshakeAck { status: "error", message: "..." }` on validation failure
- [x] **`QUIC v1` protocol version** — `quiche::PROTOCOL_VERSION` maps to v1 (RFC 9000)
- [x] **`Idle timeout unit`** — `IDLE_TIMEOUT_MS * 1000` is correct (30s) but the constant name is misleading. Added a doc comment explaining that quiche expects microseconds.
- [x] **`extract_quic_stats`** — RTT from `path_stats().rtt`, CWND from `path_stats().cwnd`, send_rate from `path_stats().delivery_rate`
- [x] **`enable_dgram` parameters** — now uses `MAX_DGRAM_SIZE` (1200) instead of hardcoded 1024
- [x] **`send_rate_bps` extraction** — from `path_stats().delivery_rate` in quiche 0.28. Quiche may return 0 in some builds; this is a quiche limitation, not ours.
- [x] **Server `stats_interval` uses `MissedTickBehavior::Skip`** — minor; skip is fine for stats. Server uses Delay for initial stats ticker (reset after handshake).
- [x] **`process_stream_messages` wildcard** — `_ => {}` arm exists and is documented
- [x] **Goodbye reason logging** — `reason` is destructured and logged as `{reason:?}`

---

## 4. Protocol compliance gaps

- [x] **Max datagram size mismatch** — both client and server now use `enable_dgram(true, MAX_DGRAM_SIZE, MAX_DGRAM_SIZE)` which resolves to 1200
- [x] **Handshake error path** — sends error `HandshakeAck` on validation failure (bitrate, version)
- [x] **`client_version` validation** — requires 3-part X.Y.Z semver
- [x] **Seq_num persistence across reconnects** — `telemetry_seq` and `datagram_seq` persisted in outer scope on client; `SessionState.datagram_send_seq` added for server-side DL pacer. Both `TrafficPacer` and `ServerTrafficState` now restore seq on reconnect per spec §8.2 rule 4
- [x] **`Handshake` sent before telemetry** — handshake is checked first in the event loop processing order; telemetry only sends after `handshake_done`
- [x] **UL traffic pacer requires `handshake_done`** — correct per protocol flow (client waits for ack)
- [x] **No Goodbye response handling on client** — minor; client sends Goodbye with FIN and closes without awaiting server ack. Per protocol §9.1, no server ack is defined - fire-and-forget.
- [x] **Telemetry backlog FIFO overflow** — oldest dropped when `TELEMETRY_BUFFER_CAP` reached via `pop_front()`

---

## 5. Infrastructure

- [x] **`.gitignore`** — `target/`, `sessions/`, `.claude/` and `*.pem` are .gitignored
- [x] **Server CLI port arg** — `--port` required, no default
- [x] **Dependencies updated to latest** — quiche 0.28, rcgen 0.14, getrandom 0.4

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
