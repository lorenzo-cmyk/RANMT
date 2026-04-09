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
