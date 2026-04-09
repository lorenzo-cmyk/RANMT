## 5. File Layout (Planned)

```text
RANMT/
├── doc/
│   ├── android/            # Android App documentation
│   ├── architecture/       # System architecture documentation
│   ├── implementation/     # Details about implementation
│   └── protocol/           # Protocol and Type definitions
└── src/
    └── rust/
        ├── Cargo.toml          # Workspace root
        ├── client/
        │   ├── Cargo.toml      # crate-type = ["lib", "cdylib"]
        │   └── src/
        │       ├── lib.rs      # Public API: run_client(), ClientConfig
        │       ├── ffi.rs      # UniFFI bindings
        │       └── bin/
        │           └── client.rs # CLI entry point
        ├── server/
        │   ├── Cargo.toml
        │   └── src/
        │       └── main.rs     # Server entry point, Event loop, Session management, Datagram recv/send
        └── shared/
            ├── Cargo.toml
            └── src/
                └── lib.rs      # Shared types: Handshake, Telemetry, Stats, etc.
```
