# RANMT (RAN Measurement Tool)

RANMT is a resilient network measurement system designed to test Radio Access Network (RAN) latency, packet loss, and jitter under real-world mobile conditions. 

It is built utilizing **QUIC (RFC 9000)** and **Unreliable Datagrams (RFC 9221)** over IPv4 UDP to ensure accurate and lightweight traffic generation. The core protocol is implemented in **Rust** (using Cloudflare's `quiche`), powering both a command-line client and an exclusive **Android 15** mobile application.

## Key Features

- **QUIC-Based Protocol:** Utilizes QUIC with BBR congestion control. A single multiplexed bidirectional stream (Stream 0) is used for reliable JSON control messages (handshake, telemetry, and stats), while actual network profiling traffic flows over QUIC Unreliable Datagrams.
- **Resilient Mobile Execution:** Combines a Rust core library with native Android 15 APIs. It aggressively utilizes modern foreground services and wakelocks to guarantee the CPU and modem never sleep under OS battery optimizations during a test, preserving measurement integrity.
- **Seamless Reconnections:** The system elegantly handles connection drops (typical in cellular environments) by tearing down the transport layer while preserving the underlying measurement session on the server up to 24 hours.
- **Unified Rust Core:** The client logic (connection loop, traffic pacing, telemetry) is encapsulated in a single Rust library (`ranmt-client`), running natively via a CLI or bound to Kotlin on Android via Mozilla's **UniFFI**.
- **Server Analytics:** The Rust `ranmt-server` outputs exhaustive test data to disk in structured `JSONL` format for easy post-flight analysis.

## Architecture Overview

```text
┌──────────────────┐         QUIC over IPv4 UDP          ┌──────────────────┐
│      Client      │◄───────────────────────────────────►│      Server      │
│ (Rust lib + CLI) │                                     │  (Rust binary)   │
│                  │                                     │                  │
│ ┌──────────────┐ │                                     │ ┌──────────────┐ │
│ │ QUIC Event   │ │       Stream 0 (bidi, muxed)        │ │ QUIC Event   │ │
│ │ Loop (lib)   │ ├────────────────────────────────────►│ │ Loop (bin)   │ │
│ │              │ │       Datagrams: traffic            │ │              │ │
│ │ ┌──────────┐ │ │       ←─────────────────            │ │ ┌──────────┐ │ │
│ │ │ Telemetry│ │ │                                     │ │ │ Session  │ │ │
│ │ │ Buffer   │ │ │                                     │ │ │ Manager  │ │ │
│ │ └──────────┘ │ │                                     │ │ └──────────┘ │ │
│ │ ┌──────────┐ │ │                                     │ └──────────────┘ │
│ │ │ Traffic  │ │ │        ┌───────────────┐            │ ┌──────────────┐ │
│ │ │ Pacer    │ │ │        │ Session .jsonl│◄───────────┤ │ JSONL Writer │ │
│ │ └──────────┘ │ │        └───────────────┘            │ └──────────────┘ │
│ └──────────────┘ │                                     └──────────────────┘
└──────────────────┘
```

## Repository Structure

- `doc/`: Detailed specifications for Android, system architecture, rust implementation, and datatypes.
- `src/rust/shared/`: Common protocol types, JSON definitions, and codecs.
- `src/rust/server/`: The backend server binary (`ranmt-server`) that terminates connections and records telemetry.
- `src/rust/client/`: The core client crate containing the QUIC event loop. Exposes a command-line interface as well as a C-ABI via UniFFI (`ffi.rs`).
- `src/kotlin/`: The modern Android 15 mobile application integrating the Rust library via JNI.

## Getting Started

### 1. Start the Server

The server can run with a valid TLS certificate, or auto-generate a development certificate on the fly:

```bash
cd src/rust
cargo run -p ranmt-server --release -- --port 4433
```

_For production deployments:_
```bash
ranmt-server --port 4433 \
    --cert /etc/letsencrypt/live/example.com/fullchain.pem \
    --key /etc/letsencrypt/live/example.com/privkey.pem \
    --output-dir ./ranmt-sessions
```

### 2. Build the Android Client (Primary Client)

The primary client for RANMT is the exclusive **Android 15 application**. The CLI is purely used as a development stub.

Before compiling the Android App in Android Studio, you'll need `cargo-ndk` and `uniffi-bindgen` to compile the core Rust library and generate the Kotlin bindings into the correct JNI library path:

1. Target configuration and ABI builds:
```bash
cd src/rust
export ANDROID_NDK_HOME=/home/lorenzo/Android/Sdk/ndk/30.0.14904198
cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android

# Build native .so libraries for 64-bit ARM (Device) and x86_64 (Emulator)
cargo ndk -t arm64-v8a -o ../kotlin/app/src/main/jniLibs build -p ranmt-client --features ffi --release
cargo ndk -t x86_64 -o ../kotlin/app/src/main/jniLibs build -p ranmt-client --features ffi --release
```

2. Generate Kotlin FFI bindings:
```bash
cargo install uniffi --features cli
uniffi-bindgen generate \
    --library target/aarch64-linux-android/release/libranmt_client.so \
    --language kotlin \
    --out-dir ../kotlin/app/src/main/java
```

Once generated, you can open `src/kotlin` in **Android Studio** and build the mobile application.

### 3. Testing with the CLI Stub

For quick server tests without deploying to a phone, use the Rust CLI stub to emulate an Uplink (UL) or Downlink (DL) connection. Use `--insecure` when testing against a server with a development certificate:

```bash
cd src/rust
# Downlink test targeting 8 kbps for 30 seconds
cargo run -p ranmt-client --release -- 127.0.0.1 \
    --direction dl \
    --port 4433 \
    --bitrate 8000 \
    --duration 30 \
    --insecure
```

## Documentation

For a deep dive into the system requirements, implementation parameters, and wire protocol formats, please start with the documentation overviews:
- [Architecture Overview](doc/architecture/00-overview.md)
- [Protocol Outline](doc/protocol/protocol-00-overview.md)
- [Implementation Specifics](doc/implementation/00-overview.md)
- [Android App Spec](doc/android/00-overview.md)
