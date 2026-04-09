## 3. Server Architecture

### 3.1 Component Breakdown

```text
Server (src/main.rs)
├── UDP socket: bound to 0.0.0.0:<port>
├── Connection Map: HashMap<ConnectionId, QuicConnection>
├── Session Registry: HashMap<SessionId, SessionState>
│   ├── SessionState {
│   │    session_id: Uuid,
│   │    direction: Direction,
│   │    bitrate_bps: u32,
│   │    jsonl_path: PathBuf,
│   │    jsonl_file: File (append mode),
│   │    datagram_tracker: LossJitterTracker,
│   └── Telemetry file: session_<uuid>.jsonl
│
├── Event Loop: single tokio loop handling all connections
│   ├── UDP recv → route to connection by DCID
│   ├── Connection timeout → on_timeout() → prune if closed
│   ├── Stats ticker per connection → send on Stream 0
│   ├── Traffic pacer per DL connection → send datagrams
│   └── Stream processing:
│       └── Stream 0: dispatch by JSON type → handshake / goodbye / telemetry / stats
```

### 3.2 Server Connection Lifecycle

```
1. UDP packet from unknown DCID → quiche::accept() → new connection
2. Read Stream 0 → parse Handshake → validate
3. If valid:
   a. Check: does session_id exist in SessionRegistry?
   
   CASE A — New session (first connection):
      a1. Create SessionState
      a2. Open session_<uuid>.jsonl (create + write)
   
   CASE B — Reconnect (existing session, dormant):
      a1. Tear down old QUIC connection resources
          (stop its stats ticker, traffic pacer, close conn)
      a2. Keep the existing SessionState (same JSONL file)
      a3. Mark SessionState as "active" with new connection
   
   b. Send HandshakeAck on Stream 0
   c. Send stats on Stream 0 (1s interval)
   d. If direction == "dl", start traffic pacer
4. If invalid:
   a. Send HandshakeAck { status: "error" }
   b. Close connection after 5s
5. During active session:
   a. Process incoming datagrams (UL test)
   b. Write telemetry to JSONL (Stream 0)
   c. Send stats on Stream 0
   d. Send datagrams (DL test)
6. On goodbye → close JSONL, remove from SessionRegistry
7. On idle timeout → mark SessionState "dormant" for 24h
   → if no reconnect within 24h, remove and close JSONL
```

### 3.3 Multi-Connection Handling

- The server uses a **single UDP socket** (all connections share `0.0.0.0:<port>`).
- Incoming packets are demultiplexed by their **Destination Connection ID (DCID)**.
- `quiche::accept()` returns a `Connection` object and the connection ID.
- A `HashMap<[u8; MAX_CONN_ID_LEN], Connection>` maps DCID → connection.
- A `HashMap<Uuid, SessionState>` maps session_id → session state (independent of QUIC connection).
- The server's single event loop iterates over all active connections.
- When a session reconnects: the new QUIC connection is created, the old one is torn down, and the SessionState is rebound to the new connection. The JSONL file handle is never closed during reconnect — it stays open for the 24h dormant window.
