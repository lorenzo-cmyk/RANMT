## 5. Stream 0 — Bidirectional Read/Write Contract

Both client and server read and write on the same bidirectional stream (Stream 0). All messages are JSON `\n`-terminated.

### 5.1 Read Loop Pattern

The reader maintains a `Vec<u8>` read buffer. On each invocation:

```rust
const STREAM_ID: u64 = 0;
const STREAM_TMP: usize = 8192;

/// Reads all available data from Stream 0, returns parsed messages.
/// Incomplete trailing bytes stay in rx_buf.
fn drain_stream(
    conn: &mut quiche::Connection,
    rx_buf: &mut Vec<u8>,
) -> Vec<WireMessage> {
    let mut msgs = Vec::new();
    loop {
        let mut tmp = [0u8; STREAM_TMP];
        match conn.stream_recv(STREAM_ID, &mut tmp) {
            Ok((n, _fin)) if n > 0 => {
                rx_buf.extend_from_slice(&tmp[..n]);
            }
            Ok(_) => break,
            Err(quiche::Error::Done) => break,
            Err(e) => {
                tracing::warn!(?e, "stream_recv error");
                break;
            }
        }
    }

    // Split by '\n', keep incomplete trailing bytes in rx_buf
    if let Some(pos) = rx_buf.iter().rposition(|&b| b == b'\n') {
        let complete = rx_buf.drain(..=pos).collect::<Vec<u8>>();
        let text = String::from_utf8_lossy(&complete);
        for line in text.split('\n').filter(|s| !s.is_empty()) {
            match serde_json::from_str::<WireMessage>(line) {
                Ok(msg) => msgs.push(msg),
                Err(e) => tracing::warn!(%e, "malformed JSON: {line}"),
            }
        }
    }
    msgs
}
```

### 5.2 Write Pattern — Send Buffer Full

When `conn.stream_send()` returns `quiche::Error::Done`, the QUIC stream send buffer is full.

**Strategy:**
1. **Do NOT drop the message.** Queue it.
2. **Drain queue first**, then send new messages.

```rust
use std::collections::VecDeque;

/// Non-blocking stream write with queuing.
fn stream_send_queued(
    conn: &mut quiche::Connection,
    queue: &mut VecDeque<Vec<u8>>,
    data: &[u8],
    is_final_for_stream: bool,
) -> Result<(), quiche::Error> {
    let payload = data.to_vec();

    // Try to flush the queue first
    while let Some(front) = queue.front() {
        match conn.stream_send(STREAM_ID, front, false) {
            Ok(_) => { queue.pop_front(); }
            Err(quiche::Error::Done) => break,
            Err(e) => {
                tracing::warn!(?e, "stream_send failed, dropping queued item");
                queue.pop_front();
            }
        }
    }

    // Now try the new message
    match conn.stream_send(STREAM_ID, &payload, is_final_for_stream) {
        Ok(_) => Ok(()),
        Err(quiche::Error::Done) => {
            queue.push_back(payload); // will retry next tick
            Ok(())
        }
        Err(e) => Err(e),
    }
}
```

### 5.3 `Goodbye` is Special — use `is_final`

When sending the `Goodbye` message, pass `is_final_for_stream = true` to signal QUIC stream-level FIN:

```rust
fn send_goodbye(conn: &mut quiche::Connection, reason: &GoodbyeReason) {
    let msg = serde_json::to_string(
        &WireMessage::Goodbye(Goodbye { reason: reason.clone() })
    ).unwrap();
    let _ = conn.stream_send(STREAM_ID, msg.as_bytes(), true); // FIN
}
```

### 5.4 Message Dispatch (on Receipt)

```rust
for msg in drain_stream(&mut conn, &mut rx_buf) {
    match msg {
        WireMessage::Handshake(h) =>       handle_handshake(h)?,
        WireMessage::HandshakeAck(a) =>    handle_ack(a),
        WireMessage::ServerStats(s) =>     display_stats(s),
        WireMessage::ClientTelemetry(t) => write_jsonl(t)?,
        WireMessage::Goodbye(reason) =>    handle_goodbye(reason),
    }
}
```

---

