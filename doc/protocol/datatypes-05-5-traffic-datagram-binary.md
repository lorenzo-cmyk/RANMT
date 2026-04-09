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

