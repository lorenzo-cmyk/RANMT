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

