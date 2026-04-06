# RANMT Data Types Reference

> All Rust struct definitions with `serde` annotations, plus wire-format notes.
> These types are shared between client and server via the `shared` crate.

---

## 1. Wire Protocol Constants

```rust
/// QUIC ALPN for RANMT
pub const ALPN_RANMT: &[u8] = b"ranmt/0.1";

/// Maximum datagram payload size (must match protocol §4.1)
pub const MAX_DGRAM_SIZE: usize = 1200;

/// Traffic payload header size (payload_type + seq_num + send_ts)
pub const TRAFFIC_HEADER_SIZE: usize = 1 + 8 + 8; // 17 bytes

/// Telemetry store-and-forward buffer max capacity
pub const TELEMETRY_BUFFER_CAP: usize = 10_000;

/// Reconnection delay
pub const RECONNECT_DELAY_MS: u64 = 2000;

/// Server idle timeout
pub const IDLE_TIMEOUT_MS: u64 = 30_000;

/// Telemetry interval
pub const TELEMETRY_INTERVAL_MS: u64 = 1000;

/// Server stats interval
pub const STATS_INTERVAL_MS: u64 = 1000;

/// Datagram payload type identifier
pub const PAYLOAD_TYPE_TRAFFIC: u8 = 0x01;

/// Server address to bind (IPv4 only)
pub const SERVER_BIND_ADDR: &str = "0.0.0.0";

/// Default port
pub const DEFAULT_PORT: u16 = 4433;

/// Default bitrate for DL tests
pub const DEFAULT_BITRATE_BPS: u32 = 8000;
```

---

## 2. Handshake Types

### 2.1 `Handshake` (Client → Server, Stream 0)

```rust
use serde::{Serialize, Deserialize};
use uuid::Uuid;

/// No `#[serde(tag)]` here — the `WireMessage` enum owns the `"type"` key.
/// When serialized directly (for sending), we manually prepend `"type"`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Handshake {
    /// RFC 4122 UUID v4, hyphenated lowercase
    pub session_id: Uuid,
    /// Direction of the test
    pub direction: Direction,
    /// Target bitrate in bits per second
    pub bitrate_bps: u32,
    /// Protocol version for future compatibility checks
    pub client_version: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Direction {
    Dl, // downlink: server → client traffic
    Ul, // uplink: client → server traffic
}
```

**Wire format (JSON, `\n`-terminated):**
```json
{"type":"handshake","session_id":"550e8400-e29b-41d4-a716-446655440000","direction":"dl","bitrate_bps":8000,"client_version":"0.1.0"}\n
```

### 2.2 `HandshakeAck` (Server → Client, Stream 0)

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HandshakeAck {
    pub status: HandshakeStatus,
    pub message: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HandshakeStatus {
    Ok,
    Error,
}
```

