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

