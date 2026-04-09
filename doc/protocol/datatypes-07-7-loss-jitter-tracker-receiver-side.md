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