### 2.3 `Goodbye` (Client → Server, Stream 0)

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Goodbye {
    pub reason: GoodbyeReason,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoodbyeReason {
    TestComplete,
    UserAbort,
    Error,
}
```

---

## 3. Server Stats

### 3.1 `ServerStats` (Server → Client, Stream 0)

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerStats {
    pub timestamp_ms: u64,
    pub quic_stats: QuicStats,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct QuicStats {
    /// Round-trip time in milliseconds
    pub rtt_ms: f64,
    /// Total bytes sent on this connection
    pub tx_bytes: u64,
    /// Total bytes received on this connection
    pub rx_bytes: u64,
    /// Current congestion window in bytes
    pub cwnd: u64,
    /// Total lost packets (from quiche internal tracking)
    pub lost_packets: u64,
    /// Estimated send rate in bits per second
    pub send_rate_bps: u64,
}
```

**Wire format:**
```json
{"type":"server_stats","timestamp_ms":1712448000000,"quic_stats":{"rtt_ms":45.2,"tx_bytes":123456,"rx_bytes":654321,"cwnd":12000,"lost_packets":3,"send_rate_bps":8050}}\n
```

---

## 4. Client Telemetry

### 4.1 `ClientTelemetry` (Client → Server, Stream 0)

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientTelemetry {
    /// Monotonically increasing sequence number
    pub seq_num: u64,
    /// Epoch milliseconds
    pub timestamp_ms: u64,
    /// Latitude, decimal degrees
    pub lat: f64,
    /// Longitude, decimal degrees
    pub lon: f64,
    /// Speed in km/h
    pub speed: f64,
    /// Network type
    pub network_type: NetworkType,
    /// Cell identifier
    pub cell_id: u32,
    /// Physical Cell ID
    pub pci: u16,
    /// E-UTRA Absolute RF Channel Number
    pub earfcn: u32,
    /// Reference Signal Received Power, dBm
    pub rsrp: f64,
    /// Reference Signal Received Quality, dB
    pub rsrq: f64,
    /// Signal-to-Interference-plus-Noise Ratio, dB
    pub sinr: f64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum NetworkType {
    #[serde(rename = "5g")]
    FiveG,
    Lte,
    #[serde(rename = "3g")]
    ThreeG,
    #[serde(rename = "2g")]
    TwoG,
    Unknown,
}
```

---

## 5. Traffic Datagram (Binary)

### 5.1 `TrafficPayload` — Binary Wire Format

NOT JSON. Raw binary sent via `conn.dgram_send()`.

```
Byte:  [0]        [1............8]  [9...........16]  [17...........N]
Field: payload_type  seq_num (u64)   send_ts (u64)     padding (0x00)
Size:  1 byte        8 bytes          8 bytes           N-17 bytes
```

### 5.2 Encoder

```rust
pub fn encode_traffic_payload(seq_num: u64, send_ts: u64, out: &mut [u8; MAX_DGRAM_SIZE]) {
    out[0] = PAYLOAD_TYPE_TRAFFIC;
    out[1..9].copy_from_slice(&seq_num.to_be_bytes());
    out[9..17].copy_from_slice(&send_ts.to_be_bytes());
    out[17..].fill(0);  // padding
}
```

### 5.3 Decoder

```rust
pub fn decode_traffic_payload(data: &[u8]) -> Option<(u64, u64)> {
    if data.len() < TRAFFIC_HEADER_SIZE || data[0] != PAYLOAD_TYPE_TRAFFIC {
        return None;
    }
    let seq_num = u64::from_be_bytes(data[1..9].try_into().ok()?);
    let send_ts = u64::from_be_bytes(data[9..17].try_into().ok()?);
    Some((seq_num, send_ts))
}
```

> **`seq_num`** and **`send_ts`** are returned for loss/jitter calculation. Padding is discarded.

---

## 6. Pacing State

```rust
/// Lightweight traffic pacer — generates payloads.
/// The caller (event loop) owns the tokio Interval timer.
/// This struct is fully synchronous and UniFFI-compatible.
pub struct TrafficPacer {
    bits_per_dgram: u64,
    bitrate_bps: u32,
    next_seq: u64,
}

impl TrafficPacer {
    pub fn new(bitrate_bps: u32) -> Self {
        Self {
            bits_per_dgram: (MAX_DGRAM_SIZE * 8) as u64,
            bitrate_bps,
            next_seq: 0,
        }
    }

    /// Calculate the tokio interval duration for this bitrate.
    /// Caller should create the Interval and set MissedTickBehavior::Delay.
    pub fn interval(&self) -> std::time::Duration {
        let datagrams_per_sec =
            (self.bitrate_bps as u64).saturating_mul(1_000_000)
            / self.bits_per_dgram;
        let interval_ns = if datagrams_per_sec > 0 {
            1_000_000_000 / datagrams_per_sec
        } else {
            1_000_000_000 // fallback: 1 dgram/s
        };
        std::time::Duration::from_nanos(interval_ns)
    }

    /// Generate the next traffic payload.
    pub fn next_payload(&mut self) -> (u64, [u8; MAX_DGRAM_SIZE]) {
        let seq = self.next_seq;
        self.next_seq += 1;
        let send_ts = current_epoch_ms();
        let mut buf = [0u8; MAX_DGRAM_SIZE];
        encode_traffic_payload(seq, send_ts, &mut buf);
        (seq, buf)
    }
}
```

**Usage in event loop:**
```rust
let mut pacer = TrafficPacer::new(bitrate_bps);
let mut traffic_interval = tokio::time::interval(pacer.interval());
traffic_interval.set_missed_tick_behavior(
    tokio::time::MissedTickBehavior::Delay);
traffic_interval.tick().await; // skip first tick

// In loop:
if traffic_interval.tick().now_or_never().is_some() {
    let (_seq, payload) = pacer.next_payload();
    let _ = conn.dgram_send(&payload);
}
```

> **Design:** `TrafficPacer` was made synchronous because the client event loop already manages a tokio interval. Separating the timer (async, event-loop concern) from the payload generator (sync, UniFFI-safe) keeps the pacer testable and portable.

---

## 7. Loss & Jitter Tracker (Receiver Side)

```rust
pub struct LossJitterTracker {
    prev_seq: Option<u64>,
    total_lost: u64,
    total_received: u64,
    prev_arrival_ms: Option<u64>,
    prev_send_ts: Option<u64>,
    jitter_ewma: f64,
}

impl LossJitterTracker {
    pub fn new() -> Self {
        Self {
            prev_seq: None,
            total_lost: 0,
            total_received: 0,
            prev_arrival_ms: None,
            prev_send_ts: None,
            jitter_ewma: 0.0,
        }
    }

    /// Call this for each received datagram.
    /// Returns: (loss_count, jitter_ms)
    pub fn on_datagram(&mut self, seq_num: u64, send_ts: u64, arrival_ms: u64) -> (u64, f64) {
        self.total_received += 1;

        // Loss detection
        let loss = match self.prev_seq {
            Some(prev) => {
                let gap = seq_num.saturating_sub(prev).saturating_sub(1);
                self.total_lost += gap;
                gap
            }
            None => 0,
        };
        self.prev_seq = Some(seq_num);

        // Jitter (PDV): |(arrival_i - arrival_{i-1}) - (send_ts_i - send_ts_{i-1})|
        let jitter = match (self.prev_arrival_ms, self.prev_send_ts) {
            (Some(prev_arr), Some(prev_send)) => {
                let delta_arrival = arrival_ms.saturating_sub(prev_arr) as f64;
                let delta_send = send_ts.saturating_sub(prev_send) as f64;
                let pdv = (delta_arrival - delta_send).abs();

                // EWMA
                self.jitter_ewma = 0.9 * self.jitter_ewma + 0.1 * pdv;
                pdv
            }
            _ => 0.0, // first packet, no jitter yet
        };
        self.prev_arrival_ms = Some(arrival_ms);
        self.prev_send_ts = Some(send_ts);

        (loss, jitter)
    }

    pub fn loss_rate(&self) -> f64 {
        let total = self.total_lost + self.total_received;
        if total == 0 { return 0.0; }
        self.total_lost as f64 / total as f64
    }

    pub fn jitter_ewma(&self) -> f64 {
        self.jitter_ewma
    }
}
```

---

## 8. Server Session State

```rust
use std::fs::File;
use std::path::PathBuf;
use std::time::Instant;

pub const DORMANT_TIMEOUT_HOURS: u64 = 24;

pub struct SessionState {
    pub session_id: Uuid,
    pub direction: Direction,
    pub bitrate_bps: u32,
    pub client_version: String,
    pub jsonl_path: PathBuf,
    /// Open JSONL file handle for telemetry (append mode)
    pub jsonl_file: File,
    /// Datagram loss/jitter tracking (for UL tests)
    pub datagram_tracker: LossJitterTracker,
    /// Timestamp when the QUIC connection was lost (None = active)
    pub dormant_since: Option<Instant>,
}

impl SessionState {
    pub fn new(session_id: Uuid, direction: Direction,
               bitrate_bps: u32, jsonl_path: &Path) -> Self {
        let jsonl_file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(jsonl_path)
            .expect("failed to open jsonl");
        Self {
            session_id,
            direction,
            bitrate_bps,
            client_version: String::new(),
            jsonl_path: jsonl_path.to_owned(),
            jsonl_file,
            datagram_tracker: LossJitterTracker::new(),
            dormant_since: None,
        }
    }

    pub fn write_telemetry(&mut self, tel: &ClientTelemetry) {
        use std::io::Write;
        // Serialize through WireMessage to get "type":"telemetry" in output
        let line = serde_json::to_string(&WireMessage::ClientTelemetry(tel.clone())).unwrap();
        let _ = writeln!(self.jsonl_file, "{line}");
        let _ = self.jsonl_file.sync_all();
    }

    /// Mark the session as dormant (QUIC connection lost).
    pub fn mark_dormant(&mut self) {
        self.dormant_since = Some(Instant::now());
        let _ = self.jsonl_file.sync_all();
    }

    /// Check if this dormant session has expired its 24h window.
    pub fn is_dormant_expiring(&self) -> bool {
        match self.dormant_since {
            Some(since) => since.elapsed()
                >= std::time::Duration::from_secs(DORMANT_TIMEOUT_HOURS * 3600),
            None => false,
        }
    }

    /// Check if currently dormant.
    pub fn is_dormant(&self) -> bool {
        self.dormant_since.is_some()
    }

    /// Wake up a dormant session (e.g., after reconnect).
    pub fn wake(&mut self) {
        self.dormant_since = None;
        self.jsonl_file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.jsonl_path)
            .expect("failed to reopen jsonl");
    }

    /// Flush and permanently close the JSONL file.
    pub fn close_jsonl(&mut self) {
        let _ = self.jsonl_file.sync_all();
        tracing::info!(
            session_id = ?self.session_id,
            path = ?self.jsonl_path,
            "JSONL file synced and closed"
        );
    }
}
```

> `write_telemetry` uses `sync_all()` to force disk writes immediately. For production throughput, consider buffering with periodic sync (e.g., every 5 seconds).

---

## 9. JSONL File Format (Server Output)

Each line in `session_<uuid>.jsonl` is one raw `ClientTelemetry` JSON message:

```
{"type":"telemetry","seq_num":0,"timestamp_ms":1712448000000,"lat":41.9028,"lon":12.4964,"speed":0.0,"network_type":"lte","cell_id":12345,"pci":150,"earfcn":1850,"rsrp":-95.2,"rsrq":-10.5,"sinr":8.3}
{"type":"telemetry","seq_num":1,"timestamp_ms":1712448001000,"lat":41.9029,"lon":12.4965,"speed":12.5,"network_type":"lte","cell_id":12345,"pci":150,"earfcn":1850,"rsrp":-94.8,"rsrq":-10.3,"sinr":8.7}
```

> No header line. No blank lines between records. Pure newline-delimited JSON.

---

## 10. `WireMessage` Enum (serde tagging strategy)

This enum is the **sole owner** of the `"type"` JSON key. Individual structs (Handshake, HandshakeAck, etc.) must NOT have `#[serde(tag = "type")]` — that would create nested duplicate keys.

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum WireMessage {
    #[serde(rename = "handshake")]
    Handshake(Handshake),
    #[serde(rename = "handshake_ack")]
    HandshakeAck(HandshakeAck),
    #[serde(rename = "server_stats")]
    ServerStats(ServerStats),
    #[serde(rename = "telemetry")]
    ClientTelemetry(ClientTelemetry),
    #[serde(rename = "goodbye")]
    Goodbye(Goodbye),
}
```

**How serde handles this** for newtype variants with `#[serde(tag = "type")]`:
- **Serialization:** `WireMessage::Handshake(h)` → serde writes `{"type":"handshake", <Handshake fields>}`
- **Deserialization:** `{"type":"handshake", ...}` → serde reads `"type"`, finds `"handshake"`, matches the variant, deserializes the inner struct

Usage in code:
```rust
// Sender side — wrap in enum for automatic type injection
let msg = WireMessage::Handshake(Handshake { ... });
let json = serde_json::to_string(&msg).unwrap();
// → {"type":"handshake","session_id":"...","direction":"dl",...}

// Receiver side — automatic dispatch
let msg: WireMessage = serde_json::from_str(&line).unwrap();
```

**Alternative:** If you prefer to serialize structs directly (without the enum wrapper), manually prepend the `"type"` key:
```rust
let payload = format!("\"type\":\"handshake\",{}", serde_json::to_string(&h).unwrap());
```
The enum approach is preferred — it's less error-prone.

---

## 11. Utility Functions

### 11.1 `current_epoch_ms()`

```rust
pub fn current_epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
```

Returns Unix epoch in milliseconds. Used for `timestamp_ms` and `send_ts`.
