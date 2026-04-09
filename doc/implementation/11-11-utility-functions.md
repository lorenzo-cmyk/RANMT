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

Returns Unix epoch in milliseconds. Used for `timestamp_ms` in telemetry and `send_ts` in datagrams.

### 11.2 `encode_traffic_payload()` / `decode_traffic_payload()`

```rust
pub fn encode_traffic_payload(
    seq_num: u64, send_ts: u64,
    out: &mut [u8; 1200],
) {
    out[0] = 0x01;
    out[1..9].copy_from_slice(&seq_num.to_be_bytes());
    out[9..17].copy_from_slice(&send_ts.to_be_bytes());
    out[17..].fill(0);
}

pub fn decode_traffic_payload(data: &[u8]) -> Option<(u64, u64)> {
    if data.len() < 17 || data[0] != 0x01 {
        return None;
    }
    let seq_num = u64::from_be_bytes(data[1..9].try_into().ok()?);
    let send_ts = u64::from_be_bytes(data[9..17].try_into().ok()?);
    Some((seq_num, send_ts))
}
```

### 11.2.1 `display_stats()` — CLI Output for Server Stats

```rust
/// Pretty-print server stats in the client CLI.
fn display_stats(stats: &ServerStats) {
    tracing::info!(
        "RTT={:.1}ms | TX={}B | RX={}B | CWND={}B | Lost={} | Rate={}bps",
        stats.quic_stats.rtt_ms,
        stats.quic_stats.tx_bytes,
        stats.quic_stats.rx_bytes,
        stats.quic_stats.cwnd,
        stats.quic_stats.lost_packets,
        stats.quic_stats.send_rate_bps,
    );
}
```

### 11.2.2 `display_loss()` — CLI Output for Packet Loss/Jitter (DL Test)

```rust
/// Log packet loss and jitter for received datagrams.
fn display_loss(
    seq_num: u64, lost: u64, jitter: f64,
    loss_rate: &f64, jitter_ewma: f64,
) {
    if lost > 0 || jitter > 1.0 {
        tracing::info!(
            "DL seq={} | lost={} | jitter={:.1}ms | rate={:.2}% | ewma={:.1}ms",
            seq_num, lost, jitter,
            loss_rate * 100.0, jitter_ewma,
        );
    }
}
```

> **Suppressive:** Quiet logs only fire when there's actual loss or jitter above 1ms to avoid flooding during normal operation.

### 11.3 `resolve_ipv4()` — Strict IPv4 DNS Resolver

```rust
use std::net::{SocketAddr, ToSocketAddrs};

/// Resolve a host string (FQDN or literal IPv4) to a single IPv4 address.
///
/// - If `host` is a literal IPv4 address (e.g. "10.0.0.50"), return it.
/// - If `host` is a FQDN, perform DNS resolution and select the first A record.
/// - If only AAAA records exist, return an error.
pub fn resolve_ipv4(host: &str, port: u16) -> Result<SocketAddr, Box<dyn std::error::Error>> {
    let addrs = (host, port).to_socket_addrs()?;
    for addr in addrs {
        if addr.is_ipv4() {
            return Ok(addr);
        }
    }
    Err(format!(
        "host '{}' has no IPv4 address (only AAAA records found)",
        host
    ).into())
}
```

**Usage in client:**
```rust
let peer_addr = resolve_ipv4(&config.server_addr, config.port)?;
```

> **Why not `tokio::net::lookup_host`?** `to_socket_addrs` from std uses the system resolver and is blocking, but for a one-shot DNS query before the main loop, the latency is negligible. In a production async context, consider `hickory-resolver` for non-blocking DNS.

### 11.4 `calc_traffic_interval()` — Bitrate to Interval Converter

```rust
use std::time::Duration;

/// Convert a target bitrate (bps) into a tokio interval for pacing.
/// Returns an `Interval` that fires once per datagram to send.
pub fn calc_traffic_interval(bitrate_bps: u32) -> Duration {
    let bits_per_dgram = (MAX_DGRAM_SIZE * 8) as u64;
    let datagrams_per_sec = (bitrate_bps as u64)
        .saturating_mul(1_000_000) / bits_per_dgram;
    // Avoid division by zero for very low bitrates
    let interval_us = if datagrams_per_sec > 0 {
        1_000_000_000 / datagrams_per_sec // nanoseconds for precision
    } else {
        1_000_000_000 // fallback: 1 datagram/s
    };
    Duration::from_nanos(interval_us)
}
```

> **Note on precision:** We use nanoseconds internally (`1_000_000_000 / datagrams_per_sec`) because microsecond-resolution tokio intervals can round to 0 for high bitrates. tokio supports sub-millisecond intervals via `Duration::from_nanos()`.

### 11.5 `extract_quic_stats()` — QUIC Stats Mapper for Server

```rust
/// Extract a QuicStats struct from a quiche Connection.
fn extract_quic_stats(conn: &quiche::Connection) -> QuicStats {
    let s = conn.stats();
    let rtt = s.rtt.as_secs_f64() * 1000.0;
    QuicStats {
        rtt_ms: rtt,
        tx_bytes: s.sent,        // total bytes sent
        rx_bytes: s.received,    // total bytes received
        cwnd: s.cwnd,
        lost_packets: s.lost,
        send_rate_bps: extract_send_rate_bps(&s),
    }
}
```

**`send_rate_bps` extraction** is not directly available from `quiche::Stats` as a raw number:
```rust
/// Extract send rate in bps if available.
/// quiche may not expose this in all versions; fallback to 0.
fn extract_send_rate_bps(stats: &quiche::Stats) -> u64 {
    // In quiche 0.22, send_rate is available in some builds.
    // If not, return 0 and let post-processing tools calculate
    // from tx_bytes / time delta.
    0 // placeholder — update when quiche API exposes it
}
```

> **Note:** `conn.stats()` returns a `quiche::Stats` struct. The exact fields available depend on the quiche version. In 0.22, `rtt`, `cwnd`, `sent`, `received`, `lost` are reliably available. `send_rate` may require reading from quiche FFI.

---

