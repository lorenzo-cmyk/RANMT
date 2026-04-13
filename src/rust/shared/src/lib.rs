use serde::{Deserialize, Serialize};
use std::f64::consts::TAU;
use std::fs::File;
use std::io::Write;
use std::net::ToSocketAddrs;
use std::path::Path;
use std::time::Instant;
use uuid::Uuid;

// ─────────────────────────────────────────────
// Wire Protocol Constants
// ─────────────────────────────────────────────

pub const ALPN_RANMT: &[u8] = b"ranmt/0.1";
pub const MAX_DGRAM_SIZE: usize = 1200;
pub const TRAFFIC_HEADER_SIZE: usize = 1 + 8 + 8;
pub const TELEMETRY_BUFFER_CAP: usize = 10_000;
pub const RECONNECT_DELAY_MS: u64 = 2000;
/// Server idle timeout (10 seconds). quiche expects milliseconds.
pub const IDLE_TIMEOUT_MS: u64 = 10_000;
pub const TELEMETRY_INTERVAL_MS: u64 = 1000;
pub const STATS_INTERVAL_MS: u64 = 1000;
pub const PAYLOAD_TYPE_TRAFFIC: u8 = 0x01;
pub const SERVER_BIND_ADDR: &str = "0.0.0.0";
pub const DEFAULT_PORT: u16 = 4433;
pub const DEFAULT_BITRATE_BPS: u32 = 8000;
pub const MAX_QUIC_PACKET: usize = 1500;
pub const DORMANT_TIMEOUT_HOURS: u64 = 24;
pub const STREAM_ID: u64 = 0;

// ─────────────────────────────────────────────
// Handshake Types
// ─────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Handshake {
    pub session_id: Uuid,
    pub direction: Direction,
    pub bitrate_bps: u32,
    pub client_version: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Direction {
    Dl,
    Ul,
}

// ─────────────────────────────────────────────
// HandshakeAck
// ─────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HandshakeAck {
    pub status: HandshakeStatus,
    pub message: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum HandshakeStatus {
    Ok,
    Error,
}

// ─────────────────────────────────────────────
// Goodbye
// ─────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Goodbye {
    pub reason: GoodbyeReason,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoodbyeReason {
    TestComplete,
    UserAbort,
    Error,
}

// ─────────────────────────────────────────────
// Server Stats
// ─────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerStats {
    pub timestamp_ms: u64,
    pub quic_stats: QuicStats,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct QuicStats {
    /// Round-trip time in milliseconds (0 if no measurement available)
    pub rtt_ms: f64,
    pub rttvar_ms: f64,
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub tx_packets: u64,
    pub rx_packets: u64,
    pub cwnd: u64,
    pub lost_packets: u64,
    pub send_rate_bps: u64,
}

// ─────────────────────────────────────────────
// Client Telemetry
// ─────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientTelemetry {
    pub seq_num: u64,
    pub timestamp_ms: u64,
    pub lat: f64,
    pub lon: f64,
    pub speed: f64,
    pub network_type: NetworkType,
    pub cell_id: u32,
    pub pci: u16,
    pub earfcn: u32,
    pub rsrp: f64,
    pub rsrq: f64,
    pub sinr: f64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum NetworkType {
    #[serde(rename = "5g")]
    FiveG,
    Lte,
    #[serde(rename = "3g")]
    ThreeG,
    #[serde(rename = "2g")]
    TwoG,
    Unknown,
}

// ─────────────────────────────────────────────
// Traffic Datagram (for JSONL persistence)
// ─────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrafficDatagram {
    pub seq_num: u64,
    pub send_ts_ms: u64,
    pub recv_ts_ms: u64,
}

// ─────────────────────────────────────────────
// WireMessage (serde tag owner)
// ─────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum WireMessage {
    #[serde(rename = "handshake")]
    Handshake(Handshake),
    #[serde(rename = "handshake_ack")]
    HandshakeAck(HandshakeAck),
    #[serde(rename = "server_stats")]
    ServerStats(ServerStats),
    #[serde(rename = "telemetry")]
    ClientTelemetry(ClientTelemetry),
    #[serde(rename = "goodbye")]
    Goodbye(Goodbye),
    #[serde(rename = "traffic_datagram")]
    TrafficDatagram(TrafficDatagram),
}

// ─────────────────────────────────────────────
// Utility Functions
// ─────────────────────────────────────────────

pub fn current_epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub fn encode_traffic_payload(seq_num: u64, send_ts: u64, out: &mut [u8; MAX_DGRAM_SIZE]) {
    out[0] = PAYLOAD_TYPE_TRAFFIC;
    out[1..9].copy_from_slice(&seq_num.to_be_bytes());
    out[9..17].copy_from_slice(&send_ts.to_be_bytes());
    out[17..].fill(0);
}

