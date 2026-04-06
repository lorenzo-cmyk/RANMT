# RANMT Architecture Overview

## 1. System Components

```
┌─────────────────┐         QUIC over IPv4 UDP          ┌──────────────────┐
│     Client       │◄────────────────────────────────────►│      Server       │
│  (Rust lib + CLI)│                                     │  (Rust binary)    │
│                  │                                     │                  │
│ ┌──────────────┐ │                                     │ ┌──────────────┐ │
│ │ QUIC Event   │ │       Stream 0 (bidi, muxed)        │ │ QUIC Event   │ │
│ │ Loop (lib)   │ ├────────────────────────────────────►│ │ Loop (bin)   │ │
│ │              │ │       Datagrams: traffic             │ │              │ │
│ │ ┌──────────┐ │ │       ←──────────────                │ │ ┌──────────┐ │ │
│ │ │ Telemetry│ │ │                                     │ │ │ Session  │ │ │
│ │ │ Buffer   │ │ │                                     │ │ │ Manager  │ │ │
│ │ └──────────┘ │ │                                     │ │ └──────────┘ │ │
│ │ ┌──────────┐ │ │                                     │ └──────────────┘ │
│ │ │ Traffic  │ │ │  ┌───────────────┐                  │ ┌──────────────┐ │
│ │ │ Pacer    │ │ │  │ Session .jsonl │                  │ │ JSONL Writer │ │
│ │ └──────────┘ │ │  └───────────────┘                  │ └──────────────┘ │
│ └──────────────┘ │                                     └──────────────────┘
└─────────────────┘
```

## 2. Client Architecture

### 2.1 Layered Structure

```
Client
├── lib.rs            # Core logic — reusable by CLI, future UniFFI/Android
│   ├── config        # Connection config (server addr, port, session_id, direction, bitrate)
│   ├── connection    # QUIC connection builder & reconnection loop
│   ├── event_loop    # Main quiche event loop (tokio select!)
│   ├── streams       # Stream 0 (bidi: handshake, stats in, telemetry out)
│   ├── datagrams     # Traffic pacer, datagram send/recv
│   ├── telemetry     # Telemetry generation, VecDeque buffer, flush protocol
│   └── codec         # JSON serialization/deserialization
│
├── bin/client.rs     # CLI entry point — clap args, logging, tokio runtime
└── Cargo.toml        # crate-type = ["lib", "cdylib"] (for future uniffi)
```

### 2.2 Client Event Loop (simplified)

The core client loop in `event_loop.rs`:

```
loop {
    select! {
        // QUIC timeout
        _ = sleep_until(timeout) => {
            conn.on_timeout();
            if conn.is_closed() { break; }
        }

        // UDP read → feed to quiche
        result = socket.recv_from(buf) => {
            let len = result?;
            let read = conn.recv(buf[..len], ...);
            if read > 0 { conn.process_timers(); }
        }

        // Handshake send (one-shot)
        _ = send_handshake, if !handshake_sent => { ... }

        // Telemetry tick (1s interval)
        _ = telemetry_tick.tick() => { ... }

        // Traffic pacing tick
        _ = traffic_tick.tick() => { ... }

        // Read Stream 0 — dispatch by JSON type
        _ = read_stream0() => {
            // if handshake_ack → activate
            // if server_stats → display
            // if telemetry → (server side) write JSONL
        }

        // Check for incoming datagrams
        _ = poll_datagrams() => { ... }
    }

    // Flush pending QUIC packets to UDP
    while conn.send(out_buf).is_ok() {
        socket.send_to(&out_buf[..n], server_addr);
    }

    // Update timeout
    timeout = conn.timeout();
}
```

### 2.3 Reconnection Strategy

```
outer loop:
    build_quic_connection()
    inner loop:
        run event_loop()
        if disconnected:
            log("Connection lost, waiting 2s...")
            sleep(2s)
            continue outer loop
        if user_abort:
            send goodbye
            break
```

### 2.4 Why `lib.rs` + `bin/`?

- `lib.rs` contains all core logic — connection, event loop, telemetry, traffic generation.
- `bin/client.rs` is a thin wrapper: parses CLI args, sets up tracing, calls `lib::run_client()`.
- This separation allows future compilation to `cdylib` for UniFFI, where `lib::run_client()` becomes a Kotlin-callable function.
- `std::process::exit` is **never** called in `lib.rs`. Errors are returned or logged.

---

## 3. Server Architecture

### 3.1 Component Breakdown

```
Server (bin/server.rs)
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

---

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

---

## 5. File Layout (Planned)

```
RANMT/
├── Cargo.toml              # Workspace root
├── doc/
│   ├── protocol.md           # Wire protocol specification
│   ├── architecture.md       # This file
│   ├── datatypes.md          # Rust struct definitions + wire formats
│   └── implementation-details.md  # Config, deps, CLI, build instructions
├── client/
│   ├── Cargo.toml          # crate-type = ["lib", "cdylib"]
│   ├── src/
│   │   ├── lib.rs          # Public API: run_client(), ClientConfig
│   │   ├── bin/
│   │   │   └── client.rs   # CLI entry point
│   │   ├── config.rs       # ClientConfig struct
│   │   ├── connection.rs   # QUIC connection builder, reconnection loop
│   │   ├── event_loop.rs   # Main tokio event loop
│   │   ├── datagrams.rs    # Traffic pacer, datagram send/recv, loss/jitter
│   │   ├── telemetry.rs    # Telemetry generator, VecDeque buffer, flush
│   │   └── codec.rs        # JSON serialization helpers
│
├── server/
│   ├── Cargo.toml
│   └── src/
│       ├── main.rs         # Server entry point
│       ├── config.rs       # Server config
│       ├── connection.rs   # Server-side QUIC connection management
│       ├── event_loop.rs   # Server event loop
│       ├── session.rs      # SessionState, JSONL management
│       ├── datagrams.rs    # Datagram recv, loss/jitter calc, traffic sender
│       └── stats.rs        # Server stats collection & sending
│
└── shared/
    └── src/
        └── lib.rs          # Shared types: Handshake, Telemetry, Stats, etc.
```
