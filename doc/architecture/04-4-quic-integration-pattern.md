## 4. QUIC Integration Pattern

### 4.1 The Quiche Paradigm

`quiche` is a **state machine**, not an async networking library. It provides:

- `quiche::connect()` / `quiche::accept()` → create `quiche::Connection`
- `conn.send(out_buf)` → generates QUIC packets into your buffer (you send them)
- `conn.recv(in_buf)` → processes incoming QUIC packets (you feed them)
- `conn.timeout()` → returns `Option<Duration>` until next timer
- `conn.on_timeout()` → processes timer expiry
- `conn.stream_send(stream_id, data, is_final)` → send on stream
- `conn.stream_recv(stream_id)` → read from stream
- `conn.dgram_send(data)` → send unreliable datagram
- `conn.dgram_recv(buf)` → receive unreliable datagram

**You own the UDP socket.** `quiche` never touches the network.

### 4.2 Tokio Integration

| Quice concept                         | Tokio equivalent                                             |
| ------------------------------------- | ------------------------------------------------------------ |
| `conn.timeout()` → `Option<Duration>` | `tokio::time::sleep_until(deadline)`                         |
| Socket ready for read                 | `UdpSocket::recv_from()`                                     |
| Flush QUIC send buffer                | `UdpSocket::send_to()` (loop while `conn.send()` > 0)        |
| Stream data ready                     | Poll `conn.stream_recv()` — quiche buffers internally        |
| Non-blocking operation                | All quiche calls are synchronous CPU ops — no `await` needed |

### 4.3 Critical Gotcha: `conn.process_multiple_levels`

In quiche >= 0.20, `conn.process()` handles timer processing internally.
Use `conn.on_timeout()` when the external timer fires.
