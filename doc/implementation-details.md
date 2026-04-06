# RANMT Implementation Details

> Concrete configuration, dependency, and usage details. The programmer can start coding from this file without guessing any parameters.

---

## 1. `quiche::Config` — Exact Parameters

Every `quiche::Connection` (client and server) must be configured with the same parameters. Code below is the exact sequence of `set_*` calls.

```rust
use quiche::{Config, CongestionControlAlgorithm, ProtocolVersion};

fn make_quic_config() -> Result<Config, quiche::Error> {
    let mut config = Config::new(ProtocolVersion::V1)?;

    // --- BBR (cellular-friendly) ---
    config.set_cc_algorithm(CongestionControlAlgorithm::BBR)?;

    // --- Datagrams (RFC 9221) ---
    config.enable_dgram_recv(true);
    config.enable_dgram_send(true);
    config.set_max_recv_udp_payload_size(1200);
    config.set_max_send_udp_payload_size(1200);

    // --- Idle timeout (30 s) ---
    config.set_max_idle_timeout(30_000_000); // microseconds

    // --- Stream limits ---
    config.set_initial_max_streams_bidi(4);
    config.set_initial_max_streams_uni(4);

    // --- Flow control ---
    config.set_initial_max_stream_data_bidi_local(5_242_880);   // 5 MiB
    config.set_initial_max_stream_data_bidi_remote(5_242_880);  // 5 MiB
    config.set_initial_max_stream_data_uni(524_288);            // 512 KiB
    config.set_initial_max_data(10_485_760);                    // 10 MiB

    // --- ALPN ---
    config.set_application_protos(&[b"ranmt/0.1"])?;

    Ok(config)
}
```

### Parameter Rationale

| Parameter | Why this value |
|-----------|---------------|
| `max_idle_timeout = 30 s` | Detects silent disconnects. Client reconnects after 2 s, so 30 s gives margin. |
| `max_stream_data = 5 MiB (bidi)` | Telemetry backlog flush can be ~2 MiB (10 000 entries × ~200 bytes). 5 MiB avoids flow-control stalls. |
| `max_data = 10 MiB` | Connection-level ceiling. At 8 kbps, 10 MiB ≈ 10 000 seconds of data. |
| `max_send/recv_udp_payload = 1200` | Matches `MAX_DGRAM_SIZE`, avoids IP fragmentation. |

---

## 2. QUIC TLS Setup

### 2.0 `ClientConfig` — Client Configuration Struct

```rust
use ranmt_shared::Direction;
use std::path::PathBuf;

#[derive(clap::Parser)]
pub struct ClientConfig {
    /// Server FQDN or IPv4 address
    pub server_addr: String,

    /// Server FQDN for SNI (defaults to server_addr if not set)
    #[arg(long)]
    pub server_fqdn: Option<String>,

    /// Server QUIC port
    #[arg(short = 'p', long, default_value = "4433")]
    pub port: u16,

    /// Test direction
    #[arg(short = 'd', long)]
    pub direction: Direction,

    /// Target bitrate in bps
    #[arg(short = 'b', long, default_value = "8000")]
    pub bitrate_bps: u32,

    /// Test duration in seconds (0 = infinite)
    #[arg(short = 't', long, default_value = "0")]
    pub duration: u64,

    /// Disable certificate verification (dev mode)
    #[arg(long)]
    pub insecure: bool,

    /// SHA-256 fingerprint to pin (skips CA verification)
    #[arg(long)]
    pub cert_fingerprint: Option<String>,

    /// RNG seed for mock telemetry
    #[arg(long, default_value = "42")]
    pub seed: u64,
}
```

> **`server_fqdn`:** If provided as a separate CLI arg, use it for SNI. If `None`, fall back to `server_addr` (works for literal IPs in dev mode with `--insecure`).

impl ClientConfig {
    /// Resolve the SNI hostname — use explicit FQDN or fall back to server_addr.
    pub fn sni_hostname(&self) -> &str {
        self.server_fqdn.as_deref().unwrap_or(&self.server_addr)
    }
}

### 2.1 Client Connection Builder

```rust
use quiche::Connection;
use openssl::ssl::SslVerifyMode;

pub fn build_client_connection(
    quic_cfg: &mut Config,
    server_fqdn: &str,
    verify_insecure: bool,
    cert_fingerprint: Option<&str>,  // SHA-256 hex, e.g. "A1:B2:..."
) -> Result<Connection, quiche::Error> {
    let mut scid = [0u8; 16];
    getrandom::getrandom(&mut scid).unwrap();
    let mut conn = quiche::connect(Some(server_fqdn), &scid, quic_cfg)?;

    if verify_insecure {
        conn.set_verify(SslVerifyMode::empty());
    } else if let Some(fp) = cert_fingerprint {
        // Pin to a specific certificate fingerprint
        conn.set_verify_callback(
            openssl::ssl::SslVerifyMode::PEER,
            move |ok, ctx| -> bool {
                if !ok { return false; }
                let expected = fp.replace(":", "").to_lowercase();
                let cert = ctx.current_cert().expect("peer cert missing");
                let digest = cert
                    .digest(openssl::hash::MessageDigest::sha256())
                    .unwrap();
                let hex = digest
                    .iter()
                    .map(|b| format!("{:02x}", b))
                    .collect::<String>();
                hex == expected
            },
        );
    }

    Ok(conn)
}
```

**Critical:** `quiche::connect()` requires `Some(server_fqdn)` for SNI. The server uses SNI to select the correct certificate.

> **openssl dependency note:** `set_verify` takes `SslVerifyMode` from the `openssl` crate. quiche links against its own vendored OpenSSL, so `openssl 0.10` must match quiche's internal version. If version conflicts occur, use `quiche::ffi::SSL_VERIFY_NONE` (a raw `u32` constant) instead and cast it via `std::mem::transmute`. In practice, quiche's Cargo.toml pins a specific openssl version — just pin the same in your client's Cargo.toml.

### 2.2 Server TLS Config

