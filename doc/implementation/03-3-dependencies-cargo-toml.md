## 3. Dependencies (Cargo.toml)

### 3.1 Workspace root

```toml
[workspace]
members = ["client", "server", "shared"]
resolver = "2"
```

### 3.2 Shared crate

```toml
[package]
name = "ranmt-shared"
version = "0.1.0"
edition = "2021"

[dependencies]
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1.8", features = ["serde", "v4"] }
```

### 3.3 Client crate

```toml
[package]
name = "ranmt-client"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "ranmt-client"
path = "src/bin/client.rs"

[lib]
name = "ranmt_client"
path = "src/lib.rs"
crate-type = ["lib", "cdylib"]

[dependencies]
ranmt-shared = { path = "../shared" }
quiche = "0.22"
tokio = { version = "1.38", features = ["full"] }
clap = { version = "4.5", features = ["derive"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
uuid = { version = "1.8", features = ["v4", "serde"] }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
getrandom = "0.3"
openssl = "0.10"
openssl-sys = "0.9"
```

### 3.4 Server crate

```toml
[package]
name = "ranmt-server"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "ranmt-server"
path = "src/main.rs"

[dependencies]
ranmt-shared = { path = "../shared" }
quiche = "0.22"
rcgen = "0.13"
tokio = { version = "1.38", features = ["full"] }
clap = { version = "4.5", features = ["derive"] }
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
uuid = { version = "1.8", features = ["serde"] }
getrandom = "0.3"
```

### 3.5 quiche Build Requirements

`quiche` is a Rust wrapper around BoringSSL (C). Building it requires:

- **cmake** >= 3.14
- **perl** (BoringSSL build uses `GenerateASM.pl` or similar)
- **Go** (BoringSSL requires Go 1.19+ for certain build steps)
- **GCC/G++** with C++14 support

On Debian/Ubuntu:
```
apt install cmake pkg-config golang-go gcc g++ perl
```

On Arch Linux:
```
pacman -S cmake pkgconf go gcc perl
```

> **Note:** quiche bundles BoringSSL as a git submodule, so `git clone` of the quiche repo (or `cargo build` with quiche as dependency) handles it automatically. The feature flags `boringss-vendored` (default) build BoringSSL from source. Setting `QUICHE_BSSL_SRC_PATH` or `QUICHE_NO_PKG_CONFIG` can override source paths.
