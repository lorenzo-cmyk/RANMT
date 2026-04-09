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