quiche `0.22` provides `load_cert_chain_from_pem` and `load_priv_key_from_pem` on `Config` directly.

```rust
pub fn load_server_tls(
    config: &mut Config,
    cert_pem: &[u8],  // in-memory PEM bytes
    key_pem: &[u8],   // in-memory PEM bytes
) -> Result<(), quiche::Error> {
    config.load_cert_chain_from_pem(cert_pem)?;
    config.load_priv_key_from_pem(key_pem)?;
    Ok(())
}
```

### 2.3 Dev Certificate (rcgen)

When no cert/key files are provided, generate in memory:

```rust
use rcgen::{CertificateParams, DistinguishedName,
    DnType, KeyPair, PKCS_ECDSA_P256_SHA256, IsCa};

pub fn generate_dev_cert() -> (Vec<u8>, Vec<u8>) {
    let mut params = CertificateParams::new(vec!["ranmt-dev.local".into()])
        .unwrap();
    params.distinguished_name = DistinguishedName::new();
    params.distinguished_name.push(DnType::CommonName, "ranmt-dev.local");
    params.is_ca = IsCa::ExplicitNoCa;
    let key_pair = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).unwrap();
    let cert = params.self_signed(&key_pair).unwrap();
    (
        cert.serialize_pem().unwrap().into_bytes(),
        cert.serialize_private_key_pem().into_bytes(),
    )
}
```

- **Key algorithm:** ECDSA P-256 (required for TLS 1.3).
- **SAN:** `DNS: ranmt-dev.local`.
- **Client dev mode:** `conn.set_verify(&openssl::ssl::SslVerifyMode::NONE)`.

---

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

---

## 4. CLI Interface

### 4.1 Server

```
Usage: ranmt-server [OPTIONS] --port <PORT>

Options:
    --bind-addr <ADDR>       Address to bind UDP socket to
                             [default: 0.0.0.0]
    --port <PORT>            Port to bind UDP socket to
    --cert <PATH>            Path to TLS certificate (PEM)
    --key <PATH>             Path to TLS private key (PEM)
    --output-dir <DIR>       Directory to write session jsonl files
                             [default: ./sessions]
    -v, --verbose            Enable debug logging
    -h, --help               Print help
```

Validation rules:
- `--port`: **1024-65535**. Required. No default.
- `--cert` + `--key`: If BOTH provided, load from disk. If NEITHER provided, generate dev cert with `rcgen`. If only one, error.
- `--output-dir`: Created on startup if not exists (`fs::create_dir_all`).

### 4.2 Client

```
Usage: ranmt-client <SERVER> [OPTIONS] --direction <DIRECTION>

Arguments:
    <SERVER>                  Server FQDN or IPv4 address

Options:
    -d, --direction <DIR>        Test direction: "dl" or "ul" [required]
    -p, --port <PORT>            Server QUIC port [default: 4433]
    -b, --bitrate <BPS>          Target bitrate in bps [default: 8000]
    -t, --duration <SEC>         Test duration in seconds
                                 [default: 0 = run until Ctrl+C]
    --cert-fingerprint <HEX>     Optional SHA-256 fingerprint to pin
                                 (skips CA verification)
    --insecure                   Disable certificate verification
                                 (dev mode only)
    --seed <SEED>                RNG seed for mock telemetry
                                 [default: 42]
    -v, --verbose                Enable debug logging
    -h, --help                   Print help
```

Validation rules:
- `<SERVER>`: Must resolve to at least one A (IPv4) record. AAAA records ignored.
- `--direction`: Required. Must be `"dl"` or `"ul"`.
- `--bitrate`: **1 000 - 1 000 000** (1 kbps - 1 Mbps).
- `--duration`: **0** = infinite. **> 0** = test stops after N seconds and sends `goodbye`.
- `--cert-fingerprint` vs `--insecure`: Mutually exclusive. If neither provided, use system CA trust store.

### 4.3 Example Usage

```bash
# Server with dev cert
ranmt-server --port 4433

# Server with production cert
ranmt-server --port 4433 \
    --cert /etc/letsencrypt/live/rantest.example.com/fullchain.pem \
    --key /etc/letsencrypt/live/rantest.example.com/privkey.pem \
    --output-dir /data/ranmt-sessions

# DL test - 30 seconds, mock GPS, default 8 kbps
ranmt-client rantest.example.com \
    --direction dl --port 4433 --duration 30 --insecure

# Reproducible mock data (same seed = same telemetry)
ranmt-client 10.0.0.50 --direction dl --seed 12345
```

---

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

## 6. UDP Socket I/O Pattern

### 6.1 Flush QUIC to UDP (common to both client and server)

After every QUIC operation that might produce packets (connect, accept, `stream_send`, `on_timeout`, dgram), **immediately** flush to the socket:

```rust
const MAX_QUIC_PACKET: usize = 1500;

async fn flush_quic(
    conn: &mut quiche::Connection,
    socket: &tokio::net::UdpSocket,
    peer: std::net::SocketAddr,
) -> std::io::Result<()> {
    let mut buf = [0u8; MAX_QUIC_PACKET];
    loop {
        let write_len = match conn.send(&mut buf) {
            Ok(n) => n,
            Err(quiche::Error::Done) => return Ok(()),
            Err(e) => {
                tracing::warn!(?e, "conn.send() returned error");
                return Err(std::io::Error::other(e.to_string()));
            }
        };
        if let Err(e) = socket.send_to(&buf[..write_len], peer).await {
            tracing::warn!(?e, "send_to failed");
        }
    }
}
```

> **Why 1500?** With `max_send_udp_payload_size = 1200`, quiche's packets stay well within standard MTU. 1500 is safe for local/LAN.

### 6.2 UDP Bind/Connect

**Client** — binds local UDP socket to an ephemeral port:
```rust
let socket = tokio::net::UdpSocket::bind("0.0.0.0:0").await?;
socket.connect(peer_addr).await?; // connect for routing
```

**Server** — binds to explicit port:
```rust
let socket = tokio::net::UdpSocket::bind(
    &format!("0.0.0.0:{port}")
).await?;
```