/// Decode a traffic payload from a datagram.
/// Returns `(seq_num, send_ts)` if valid, or `None` if the payload is too
/// short or has the wrong type byte.  Callers should check `data.len() ==
/// MAX_DGRAM_SIZE` separately to satisfy spec §10.
pub fn decode_traffic_payload(data: &[u8]) -> Option<(u64, u64)> {
    if data.len() < TRAFFIC_HEADER_SIZE || data[0] != PAYLOAD_TYPE_TRAFFIC {
        return None;
    }
    let seq_num = u64::from_be_bytes(data[1..9].try_into().ok()?);
    let send_ts = u64::from_be_bytes(data[9..17].try_into().ok()?);
    Some((seq_num, send_ts))
}

/// Resolve a host string (FQDN or literal IPv4) to a single IPv4 address.
pub fn resolve_ipv4(
    host: &str,
    port: u16,
) -> Result<std::net::SocketAddr, Box<dyn std::error::Error>> {
    let addrs = (host, port).to_socket_addrs()?;
    for addr in addrs {
        if addr.is_ipv4() {
            return Ok(addr);
        }
    }
    Err(format!(
        "host '{}' has no IPv4 address (only AAAA records found)",
        host
    )
    .into())
}

/// Convert a target bitrate (bps) into a duration for pacing.
pub fn calc_traffic_interval(bitrate_bps: u32) -> std::time::Duration {
    let bits_per_dgram = (MAX_DGRAM_SIZE * 8) as u64;
    // Use floating-point to handle low bitrates where datagrams/sec < 1
    let datagrams_per_sec = (bitrate_bps as f64) / (bits_per_dgram as f64);
    let interval_ns = if datagrams_per_sec > 0.0 {
        (1_000_000_000.0 / datagrams_per_sec) as u64
    } else {
        1_000_000_000 // fallback: 1 dgram/s
    };
    std::time::Duration::from_nanos(interval_ns)
}

// ─────────────────────────────────────────────
// Traffic Pacer
// ─────────────────────────────────────────────

pub struct TrafficPacer {
    bits_per_dgram: u64,
    bitrate_bps: u32,
    next_seq: u64,
}

impl TrafficPacer {
    pub fn new(bitrate_bps: u32) -> Self {
        Self::with_seq(bitrate_bps, 0)
    }

    pub fn with_seq(bitrate_bps: u32, next_seq: u64) -> Self {
        Self {
            bits_per_dgram: (MAX_DGRAM_SIZE * 8) as u64,
            bitrate_bps,
            next_seq,
        }
    }

    pub fn next_seq(&self) -> u64 {
        self.next_seq
    }

    pub fn mark_sent(&mut self) {
        self.next_seq += 1;
    }

    pub fn interval(&self) -> std::time::Duration {
        let datagrams_per_sec = (self.bitrate_bps as f64) / (self.bits_per_dgram as f64);
        let interval_ns = if datagrams_per_sec > 0.0 {
            (1_000_000_000.0 / datagrams_per_sec) as u64
        } else {
            1_000_000_000
        };
        std::time::Duration::from_nanos(interval_ns)
    }

    pub fn next_payload(&mut self) -> (u64, [u8; MAX_DGRAM_SIZE]) {
        let seq = self.next_seq;
        self.mark_sent();
        let send_ts = current_epoch_ms();
        let mut buf = [0u8; MAX_DGRAM_SIZE];
        encode_traffic_payload(seq, send_ts, &mut buf);
        (seq, buf)
    }
}

// ─────────────────────────────────────────────
// Server Session State
// ─────────────────────────────────────────────

pub struct SessionState {
    pub session_id: Uuid,
    pub direction: Direction,
    pub bitrate_bps: u32,
    pub client_version: String,
    pub jsonl_path: std::path::PathBuf,
    pub jsonl_file: File,
    /// Datagram sequence counter (sender side, for DL tests).
    /// Persisted across reconnects per spec §8.2 rule 4.
    pub datagram_send_seq: u64,
    pub dormant_since: Option<Instant>,
}

impl SessionState {
    pub fn new(
        session_id: Uuid,
        direction: Direction,
        bitrate_bps: u32,
        jsonl_path: &Path,
    ) -> std::io::Result<Self> {
        let jsonl_file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(jsonl_path)?;
        Ok(Self {
            session_id,
            direction,
            bitrate_bps,
            client_version: String::new(),
            jsonl_path: jsonl_path.to_owned(),
            jsonl_file,
            datagram_send_seq: 0,
            dormant_since: None,
        })
    }

