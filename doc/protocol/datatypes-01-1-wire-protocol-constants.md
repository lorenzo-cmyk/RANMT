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
pub const IDLE_TIMEOUT_MS: u64 = 10_000;

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