---

## 7. Mock Telemetry Generator (Exact Formulas)

All mock data is deterministic given a `seed` (from CLI `--seed`) and elapsed seconds `t` (monotonic clock since test start).

```rust
use std::f64::consts::TAU;
use std::time::Instant;

pub struct MockTelemetry {
    test_start: Instant,
    seq_num: u64,
}

impl MockTelemetry {
    pub fn new(test_start: Instant) -> Self {
        Self { test_start, seq_num: 0 }
    }

    pub fn generate(&mut self) -> ClientTelemetry {
        let phase = self.test_start.elapsed().as_secs_f64();
        let seq = self.seq_num;
        self.seq_num += 1;

        let lat = 41.9028 + 0.001 * (phase * 0.5).sin();
        let lon = 12.4964 + 0.001 * (phase * 0.3 + 1.0).sin();

        let speed = 60.0 + 60.0 * (phase * TAU / 60.0).sin();

        // Network type cycles every 30 s
        let cycle_30 = (phase / 30.0).floor() as i64;
        let network_type = match (cycle_30 % 4).abs() {
            0 => NetworkType::Lte,
            1 => NetworkType::FiveG,
            2 => NetworkType::ThreeG,
            3 => NetworkType::Lte,
            _ => NetworkType::Unknown,
        };

        let cell_id   = 12345 + (phase / 30.0) as u32;
        let pci       = (150 + (phase / 30.0) as u16) % 504;  // range 0-503
        let earfcn    = 1850 + (phase / 30.0) as u32;

        let rsrp = -100.0 + 20.0 * (phase * TAU / 45.0).sin();    // -120 to -80
        let rsrq = -11.5 + 8.5 * (phase * TAU / 45.0 + 0.5).sin(); // -20 to -3
        let sinr = 10.0 + 15.0 * (phase * TAU / 50.0 + 1.2).sin(); // -5 to 25

        ClientTelemetry {
            seq_num: seq,
            timestamp_ms: current_epoch_ms(),
            lat, lon, speed, network_type,
            cell_id, pci, earfcn, rsrp, rsrq, sinr,
        }
    }
}
```

### Oscillation Table

| Field    | Center   | Amplitude | Period | Phase offset |
|----------|----------|-----------|--------|-------------|
| lat      | 41.9028  | 0.001     | ~12.57s| 0.0         |
| lon      | 12.4964  | 0.001     | ~20.94s| 1.0         |
| speed    | 60.0     | 60.0      | 60 s   | 0.0         |
| rsrp     | -100.0   | 20.0      | 45 s   | 0.0         |
| rsrq     | -11.5    | 8.5       | 45 s   | 0.5*TAU     |
| sinr     | 10.0     | 15.0      | 50 s   | 1.2         |

### Reproducibility

The test uses `Instant::now()` for `t_secs` (monotonic clock), NOT `SystemTime`.
- Same seed → same telemetry values relative to test start.
- `SystemTime` wall clock is only used for `timestamp_ms` (which must be real).
- Note: `--seed` currently does not feed into a PRNG; all oscillations are deterministic `sin()` functions. The `--seed` parameter is reserved for future PRNG-based mock data.

---

## 8. Client Event Loop — Complete Structure

Quiche is a synchronous state machine, so the correct pattern is **not** to use many `select!` branches (which compete for the same `select!` arbitration). Instead, use one `select!` per iteration with a single `sleep` (QUIC timeout) vs. the UDP socket. After either fires, **synchronously process everything**: drain UDP, drain stream, send telemetry, send datagrams, flush.

