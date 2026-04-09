# RANMT Protocol Specification

> Wire protocol running over QUIC. This document defines every message, stream, and datagram exchanged between client and server.

---

## 1. Connection Model

### 1.1 QUIC Transport Parameters

| Parameter                  | Value                  | Notes                                         |
| -------------------------- | ---------------------- | --------------------------------------------- |
| Version                    | QUIC v1 (RFC 9000)     |                                               |
| Max bidirectional streams  | 4                      | We use only 1 bidi stream per connection      |
| Max unidirectional streams | 8                      | Reserved for future use                       |
| Max datagram frame size    | **1200 bytes**         | Matches typical IPv4 MTU; our payloads ≤ 1200 |
| CC algorithm               | BBR                    | Set via `set_cc_algorithm(BBR)`               |
| Datagram support           | **enabled** (RFC 9221) | Required for unreliable traffic flow          |
| idle_timeout               | **10 000 ms**          | Server-side; client reconnects faster         |

### 1.2 TLS Configuration

QUIC mandates TLS 1.3. A TLS certificate is therefore mandatory before any data can flow.

- **Certificate source:** The server loads a certificate + private key from disk at startup. In production, this is a valid certificate (e.g. Let's Encrypt) for a real FQDN that resolves to the server's IPv4 address. For local/dev use, a self-signed cert can be generated on-the-fly via `rcgen`.
- **SNI (Server Name Indication):** The client MUST set the correct `server_name` (FQDN) when calling `quiche::connect()`. This is not optional — QUIC implementations require it for certificate verification and for the server to select the right certificate.
- **Client verification:** In production, the client verifies the server certificate chain against the system CA trust store, matching the cert's SAN against the FQDN. In dev mode, a `--insecure` CLI flag disables verification.
- **ALPN:** Exactly `[b"ranmt/0.1"]`. Both client and server must set this.
- **Server socket binding:** The UDP socket binds to `0.0.0.0:<port>` to accept connections on all interfaces, but the **TLS handshake still requires a valid FQDN** to complete. The FQDN is resolved to an IPv4 address by the client, which then targets that IP. The server's certificate must have the FQDN in its SAN list.
- **Only IPv4 addresses** are used. The client resolves the FQDN and selects only A records (no AAAA).

### 1.3 Stream Registry

QUIC assigns stream IDs according to a fixed formula. For bidirectional streams, client-initiated IDs start at **0** and increment by **4** (0, 4, 8, 12, ...). Server-initiated IDs start at **1** and increment by 4 (1, 5, 9, 13, ...).

We use a single client-initiated bidirectional stream for all application data, multiplexed by the JSON `"type"` field. This avoids the complexity of synchronizing reads/writes across multiple bidirectional streams and eliminates the question of stream ordering.

| Purpose                 | QUIC Stream ID | Direction       | How it works                                                       |
| ----------------------- | -------------- | --------------- | ------------------------------------------------------------------ |
| All application data    | **0**          | Bidirectional   | Client opens first. Client writes Handshake + Telemetry. Server reads. Server writes ServerStats back. Both sides read by JSON type. |
| QUIC Datagrams          | N/A            | Both directions | Not a stream. Uses `dgram_send()` / `dgram_recv()` for traffic.   |

#### Why a single stream?

- Opening a second bidirectional stream (e.g., for stats) would use ID **4**. The client would need to accept it from the server side, which requires additional event handling in quiche for server-initiated stream creation.
- Telemetry and stats flow in opposite directions but never compete: the client writes telemetry and reads stats; the server reads telemetry and writes stats. A single bidi stream handles this naturally.
- The JSON `"type"` discriminator cleanly separates the three message types without any stream-level multiplexing overhead.

---

## 2. Message Frame Format

All messages serialized over streams use **JSON** encoded as UTF-8, terminated by a **single `\n` (LF, 0x0A)** byte. This makes each message self-delimiting on the byte stream.

### 2.1 Frame Envelope

Every JSON message carries a `"type"` discriminator:

```jsonc
{
  "type": "<message_type>",
  ...fields...
}
```

---

## 3. Control Handshake (Stream 0 → S_HANDSHAKE)

Initiated by the client immediately after the QUIC connection is established.

### 3.1 `Handshake` (Client → Server, exactly 1 per connection)

```jsonc
{
  "type": "handshake",
  "session_id": "550e8400-e29b-41d4-a716-446655440000",  // UUID v4, lowercase
  "direction": "dl",                                     // "dl" | "ul"
  "bitrate_bps": 8000,                                   // target bitrate in bits per second
  "client_version": "0.1.0"                              // protocol version
}
```

| Field            | Type   | Constraints                                 |
| ---------------- | ------ | ------------------------------------------- |
| `type`           | string | Must be `"handshake"`                       |
| `session_id`     | string | RFC 4122 UUID v4, canonical hyphenated form |
| `direction`      | string | `"dl"` (downlink) or `"ul"` (uplink)        |
| `bitrate_bps`    | u32    | 1 000 – 1 000 000 (1 kbps – 1 Mbps)         |
| `client_version` | string | Semver `"major.minor.patch"`                |

**Server behavior on receipt:**
1. Parse and validate all fields.
2. If `direction` is `"dl"` (downlink test): server will send datagram traffic to the client.
3. If `direction` is `"ul"` (uplink test): client will send datagram traffic to the server.
4. If bidirectional: both sides send datagrams (future extension, not yet implemented).
5. Begin sending ServerStats on Stream 0 (server → client direction of the same bidi stream).
6. Begin reading telemetry from Stream 0 (client → server direction).
7. Reply on Stream 0 with `HandshakeAck`.

### 3.2 `HandshakeAck` (Server → Client, exactly 1)

```jsonc
{
  "type": "handshake_ack",
  "status": "ok",           // "ok" | "error"
  "message": ""             // non-empty on error
}
```

**Client behavior:** Client MUST NOT start traffic or telemetry until receiving `{"type":"handshake_ack","status":"ok"}`.

---

## 4. Datagram Traffic Flow (Unreliable, RFC 9221)

Datagrams are used for synthetic traffic. They are **not** JSON-framed with `\n`; they are fixed-length binary payloads sent via `dgram_send()`.

### 4.1 `TrafficPayload` Datagram (binary)

Each datagram is exactly **N bytes** (up to `max_dgram_payload_size`). The binary wire format is:

```
Offset  Size  Field          Endianness
------  ----  -----          ----------
0       1     payload_type   —
1       8     seq_num        big-endian u64
9       8     send_ts        big-endian u64 (Unix epoch milliseconds)
17      N-17  padding        zero bytes
```

| Field          | Value / Description                                    |
| -------------- | ------------------------------------------------------ |
| `payload_type` | `0x01` = synthetic traffic (`TrafficPayload`)          |
| `seq_num`      | Monotonically increasing per-session, starting at 0    |
| `send_ts`      | Timestamp of when the datagram was enqueued (epoch ms) |
| `padding`      | Filled with `0x00` to reach target datagram size       |

> **Datagram size:** Fixed at **1200 bytes** per packet. With 17 bytes header, padding = 1183 zero bytes. This matches typical IPv4 MTU and avoids IP fragmentation.

### 4.2 Traffic Direction

- **DL test (`"dl"`):** Server → Client datagrams. Client receives, computes loss & jitter, logs locally.
- **UL test (`"ul"`):** Client → Server datagrams. Server receives, computes loss & jitter, logs locally.

### 4.3 Bitrate Pacing

Traffic is paced to achieve `bitrate_bps`.

```
bits_per_dgram        = datagram_size * 8
datagrams_per_second  = bitrate_bps / bits_per_dgram
interval_ns           = 1_000_000_000 / datagrams_per_second  (nanoseconds, for sub-ms precision)
```

For the default **8000 bps** at **1200 bytes**:
- `bits_per_dgram = 9600`
- `datagrams_per_second = 8000 / 9600 ≈ 0.833`
- `interval_ns = 1_200_000_000 ns` (≈ 1 datagram every 1200 ms)

The sender MUST implement a pacing timer (tokio `Interval` or `sleep`) to space transmissions. Bursts must be avoided.

### 4.4 Loss & Jitter Calculation (Receiver Side)

**Packet Loss (gap-based):**
```
lost_packets = current_seq_num - (prev_seq_num + 1)   // if > 0
total_sent   = current_seq_num + 1
loss_rate    = total_lost / total_sent
```

**Relative Jitter (Packet Delay Variation):**
```jitter_i = |(arrival_i - arrival_{i-1}) - (send_ts_i - send_ts_{i-1})|```

Jitter is computed per-datagram arrival. The receiver maintains an exponentially weighted moving average (EWMA):
```jitter_ewma = 0.9 * jitter_ewma + 0.1 * |delta_arrival - delta_send|```

Initial value: `jitter_ewma` of first computed pair.

---

## 5. Server Stats (Stream 0, Server → Client)

Server periodically pushes connection stats to the client **on the same bidirectional stream**. The client distinguishes stats messages from telemetry by the `"type"` JSON discriminator.

### 5.1 `ServerStats` (Server → Client, periodic, interval: **1000 ms**)

```jsonc
{
  "type": "server_stats",
  "timestamp_ms": 1712448000000,
  "quic_stats": {
    "rtt_ms": 45.2,
    "tx_bytes": 123456,
    "rx_bytes": 654321,
    "cwnd": 12000,
    "lost_packets": 3,
    "send_rate_bps": 8050
  }
}
```

| Field           | Type | Source                                            |
| --------------- | ---- | ------------------------------------------------- |
| `timestamp_ms`  | u64  | Server epoch milliseconds                         |
| `rtt_ms`        | f64  | `conn.stats().stats().rtt.as_millis()`            |
| `tx_bytes`      | u64  | `conn.stats().stats().sent`                       |
| `rx_bytes`      | u64  | `conn.stats().stats().received`                   |
| `cwnd`          | u64  | `conn.stats().stats().cwnd`                       |
| `lost_packets`  | u64  | `conn.stats().stats().lost`                       |
| `send_rate_bps` | u64  | `conn.stats().stats().send_rate_mbps * 1_000_000` |

> The client displays these stats in the CLI. It does **not** need to acknowledge or respond.

---

## 6. Extended Telemetry (Stream 0, Client → Server)

Client periodically pushes environmental telemetry to the server, on the same bidirectional stream. The server distinguishes telemetry from other messages via the `"type"` JSON discriminator.

### 6.1 `ClientTelemetry` (Client → Server, periodic, interval: **1000 ms**)

```jsonc
{
  "type": "telemetry",
  "seq_num": 42,
  "timestamp_ms": 1712448000000,
  "lat": 41.9028,
  "lon": 12.4964,
  "speed": 60.0,
  "network_type": "lte",
  "cell_id": 12345,
  "pci": 150,
  "earfcn": 1850,
  "rsrp": -95.2,
  "rsrq": -10.5,
  "sinr": 8.3
}
```

| Field          | Type   | Unit / Constraints                    |
| -------------- | ------ | ------------------------------------- |
| `type`         | string | Must be `"telemetry"`                 |
| `seq_num`      | u64    | Monotonically increasing, starts at 0 |
| `timestamp_ms` | u64    | Epoch milliseconds                    |
| `lat`          | f64    | Decimal degrees, -90 to 90            |
| `lon`          | f64    | Decimal degrees, -180 to 180          |
| `speed`        | f64    | km/h                                  |
| `network_type` | string | `"5g"                                 | "lte" | "3g" | "2g" | "unknown"` |
| `cell_id`      | u32    | Physical Cell ID                      |
| `pci`          | u16    | Physical Cell ID                      |
| `earfcn`       | u32    | E-UTRA Absolute RF Channel Number     |
| `rsrp`         | f64    | dBm                                   |
| `rsrq`         | f64    | dB                                    |
| `sinr`         | f64    | dB                                    |

### 6.2 Server Behavior

- Telemetry records are appended to a file named `session_<session_id>.jsonl` in the server's output directory.
- Each line is the raw JSON of a single `ClientTelemetry` message.
- File is opened in append mode. No truncation on reconnection.

---

## 7. Store-and-Forward Telemetry (The Tunnel Problem)

### 7.1 Client-Side Buffer

- The client maintains a `VecDeque<ClientTelemetry>` buffer with a **max capacity of 10 000** entries.
- When full, the oldest entry is silently dropped (FIFO overflow).
- Telemetry generation (1-second ticks) continues even when the QUIC connection is down. Entries are pushed to the buffer.

### 7.2 Flush Protocol

Upon successful handshake acknowledgment, the client:

1. **Drains the backlog** — pops all entries from the `VecDeque` and transmits them on Stream 0 as regular `{"type":"telemetry",...}` messages.
2. The client SHOULD **flush in bursts** — send all backlog entries as fast as the QUIC flow control allows (one JSON per `\n`).
3. **After the backlog is fully drained**, the client enters the normal periodic 1-second telemetry loop.

### 7.3 No Telemetry Ack Needed

The client does not wait for server acknowledgment of telemetry. Fire-and-forget. If the server receives a malformed message, it logs a warning and continues.

---

## 8. Connection Lifecycle & Reconnection

### 8.1 Full Lifecycle (DL test example)

```
  Client                                          Server
    |                                                 |
    | ---- QUIC handshake (TLS 1.3, 1-RTT) ---------> |
    | ---- {"type":"handshake", ...} (Stream 0) ----> |
    | <--- {"type":"handshake_ack","status":"ok"} --- |
    |                                                 |
    | ---- {"type":"telemetry",...} (Stream 0) ----->  | (periodic)
    | <--- {"type":"server_stats",...} (Stream 0) --- | (periodic)
    | <--- [datagrams: traffic] --------------------- | (pacing loop)
    |                                                 |
    | ==== [DISCONNECT: tunnel, handover, timeout] == |
    |                                                 |
    | (wait 2s, retry: build new QUIC conn)           |
    | ---- QUIC re-connect -------------------------> |
    | ---- {"type":"handshake", same session_id} --> |
    | <--- {"type":"handshake_ack","status":"ok"} --- |
    | ---- flush stored telemetry (Stream 0) ------->  |
    | ---- resume telemetry + receive stats --------- |
    | <--- resume datagrams ------------------------ |
```

### 8.2 Reconnection Rules

1. **Trigger:** ANY of the following triggers reconnection:
   - `conn.is_closed()` returns `true` (after `on_timeout()` or `recv()`).
   - `conn.recv()` returns `Err(_)`.
   - UDP socket error on `recv_from()`.
   - Any `quiche::Error::Done` from `stream_recv`, `dgram_recv`, or `conn.send()` is **NORMAL** — it does NOT trigger disconnect. Only stream/socket-level failures do.

2. **Reconnection delay:** **2000 ms** (fixed, not exponential backoff). While disconnected, telemetry continues to be generated and buffered (see Section 7).

3. **Session ID persistence:** The `session_id` UUID is **constant** across all reconnections within the same test run. This allows the server to stitch all data into the same `session_<uuid>.jsonl` file.

4. **Seq_num continuity:** `seq_num` for datagrams **is never reset**. It continues incrementing from the last value, even across reconnections. The same applies to telemetry `seq_num`. This ensures that post-processing tools can detect gaps and calculate true data completeness across the entire session, regardless of how many disconnections occurred.

5. **Server-side reconnect handling:** The server does **not** actively probe for dead connections. It relies on:
  - QUIC `idle_timeout` (10 s) to detect when a client has silently dropped.
   - A new QUIC connection arriving with a `Handshake` containing a `session_id` that matches an existing session. When this happens, the server:
     1. Tears down the old (dead) connection and its associated resources (stats ticker, traffic pacer).
     2. Associates the **new** QUIC connection with the existing `SessionState`.
     3. Sends `HandshakeAck` and resumes sending stats and datagrams on the new connection.
     4. **Does NOT truncate or reset** the JSONL file — it continues appending.

### 8.3 Typical Disconnect / Reconnect Timeline (Tunnel Scenario)

```
  Time      Client State              Server State
──────────  ─────────────────────────────────────────────────────────
  T+0s      Connected, sending telem.  Active session, sending DL traffic
  T+5s      Enters tunnel (no IP)     Still sending → UDP loss, no ACK
  T+10s     QUIC detects loss         idle_timeout timer still running
             (breaks event loop)
  T+12s     Starts 2s reconnect wait  idle_timeout still ticking
  T+14s     Retry #1 (socket fails)   —
  T+16s     Retry #2 (socket fails)   —
  ...
  T+15s     Old QUIC conn timed out   Server cleans up old conn,
             on server                  but SessionState kept alive
                                        (session is "dormant")
  T+40s     Tunnel exit. Retry N.     —
  T+40.5s   DNS resolve OK.           —
             New QUIC handshake
  T+41s     Handshake sent (same sid) Server: "Hey, session X back!"
             → Stream 0                → Tears old conn, binds new conn
  T+41.1s   Receives HandshakeAck     → Sends stats on Stream 0
  T+41.1s   Flushes backlog (18 tel.) → Appends all to session_X.jsonl
  T+42s     Normal operation resumes   → Resumes DL traffic pacer
                                       → Resumes stats ticker
```

#### Key behaviors during the disconnected period (T+5s → T+42s):

| Behavior                          | What happens                                                                                                                                                                                                                              |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Telemetry generation              | Continues at 1 Hz. Entries pushed to `VecDeque` buffer.                                                                                                                                                                                   |
| Datagram seq_num                  | **Paused** — no traffic sent or received while disconnected.                                                                                                                                                                              |
| Buffer overflow (>10 000 entries) | Oldest entries silently dropped. ~2h46m of telemetry at 1 Hz before loss.                                                                                                                                                                 |
| Server old conn cleanup           | After 10s idle timeout, QUIC conn is closed. SessionState marked "dormant".                                                                                                                                                               |
| Server dormant session lifetime   | **24 hours max**. If no reconnect within 24h, SessionState is evicted and JSONL closed.                                                                                                                                                   |
| Post-reconnect jitter calculation | Jitter will show a **massive spike** for the first datagram after reconnect (large `delta_arrival`). This is expected and should be visible in analysis but not skew EWMA excessively — EWMA naturally dampens single outliers over time. |

### 8.4 Multi-Disconnect Edge Cases

- **Server restart while client is disconnected:** Upon reconnect, the server has no memory of the session. The client sends a `Handshake` with its existing `session_id`. The server creates a **new** `SessionState` with the same UUID and opens the existing JSONL file in append mode. The JSONL file path is `session_<uuid>.jsonl`, so it will naturally append to the pre-existing file. This means a server restart **does not lose data** — the session continuity is preserved on disk. However, per-connection state (stats ticker, traffic pacer, datagram tracker) is rebuilt from scratch.

- **Client-side `seq_num` for datagrams while offline:** The seq_num counter is **not** incremented during disconnection. It only increments on actual `dgram_send()` calls. This ensures the receiver can still use gaps in `seq_num` to identify lost packets vs. offline periods.

- **Concurrent sessions on server:** The server supports multiple active sessions (different `session_id` values) simultaneously. Each session has its own QUIC connection, JSONL file, stats ticker, and traffic pacer.

---

## 9. Shutdown Protocol

### 9.1 Graceful Shutdown (Initiated by Client)

Client sends a `{"type":"goodbye"}` message on Stream 0, then closes the QUIC connection gracefully:

```jsonc
{
  "type": "goodbye",
  "reason": "test_complete"   // "test_complete" | "user_abort" | "error"
}
```

Server behavior:
1. Receives `goodbye`, flushes any pending writes.
2. Closes the session JSONL file.
3. Closes the QUIC connection.

### 9.2 Abrupt Shutdown

If the client crashes or drops connection, the server relies on `idle_timeout` (30 s) to detect dead connections. The session JSONL file remains open with whatever data was received — this is the expected behavior.

---

## 10. Error Handling Contract

| Condition                     | Action                                                         |
| ----------------------------- | -------------------------------------------------------------- |
| Malformed JSON on any stream  | Log `WARN`, skip message, continue stream                      |
| `Handshake` validation fail   | Send `{"type":"handshake_ack","status":"error"}`, close stream |
| Datagram payload ≠ 1200 bytes | Log `WARN`, skip counting as loss                              |
| `payload_type` ≠ `0x01`       | Log `WARN`, skip                                               |
| Telemetry file write fails    | Log `ERROR`, continue (don't crash test)                       |
| Connection drops mid-stream   | Break inner event loop, log `INFO`, reconnect                  |
| QUIC timeout (`idle_timeout`) | Connection closes, server cleans up resources                  |

---

## 11. Mock Data Generation (Client CLI)

Until real modem integration, telemetry uses deterministic oscillating mock data:

- **Lat/Lon:** Small oscillation around `41.9028, 12.4964` (Roma Termini) with `sin(t)` wave, amplitude ~0.001°.
- **Speed:** Sinusoid between 0 and 120 km/h, period = 60 s.
- **RSRP:** Oscillates between -120 and -80 dBm, period = 45 s.
- **RSRQ:** Oscillates between -20 and -3 dB, period = 45 s.
- **SINR:** Oscillates between -5 and 25 dB, period = 50 s.
- **Network type:** Cycles lte → 5g → 3g → lte every 30 s.
- **Cell ID / PCI / EARFCN:** Increment by 1 every 30 s (simulating handover).

A `--seed <u64>` CLI flag ensures reproducibility.