    pub fn write_telemetry(&mut self, tel: &ClientTelemetry) {
        self.append_wire_message(&WireMessage::ClientTelemetry(tel.clone()));
    }

    pub fn write_handshake(&mut self, h: &Handshake) {
        self.append_wire_message(&WireMessage::Handshake(h.clone()));
    }

    pub fn write_server_stats(&mut self, stats: &ServerStats) {
        self.append_wire_message(&WireMessage::ServerStats(stats.clone()));
    }

    pub fn write_traffic_datagram(&mut self, dgram: &TrafficDatagram) {
        self.append_wire_message(&WireMessage::TrafficDatagram(dgram.clone()));
    }

    fn append_wire_message(&mut self, msg: &WireMessage) {
        let line = match serde_json::to_string(msg) {
            Ok(v) => v,
            Err(e) => {
                tracing::error!(?e, "failed to serialize message");
                return;
            }
        };

        if let Err(e) = writeln!(self.jsonl_file, "{line}") {
            tracing::error!(?e, "failed to append JSONL line");
            return;
        }

        if let Err(e) = self.jsonl_file.sync_all() {
            tracing::error!(?e, "failed to sync JSONL file");
        }
    }

    pub fn mark_dormant(&mut self) {
        self.dormant_since = Some(Instant::now());
        let _ = self.jsonl_file.sync_all();
    }

    pub fn is_dormant_expiring(&self) -> bool {
        match self.dormant_since {
            Some(since) => {
                since.elapsed() >= std::time::Duration::from_secs(DORMANT_TIMEOUT_HOURS * 3600)
            }
            None => false,
        }
    }

    pub fn is_dormant(&self) -> bool {
        self.dormant_since.is_some()
    }

    pub fn wake(&mut self) -> std::io::Result<()> {
        self.dormant_since = None;
        self.jsonl_file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.jsonl_path)?;
        Ok(())
    }

    pub fn close_jsonl(&mut self) {
        let _ = self.jsonl_file.sync_all();
        tracing::info!(
            session_id = ?self.session_id,
            path = ?self.jsonl_path,
            "JSONL file synced and closed (conceptually)"
        );
    }
}

// ─────────────────────────────────────────────
// Mock Telemetry Generator
// ─────────────────────────────────────────────

pub struct MockTelemetry {
    test_start: Instant,
    phase_offset: f64,
    pub seq_num: u64,
}

impl MockTelemetry {
    fn phase_offset_from_seed(seed: u64) -> f64 {
        let scrambled = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        (scrambled as f64 / u64::MAX as f64) * TAU
    }

    pub fn new(test_start: Instant) -> Self {
        Self::with_seed_seq(test_start, 0, 0)
    }

    pub fn with_seq(test_start: Instant, seq_num: u64) -> Self {
        Self::with_seed_seq(test_start, seq_num, 0)
    }

    pub fn with_seed(test_start: Instant, seed: u64) -> Self {
        Self::with_seed_seq(test_start, 0, seed)
    }

    pub fn with_seed_seq(test_start: Instant, seq_num: u64, seed: u64) -> Self {
        Self {
            test_start,
            phase_offset: Self::phase_offset_from_seed(seed),
            seq_num,
        }
    }

    pub fn generate(&mut self) -> ClientTelemetry {
        let phase = self.test_start.elapsed().as_secs_f64() + self.phase_offset;
        let seq = self.seq_num;
        self.seq_num += 1;

        let lat = 41.9028 + 0.001 * (phase * 0.5).sin();
        let lon = 12.4964 + 0.001 * (phase * 0.3 + 1.0).sin();
        let speed = 60.0 + 60.0 * (phase * TAU / 60.0).sin();

        let cycle_30 = (phase / 30.0).floor() as i64;
        let network_type = match (cycle_30 % 4).abs() {
            0 => NetworkType::Lte,
            1 => NetworkType::FiveG,
            2 => NetworkType::ThreeG,
            3 => NetworkType::Lte,
            _ => NetworkType::Unknown,
        };

        let cell_id = 12345 + (phase / 30.0) as u32;
        let pci = (150 + (phase / 30.0) as u16) % 504;
        let earfcn = 1850 + (phase / 30.0) as u32;

        let rsrp = -100.0 + 20.0 * (phase * TAU / 45.0).sin();
        let rsrq = -11.5 + 8.5 * (phase * TAU / 45.0 + 0.5).sin();
        let sinr = 10.0 + 15.0 * (phase * TAU / 50.0 + 1.2).sin();

        ClientTelemetry {
            seq_num: seq,
            timestamp_ms: current_epoch_ms(),
            lat,
            lon,
            speed,
            network_type,
            cell_id,
            pci,
            earfcn,
            rsrp,
            rsrq,
            sinr,
        }
    }
}