```rust
/// Drains the telemetry backlog buffer over Stream 0.
/// Must be called after handshake succeeds.
/// Returns `true` if backlog is fully drained, `false` if flow-controlled (retry later).
fn drain_backlog(
    conn: &mut quiche::Connection,
    backlog: &mut VecDeque<String>,
) -> bool {
    while let Some(msg) = backlog.front() {
        // Try to send, including the \n terminator
        let mut payload = msg.as_bytes().to_vec();
        payload.push(b'\n');
        match conn.stream_send(STREAM_ID, &payload, false) {
            Ok(_) => {
                backlog.pop_front();
            }
            Err(quiche::Error::Done) => {
                return false;  // flow-controlled, retry next tick
            }
            Err(e) => {
                tracing::warn!(?e, "backlog send failed, dropping");
                backlog.pop_front();
            }
        }
    }
    true
}

pub async fn run_client(config: ClientConfig) -> Result<()> {
    let session_id = Uuid::new_v4();

    loop {
        // === OUTER LOOP: Reconnection ===
        tracing::info!("Connecting to {}...", config.server_addr);

        // 1. Resolve DNS (A records only) + bind UDP
        let peer_addr = resolve_ipv4(&config.server_addr, config.port)?;
        let socket = tokio::net::UdpSocket::bind("0.0.0.0:0").await?;

        // 2. Build QUIC connection
        let mut quic_cfg = make_quic_config()?;
        let fp = config.cert_fingerprint.as_deref();
        let mut conn = build_client_connection(
            &mut quic_cfg,
            config.sni_hostname(),
            config.insecure,
            fp,
        )?;

        // 3. Initialize state
        let mut rx_buf = Vec::new();
        let mut udp_buf = [0u8; MAX_QUIC_PACKET];
        let mut telemetry_queue = VecDeque::new();
        let mut telemetry_backlog_drained = false;
        let mut handshake_sent = false;
        let mut handshake_done = false;

        let test_start = Instant::now();
        let mut mock_gen = MockTelemetry::new(test_start);

        // Telemetry timer (1 Hz)
        let mut telemetry_interval =
            tokio::time::interval(Duration::from_millis(TELEMETRY_INTERVAL_MS));
        telemetry_interval.set_missed_tick_behavior(
            tokio::time::MissedTickBehavior::Delay);
        telemetry_interval.tick().await;  // skip first immediate tick

        // Traffic pacer (UL only — server is traffic source for DL)
        let mut pacer = TrafficPacer::new(config.bitrate_bps);
        let mut traffic_interval =
            tokio::time::interval(pacer.interval());
        traffic_interval.set_missed_tick_behavior(
            tokio::time::MissedTickBehavior::Delay);
        traffic_interval.tick().await;

        // Loss/jitter tracker (receiver side)
        let mut tracker = LossJitterTracker::new();

        // === INNER LOOP ===
        'inner: loop {
            // --- Phase 1: Wait for something to happen ---
            let timeout = conn.timeout().unwrap_or(Duration::from_secs(5));

            // If there's no QUIC timeout and no pending work,
            // we still need to wait for the socket to be readable
            // or for the next periodic tick.
            let sleep_future = tokio::time::sleep(timeout);
            tokio::pin!(sleep_future);

            tokio::select! {
                _ = &mut sleep_future => {
                    // QUIC timer fired — process it
                    conn.on_timeout();
                    if conn.is_closed() {
                        tracing::warn!("QUIC connection closed (timeout)");
                        break 'inner;
                    }
                }

                result = socket.recv_from(&mut udp_buf) => {
                    let (len, src) = match result {
                        Ok(v) => v,
                        Err(e) => {
                            tracing::warn!(?e, "UDP recv error");
                            break 'inner;
                        }
                    };
                    let local_addr = socket.local_addr()?;
                    let recv_info = quiche::RecvInfo {
                        from: src.to_std(),
                        to: local_addr.to_std(),
                        at: std::time::Instant::now(),
                    };
                    if conn.recv(&mut udp_buf[..len], recv_info).is_err() {
                        tracing::warn!("recv error, reconnecting");
                        break 'inner;
                    }
                }
            }

            // --- Phase 2: Synchronously process everything ---

            // 4a. Read Stream 0 messages
            let msgs = drain_stream(&mut conn, &mut rx_buf);
            for msg in msgs {
                match msg {
                    WireMessage::HandshakeAck(a)
                        if a.status == HandshakeStatus::Ok =>
                    {
                        if !handshake_done {
                            handshake_done = true;
                            telemetry_backlog_drained = drain_backlog(&mut conn, &mut telemetry_queue);
                        }
                    }
                    WireMessage::ServerStats(s) => {
                        display_stats(&s);
                    }
                    _ => {}
                }
            }

            // 4b. Telemetry tick (1 Hz, always generates)
            if telemetry_interval.tick().now_or_never().is_some() {
                let tel = mock_gen.generate();
                let json = serde_json::to_string(
                    &WireMessage::ClientTelemetry(tel)).unwrap();

                if handshake_done && conn.is_established() {
                    // Send directly (quiche will buffer)
                    let _ = conn.stream_send(STREAM_ID, json.as_bytes(), false);
                    let _ = conn.stream_send(STREAM_ID, b"\n", false);
                } else {
                    // Buffer (tunnel scenario)
                    if telemetry_queue.len() < TELEMETRY_BUFFER_CAP {
                        telemetry_queue.push_back(json);
                    }
                }
            }

            // 4c. Process incoming datagrams (DL test)
            if config.direction == Direction::Dl {
                let mut dgram_buf = vec![0u8; MAX_DGRAM_SIZE];
                while let Ok(payload) = conn.dgram_recv(&mut dgram_buf) {
                    if let Some((seq, send_ts)) =
                        decode_traffic_payload(&payload)
                    {
                        let arrival = current_epoch_ms();
                        let (lost, jitter) =
                            tracker.on_datagram(seq, send_ts, arrival);
                        display_loss(seq, lost, jitter,
                            &tracker.loss_rate(),
                            tracker.jitter_ewma());
                    }
                }
            }

            // 4d. Send handshake (if established and not sent)
            if !handshake_sent && conn.is_established() {
                let hs = WireMessage::Handshake(Handshake {
                    session_id,
                    direction: config.direction,
                    bitrate_bps: config.bitrate_bps,
                    client_version: "0.1.0".into(),
                });
                let json = serde_json::to_string(&hs).unwrap();
                let _ = conn.stream_send(STREAM_ID, json.as_bytes(), false);
                let _ = conn.stream_send(STREAM_ID, b"\n", false);
                handshake_sent = true;
            }

            // 4e. Traffic pacer (DL = we receive, UL = we transmit)
            if config.direction == Direction::Ul
                && traffic_interval.tick().now_or_never().is_some()
                && handshake_done
                && conn.is_established()
            {
                let (seq, payload) = pacer.next_payload();
                let _ = conn.dgram_send(&payload);
                // seq_num persists across reconnects in outer scope
            }

            // 4f. Drain telemetry backlog after reconnect
            if handshake_done && !telemetry_backlog_drained
                && conn.is_established()
            {
                telemetry_backlog_drained = drain_backlog(
                    &mut conn, &mut telemetry_queue);
            }

            // 4g. Check for test duration expiry
            if config.duration > 0
                && test_start.elapsed() >= Duration::from_secs(config.duration)
            {
                send_goodbye(&mut conn, &GoodbyeReason::TestComplete);
                break 'inner;
            }

            // 4h. Flush all accumulated QUIC packets to UDP
            flush_quic(&mut conn, &socket, peer_addr).await?;

            // 4i. Check for closed connection
            if conn.is_closed() {
                tracing::info!("Connection lost");
                break 'inner;
            }
        }

        // === DISCONNECTED ===
        tracing::info!("Disconnected, reconnecting in 2s...");
        tokio::time::sleep(Duration::from_millis(RECONNECT_DELAY_MS)).await;
    }
}
```

### Key Design Pattern Explained

The `select!` only has **two branches**: the QUIC timeout timer and the UDP socket. Whichever wins, the **same synchronous processing block** runs after it. This avoids the "phantom ready branches" problem of multi-branch `select!` with `async {}` stubs (which resolve immediately and win every time).

- `tick().now_or_never()` polls a tokio interval without yielding — returns `Some(Instant)` if the tick has fired, `None` if not. This is how we multiplex multiple tickers in synchronous code.
- All quiche calls (`stream_recv`, `stream_send`, `dgram_recv`, `dgram_send`, `on_timeout`) are synchronous — they return immediately.

