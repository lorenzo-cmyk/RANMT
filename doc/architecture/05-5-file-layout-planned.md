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
