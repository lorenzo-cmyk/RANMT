use clap::Parser;
use ranmt_client::{ClientConfig, run_client};
use ranmt_shared::Direction;

/// RANMT Client — measure RAN performance during mobility events.
#[derive(Parser)]
#[command(name = "ranmt-client")]
struct Cli {
    /// Server FQDN or IPv4 address
    server: String,

    /// Test direction: "dl" or "ul"
    #[arg(short = 'd', long, required = true)]
    direction: String,

    /// Server QUIC port
    #[arg(short = 'p', long, default_value = "4433")]
    port: u16,

    /// Target bitrate in bps
    #[arg(short = 'b', long, default_value = "8000")]
    bitrate: u32,

    /// Test duration in seconds (0 = run until Ctrl+C)
    #[arg(short = 't', long, default_value = "0")]
    duration: u64,

    /// Disable certificate verification (dev mode)
    #[arg(long)]
    insecure: bool,

    /// SHA-256 fingerprint to pin (skips CA verification)
    #[arg(long)]
    cert_fingerprint: Option<String>,

    /// RNG seed for mock telemetry
    #[arg(long, default_value = "42")]
    seed: u64,

    /// Enable debug logging
    #[arg(short = 'v', long)]
    verbose: bool,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();

    tracing_subscriber::fmt()
        .with_env_filter(if cli.verbose { "debug" } else { "info" })
        .init();

    let direction = match cli.direction.to_lowercase().as_str() {
        "dl" => Direction::Dl,
        "ul" => Direction::Ul,
        _ => {
            return Err("direction must be 'dl' or 'ul'".into());
        }
    };

    if cli.insecure && cli.cert_fingerprint.is_some() {
        return Err("--cert-fingerprint and --insecure are mutually exclusive".into());
    }

    if cli.cert_fingerprint.is_some() {
        return Err("--cert-fingerprint is not implemented yet; use --insecure for dev or omit for CA verification".into());
    }

    let config = ClientConfig {
        server_addr: cli.server,
        server_fqdn: None,
        port: cli.port,
        direction,
        bitrate_bps: cli.bitrate,
        duration: cli.duration,
        insecure: cli.insecure,
        cert_fingerprint: cli.cert_fingerprint,
        seed: cli.seed,
    };

    run_client(config).await
}