---

## 9. Server Event Loop — Complete Structure

### 9.0 Server Config

```rust
use std::path::PathBuf;

#[derive(clap::Parser)]
pub struct ServerConfig {
    #[arg(long, default_value = "0.0.0.0")]
    pub bind_addr: String,

    #[arg(long)]
    pub port: u16,

    #[arg(long)]
    pub cert: Option<PathBuf>,

    #[arg(long)]
    pub key: Option<PathBuf>,

    #[arg(long, default_value = "./sessions")]
    pub output_dir: PathBuf,
}
```

### 9.0.1 `load_or_generate_cert()` — Dev or Production TLS

```rust
/// Load cert/key from disk, or generate dev cert if neither provided.
fn load_or_generate_cert(
    config: &ServerConfig,
) -> Result<(Vec<u8>, Vec<u8>), Box<dyn std::error::Error>> {
    match (&config.cert, &config.key) {
        (Some(cert_path), Some(key_path)) => {
            let cert = std::fs::read(cert_path)?;
            let key = std::fs::read(key_path)?;
            Ok((cert, key))
        }
        (None, None) => {
            Ok(generate_dev_cert())
        }
        _ => Err("both --cert and --key must be provided, or neither".into()),
    }
}
```

### 9.1 Connection Types

```rust
use std::collections::HashMap;

struct ActiveConn {
    conn: quiche::Connection,
    rx_buf: Vec<u8>,
    peer: std::net::SocketAddr,
    session_id: Option<Uuid>,   // set after handshake
    direction: Option<Direction>,
    bitrate: Option<u32>,
    /// Local timers per connection
    stats_interval: tokio::time::Interval,
    /// Server-side traffic pacer (for DL tests). None for UL tests.
    server_traffic_state: Option<ServerTrafficState>,
}

/// Server-side traffic pacer state (exists only for DL connections).
struct ServerTrafficState {
    interval: tokio::time::Interval,
    next_seq: u64,
}
```

### 9.2 Full Server Loop

```rust
pub async fn run_server(config: ServerConfig) -> Result<()> {
    // 1. Load TLS
    let mut quic_cfg = make_quic_config()?;
    let (cert, key) = load_or_generate_cert(&config)?;
    load_server_tls(&mut quic_cfg, &cert, &key)?;

    // 2. Bind UDP socket (IPv4 only)
    let socket = tokio::net::UdpSocket::bind(
        &format!("{}:{}", config.bind_addr, config.port)
    ).await?;

    let mut active: HashMap<[u8; 16], ActiveConn> = HashMap::new();
    let mut sessions: HashMap<Uuid, SessionState> = HashMap::new();
    let mut udp_buf = vec![0u8; MAX_QUIC_PACKET];

    loop {
        // === Receive next UDP datagram ===
        let (len, peer) = match socket.recv_from(&mut udp_buf).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(?e, "UDP recv error");
                tokio::time::sleep(Duration::from_millis(100)).await;
                continue;
            }
        };

        // === Accept new connections or route to existing ===
        let mut local_cid = [0u8; 16];
        let recv_info = quiche::RecvInfo {
            from: peer.to_std(),
            to: socket.local_addr().unwrap().to_std(),
            at: std::time::Instant::now(),
        };

        // Check if there's an active connection for this peer.
        // If the existing connection is closed, mark its session dormant
        // and remove it — this allows a fresh handshake on reconnect.
        let stale_peer: Vec<[u8; 16]> = active.iter()
            .filter(|(scid, e)| e.peer == peer && e.conn.is_closed())
            .map(|(scid, _)| *scid)
            .collect();
        for scid in &stale_peer {
            if let Some(entry) = active.remove(scid) {
                if let Some(sid) = entry.session_id {
                    if let Some(sess) = sessions.get_mut(&sid) {
                        sess.mark_dormant();
                        tracing::info!("Session {sid} marked dormant (conn recycled)");
                    }
                }
            }
        }

        // Detect initial client packet (no active connection for this peer)
        // vs ongoing QUIC traffic. Attempt accept if no active connection exists.
        let has_active = active.values()
            .any(|e| e.peer == peer && !e.conn.is_closed());

        if !has_active {
            getrandom::getrandom(&mut local_cid).unwrap();
            match quiche::accept(&local_cid, None, &mut quic_cfg) {
                Ok(mut conn) => {
                    let stats_interval = tokio::time::interval(
                        Duration::from_millis(STATS_INTERVAL_MS));
                    active.insert(local_cid, ActiveConn {
                        conn,
                        rx_buf: Vec::new(),
                        peer,
                        session_id: None,
                        direction: None,
                        bitrate: None,
                        stats_interval,
                        server_traffic_state: None,
                    });
                    tracing::info!(?peer, ?local_cid, "new connection");
                }
                Err(e) => {
                    tracing::debug!(?e, "accept failed (may be retransmit)");
                    continue;
                }
            }
        }

        // === Feed UDP data to all matching connections ===
        let mut to_remove = Vec::new();
        for (scid, entry) in active.iter_mut() {
            // Try feeding to this connection (match by peer is sufficient)
            if !entry.conn.is_closed() && entry.peer == peer {
                if entry.conn.recv(&mut udp_buf[..len], recv_info).is_err() {
                    tracing::warn!(?peer, "recv error, marking for removal");
                    to_remove.push(*scid);
                }
                // Process timers after feeding data
                entry.conn.process_multiple_levels(10);
            }
        }

        // === Per-connection: periodic tasks (stats + traffic pacer) ===
        for (scid, entry) in active.iter_mut() {
            if entry.conn.is_closed() {
                to_remove.push(*scid);
                continue;
            }

            // Process QUIC timeout per connection
            if let Some(timeout) = entry.conn.timeout() {
                if timeout.is_zero() {
                    entry.conn.on_timeout();
                    if entry.conn.is_closed() {
                        to_remove.push(*scid);
                        continue;
                    }
                }
            }

            // Send server stats on interval
            if entry.session_id.is_some()
                && entry.stats_interval.tick().now_or_never().is_some()
            {
                let stats = ServerStats {
                    timestamp_ms: current_epoch_ms(),
                    quic_stats: extract_quic_stats(&entry.conn),
                };
                let msg = serde_json::to_string(
                    &WireMessage::ServerStats(stats)).unwrap();
                let _ = entry.conn.stream_send(0, msg.as_bytes(), false);
                let _ = entry.conn.stream_send(0, b"\n", false);
            }

            // Traffic pacer (DL only — server sends dummy traffic)
            if entry.direction == Some(Direction::Dl)
                && entry.server_traffic_state.is_none()
                && entry.conn.is_established()
            {
                // Initialize on first tick after handshake
                let interval = calc_traffic_interval(
                    entry.bitrate.unwrap_or(DEFAULT_BITRATE_BPS));
                entry.server_traffic_state = Some(ServerTrafficState {
                    interval: tokio::time::interval(interval),
                    next_seq: 0,
                });
            }
            if entry.server_traffic_state.is_some()
                && entry.conn.is_established()
            {
                let sts = entry.server_traffic_state.as_mut().unwrap();
                if sts.interval.tick().now_or_never().is_some() {
                    let mut payload = [0u8; MAX_DGRAM_SIZE];
                    encode_traffic_payload(
                        sts.next_seq, current_epoch_ms(), &mut payload);
                    let _ = entry.conn.dgram_send(&payload);
                    sts.next_seq += 1;
                }
            }
        }

        // === Process Stream 0 messages + Flush on ALL connections ===
        let output_dir = config.output_dir.clone();

        for (scid, entry) in active.iter_mut() {
            // Process Stream 0
            process_stream_messages(entry, &mut sessions, &output_dir);

            // Process UL datagrams
            if let Some(sid) = entry.session_id {
                if let Some(sess) = sessions.get_mut(&sid) {
                    process_datagrams(entry, sess);
                }
            }

            // Flush QUIC → UDP
            loop {
                let mut buf = [0u8; MAX_QUIC_PACKET];
                match entry.conn.send(&mut buf) {
                    Ok(n) => {
                        let _ = socket.send_to(&buf[..n], entry.peer).await;
                    }
                    Err(quiche::Error::Done) => break,
                    Err(e) => {
                        tracing::warn!(?e, "conn.send error");
                        break;
                    }
                }
            }

            if entry.conn.is_closed() {
                to_remove.push(*scid);
            }
        }

        // Clean up dead connections
        for scid in to_remove {
            if let Some(entry) = active.remove(&scid) {
                if let Some(sid) = entry.session_id {
                    if let Some(sess) = sessions.get_mut(&sid) {
                        tracing::info!(
                            "Session {sid} conn dropped, marking dormant"
                        );
                        // SessionState stays alive for 24h dormant window
                        sess.mark_dormant();
                    }
                } else {
                    tracing::info!("Unknown connection dropped");
                }
            }
        }

        // === Prune dormant sessions older than 24h ===
        sessions.retain(|sid, sess| {
            if sess.is_dormant_expiring() {
                sess.close_jsonl();
                tracing::info!("Session {sid} expired, removing");
                false
            } else {
                true
            }
        });
    }
}
```

