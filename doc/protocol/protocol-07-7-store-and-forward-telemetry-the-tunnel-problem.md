## 7. Store-and-Forward Telemetry (The Tunnel Problem)

### 7.1 Client-Side Buffer

- The client maintains a `VecDeque<ClientTelemetry>` buffer with a **max capacity of 10 000** entries.
- When full, the oldest entry is silently dropped (FIFO overflow).
- Telemetry generation (1-second ticks) continues even when the QUIC connection is down. Entries are pushed to the buffer.

### 7.2 Flush Protocol

Upon successful handshake acknowledgment, the client:

1. **Drains the backlog** — pops all entries from the `VecDeque` and transmits them on Stream 0 as regular `{"type":"telemetry",...}` messages.
2. The client SHOULD **flush in bursts** — send all backlog entries as fast as the QUIC flow control allows (one JSON per `\n`).
3. **After the backlog is fully drained**, the client enters the normal periodic 1-second telemetry loop.

### 7.3 No Telemetry Ack Needed

The client does not wait for server acknowledgment of telemetry. Fire-and-forget. If the server receives a malformed message, it logs a warning and continues.

---

