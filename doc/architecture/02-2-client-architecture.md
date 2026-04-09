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