### 9.3 Stream Processing (same as before, with Goodbye handling fixed)

```rust
/// Process Stream 0 messages for one connection.
fn process_stream_messages(
    entry: &mut ActiveConn,
    sessions: &mut HashMap<Uuid, SessionState>,
    output_dir: &Path,
) {
    let stream_id = 0u64;
    loop {
        let mut tmp = [0u8; 8192];
        match entry.conn.stream_recv(stream_id, &mut tmp) {
            Ok((n, _fin)) if n > 0 => {
                entry.rx_buf.extend_from_slice(&tmp[..n]);
            }
            Ok(_) => break,
            Err(quiche::Error::Done) => break,
            Err(e) => {
                tracing::warn!(?e, "stream_recv error");
                break;
            }
        }
    }

    if let Some(pos) = entry.rx_buf.iter().rposition(|&b| b == b'\n') {
        let complete = entry.rx_buf.drain(..=pos).collect::<Vec<u8>>();
        let text = String::from_utf8_lossy(&complete);
        for line in text.split('\n').filter(|s| !s.is_empty()) {
            match serde_json::from_str::<WireMessage>(line) {
                Ok(WireMessage::Handshake(h)) => {
                    let path = output_dir
                        .join(format!("session_{}.jsonl", h.session_id));

                    if let Some(existing) = sessions.get_mut(&h.session_id) {
                        // Reconnect of existing session
                        if existing.is_dormant() {
                            existing.wake();
                            tracing::info!(
                                session_id = ?h.session_id,
                                "session reconnected, waking up"
                            );
                        }
                    } else {
                        // Brand new session
                        sessions.insert(h.session_id, SessionState::new(
                            h.session_id, h.direction,
                            h.bitrate_bps, &path
                        ));
                    }

                    let ack = WireMessage::HandshakeAck(HandshakeAck {
                        status: HandshakeStatus::Ok,
                        message: String::new(),
                    });
                    let json = serde_json::to_string(&ack).unwrap();
                    let _ = entry.conn.stream_send(0, json.as_bytes(), false);
                    let _ = entry.conn.stream_send(0, b"\n", false);
                    entry.session_id = Some(h.session_id);
                    entry.direction = Some(h.direction);
                    entry.bitrate = Some(h.bitrate_bps);
                    tracing::info!(
                        session_id = ?h.session_id, ?h.direction, ?h.bitrate_bps,
                        "handshake complete"
                    );
                Ok(WireMessage::Goodbye(reason)) => {
                    tracing::info!(?reason, "client said goodbye");
                    if let Some(sid) = entry.session_id {
                        if let Some(sess) = sessions.get_mut(&sid) {
                            sess.close_jsonl();
                            sessions.remove(&sid);
                        }
                    }
                }
                Ok(WireMessage::ClientTelemetry(tel)) => {
                    if let Some(sid) = entry.session_id {
                        if let Some(sess) = sessions.get_mut(&sid) {
                            sess.write_telemetry(&tel);
                        }
                    }
                }
                Err(e) => {
                    tracing::warn!(%e, "malformed JSON on stream 0");
                }
                _ => {}
            }
        }
    }
}
```

