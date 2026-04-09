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