### 9.4 Server Datagram Processing (UL test)

When the server receives datagrams during a UL test, it tracks loss/jitter:

```rust
/// Process incoming datagrams for UL testing.
/// Call this after feeding UDP data but before flushing.
fn process_datagrams(
    entry: &mut ActiveConn,
    session: &mut SessionState,
) {
    if entry.direction != Some(Direction::Ul) {
        return; // UL test: client sends, server receives
    }

    let mut dgram_buf = vec![0u8; MAX_DGRAM_SIZE];
    loop {
        match entry.conn.dgram_recv(&mut dgram_buf) {
            Ok(n) if n > 0 => {
                let payload = dgram_buf[..n].to_vec();
                if let Some((seq_num, send_ts)) =
                    decode_traffic_payload(&payload)
                {
                    let arrival = current_epoch_ms();
                    let (lost, jitter) = session
                        .datagram_tracker
                        .on_datagram(seq_num, send_ts, arrival);

                    tracing::debug!(
                        seq = seq_num, lost, ?jitter,
                        loss_rate = session.datagram_tracker.loss_rate(),
                        "UL datagram received"
                    );
                }
            }
            Ok(_) => break, // no more datagrams
            Err(quiche::Error::Done) => break,
            Err(e) => {
                tracing::warn!(?e, "dgram_recv error");
                break;
            }
        }
    }
}
```

### 9.5 `dgram_recv` Error Convention

`quiche::Connection::dgram_recv(&mut [u8])` returns `Result<usize, quiche::Error>`:
- `Ok(n)` — `n` bytes written to buffer. 0 means no datagram queued.
- `Err(quiche::Error::Done)` — no more datagrams available. **Normal, not an error.**
- `Err(e)` — actual error (e.g., connection not established).

Call in a loop, breaking on `Done`. Same pattern as `stream_recv`.

### 9.6 Traffic Pacing (Server DL Pattern)

The server uses `ServerTrafficState` in `ActiveConn` (see §9.1), which holds a tokio interval and a per-connection sequence counter. The `TrafficPacer` in the shared crate is synchronous — the event loop owns the timer.

Pattern:
```rust
// On handshake (DL direction), in process_stream_messages:
if h.direction == Direction::Dl {
    entry.server_traffic_state = Some(ServerTrafficState {
        interval: tokio::time::interval(
            calc_traffic_interval(h.bitrate_bps)),
        next_seq: 0,
    });
}

// In the event loop tick:
if let Some(ref mut sts) = entry.server_traffic_state {
    if sts.interval.tick().now_or_never().is_some() {
        let mut payload = [0u8; MAX_DGRAM_SIZE];
        encode_traffic_payload(
            sts.next_seq, current_epoch_ms(), &mut payload);
        let _ = entry.conn.dgram_send(&payload);
        sts.next_seq += 1;
    }
}
```

> The sequence counter lives on the server-side `ServerTrafficState`, not in `TrafficPacer`. `TrafficPacer.next_seq` starts at 0 and increments each call but is **not** persisted across server restarts or reconnects if stored separately. For DL tests where the server is the sender, `ServerTrafficState.next_seq` is the authoritative counter.

---

## 10. Constants Summary

| Constant | Value | Source |
|----------|-------|--------|
| `ALPN_RANMT` | `b"ranmt/0.1"` | protocol §1.2 |
| `STREAM_ID` | `0` | protocol §1.3 |
| `MAX_DGRAM_SIZE` | `1200` | protocol §4.1 |
| `TRAFFIC_HEADER_SIZE` | `17` | protocol §4.1 |
| `TELEMETRY_BUFFER_CAP` | `10 000` | protocol §7.1 |
| `RECONNECT_DELAY_MS` | `2000` | protocol §8.2 |
| `IDLE_TIMEOUT_MS` | `30 000` | protocol §1.1 |
| `TELEMETRY_INTERVAL_MS` | `1000` | protocol §6 |
| `STATS_INTERVAL_MS` | `1000` | protocol §5 |
| `PAYLOAD_TYPE_TRAFFIC` | `0x01` | protocol §4.1 |
| `SERVER_BIND_ADDR` | `"0.0.0.0"` | protocol §1.2 |
| `MAX_QUIC_PACKET` | `1500` | §6.1 |
| `DEFAULT_PORT` | `4433` | §4.2 CLI |
| `DORMANT_SESSION_TIMEOUT_H` | `24` | protocol §8.4 |

---

## 11. Utility Functions

### 11.1 `current_epoch_ms()`

```rust
pub fn current_epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
```

Returns Unix epoch in milliseconds. Used for `timestamp_ms` in telemetry and `send_ts` in datagrams.

### 11.2 `encode_traffic_payload()` / `decode_traffic_payload()`

```rust
pub fn encode_traffic_payload(
    seq_num: u64, send_ts: u64,
    out: &mut [u8; 1200],
) {
    out[0] = 0x01;
    out[1..9].copy_from_slice(&seq_num.to_be_bytes());
    out[9..17].copy_from_slice(&send_ts.to_be_bytes());
    out[17..].fill(0);
}

pub fn decode_traffic_payload(data: &[u8]) -> Option<(u64, u64)> {
    if data.len() < 17 || data[0] != 0x01 {
        return None;
    }
    let seq_num = u64::from_be_bytes(data[1..9].try_into().ok()?);
    let send_ts = u64::from_be_bytes(data[9..17].try_into().ok()?);
    Some((seq_num, send_ts))
}
```

### 11.2.1 `display_stats()` — CLI Output for Server Stats

```rust
/// Pretty-print server stats in the client CLI.
fn display_stats(stats: &ServerStats) {
    tracing::info!(
        "RTT={:.1}ms | TX={}B | RX={}B | CWND={}B | Lost={} | Rate={}bps",
        stats.quic_stats.rtt_ms,
        stats.quic_stats.tx_bytes,
        stats.quic_stats.rx_bytes,
        stats.quic_stats.cwnd,
        stats.quic_stats.lost_packets,
        stats.quic_stats.send_rate_bps,
    );
}
```

### 11.2.2 `display_loss()` — CLI Output for Packet Loss/Jitter (DL Test)

```rust
/// Log packet loss and jitter for received datagrams.
fn display_loss(
    seq_num: u64, lost: u64, jitter: f64,
    loss_rate: &f64, jitter_ewma: f64,
) {
    if lost > 0 || jitter > 1.0 {
        tracing::info!(
            "DL seq={} | lost={} | jitter={:.1}ms | rate={:.2}% | ewma={:.1}ms",
            seq_num, lost, jitter,
            loss_rate * 100.0, jitter_ewma,
        );
    }
}
```

> **Suppressive:** Quiet logs only fire when there's actual loss or jitter above 1ms to avoid flooding during normal operation.

### 11.3 `resolve_ipv4()` — Strict IPv4 DNS Resolver

```rust
use std::net::{SocketAddr, ToSocketAddrs};

/// Resolve a host string (FQDN or literal IPv4) to a single IPv4 address.
///
/// - If `host` is a literal IPv4 address (e.g. "10.0.0.50"), return it.
/// - If `host` is a FQDN, perform DNS resolution and select the first A record.
/// - If only AAAA records exist, return an error.
pub fn resolve_ipv4(host: &str, port: u16) -> Result<SocketAddr, Box<dyn std::error::Error>> {
    let addrs = (host, port).to_socket_addrs()?;
    for addr in addrs {
        if addr.is_ipv4() {
            return Ok(addr);
        }
    }
    Err(format!(
        "host '{}' has no IPv4 address (only AAAA records found)",
        host
    ).into())
}
```

**Usage in client:**
```rust
let peer_addr = resolve_ipv4(&config.server_addr, config.port)?;
```

> **Why not `tokio::net::lookup_host`?** `to_socket_addrs` from std uses the system resolver and is blocking, but for a one-shot DNS query before the main loop, the latency is negligible. In a production async context, consider `hickory-resolver` for non-blocking DNS.

### 11.4 `calc_traffic_interval()` — Bitrate to Interval Converter

```rust
use std::time::Duration;

/// Convert a target bitrate (bps) into a tokio interval for pacing.
/// Returns an `Interval` that fires once per datagram to send.
pub fn calc_traffic_interval(bitrate_bps: u32) -> Duration {
    let bits_per_dgram = (MAX_DGRAM_SIZE * 8) as u64;
    let datagrams_per_sec = (bitrate_bps as u64)
        .saturating_mul(1_000_000) / bits_per_dgram;
    // Avoid division by zero for very low bitrates
    let interval_us = if datagrams_per_sec > 0 {
        1_000_000_000 / datagrams_per_sec // nanoseconds for precision
    } else {
        1_000_000_000 // fallback: 1 datagram/s
    };
    Duration::from_nanos(interval_us)
}
```

> **Note on precision:** We use nanoseconds internally (`1_000_000_000 / datagrams_per_sec`) because microsecond-resolution tokio intervals can round to 0 for high bitrates. tokio supports sub-millisecond intervals via `Duration::from_nanos()`.

### 11.5 `extract_quic_stats()` — QUIC Stats Mapper for Server

```rust
/// Extract a QuicStats struct from a quiche Connection.
fn extract_quic_stats(conn: &quiche::Connection) -> QuicStats {
    let s = conn.stats();
    let rtt = s.rtt.as_secs_f64() * 1000.0;
    QuicStats {
        rtt_ms: rtt,
        tx_bytes: s.sent,        // total bytes sent
        rx_bytes: s.received,    // total bytes received
        cwnd: s.cwnd,
        lost_packets: s.lost,
        send_rate_bps: extract_send_rate_bps(&s),
    }
}
```

**`send_rate_bps` extraction** is not directly available from `quiche::Stats` as a raw number:
```rust
/// Extract send rate in bps if available.
/// quiche may not expose this in all versions; fallback to 0.
fn extract_send_rate_bps(stats: &quiche::Stats) -> u64 {
    // In quiche 0.22, send_rate is available in some builds.
    // If not, return 0 and let post-processing tools calculate
    // from tx_bytes / time delta.
    0 // placeholder — update when quiche API exposes it
}
```

> **Note:** `conn.stats()` returns a `quiche::Stats` struct. The exact fields available depend on the quiche version. In 0.22, `rtt`, `cwnd`, `sent`, `received`, `lost` are reliably available. `send_rate` may require reading from quiche FFI.

---

## 12. Server-Side Session Lifecycle Summary

SessionState management on the server involves two phases:

### 12.1 Connection Loss → Mark Dormant

When a QUIC connection is dropped (idle_timeout or error), the server calls `SessionState::mark_dormant()` on the matching session. This:
1. Records `dormant_since = Some(Instant::now())`
2. Syncs the JSONL file to disk
3. **Does not close or remove** the file — it stays on disk for 24h

### 12.2 Reconnect → Wake Up

When a new handshake arrives with an existing `session_id`, the server checks:
```rust
if let Some(sess) = sessions.get_mut(&h.session_id) {
    if sess.is_dormant() {
        sess.wake(); // Reopens file handle in append mode
    }
}
```

### 12.3 Expired Dormancy → Cleanup

At the end of each server loop iteration:
```rust
sessions.retain(|sid, sess| {
    if sess.is_dormant_expiring() {
        sess.close_jsonl();
        tracing::info!("Session {sid} expired, removing");
        false
    } else {
        true
    }
});
```

> **See** `datatypes.md §8` for the complete `SessionState` struct definition with all dormant lifecycle methods.
