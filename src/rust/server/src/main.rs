use clap::Parser;
use quiche::ConnectionId;
use ranmt_shared::*;
use rcgen::{CertifiedKey, generate_simple_self_signed};
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;

/// RANMT Server — measures RAN performance during mobility events.
#[derive(Parser)]
#[command(name = "ranmt-server")]
struct Cli {
    #[arg(long, default_value = "0.0.0.0")]
    bind_addr: Ipv4Addr,

    #[arg(short = 'p', long)]
    port: u16,

    #[arg(long)]
    cert: Option<PathBuf>,

    #[arg(long)]
    key: Option<PathBuf>,

    #[arg(long, default_value = "./sessions")]
    output_dir: PathBuf,

    #[arg(short = 'v', long)]
    verbose: bool,
}

// ─────────────────────────────────────
// Server-side connection state
// ─────────────────────────────────────

struct ActiveConn {
    conn: quiche::Connection,
    rx_buf: Vec<u8>,
    peer: SocketAddr,
    session_id: Option<uuid::Uuid>,
    direction: Option<Direction>,
    bitrate: Option<u32>,
    stats_interval: tokio::time::Interval,
    server_traffic_state: Option<ServerTrafficState>,
    last_rx_at: Instant,
    last_stream_at: Option<Instant>,
    last_dgram_at: Option<Instant>,
}

struct ServerTrafficState {
    interval: tokio::time::Interval,
    next_seq: u64,
}

// ─────────────────────────────────────
// QUIC config
// ─────────────────────────────────────

fn make_quic_config() -> Result<quiche::Config, quiche::Error> {
    let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION)?;
    // quiche 0.28 exposes BBR via the `Bbr2Gcongestion` variant.
    config.set_cc_algorithm(quiche::CongestionControlAlgorithm::Bbr2Gcongestion);
    config.enable_dgram(true, MAX_DGRAM_SIZE, MAX_DGRAM_SIZE);
    // Keep QUIC packets at MTU-sized 1500 so 1200-byte DATAGRAM payloads
    // fit after short-header + packet number + AEAD overhead.
    config.set_max_recv_udp_payload_size(MAX_QUIC_PACKET);
    config.set_max_send_udp_payload_size(MAX_QUIC_PACKET);
    config.set_max_idle_timeout(IDLE_TIMEOUT_MS);
    config.set_max_ack_delay(5); // Reduced to 5ms to prevent RTT inflation/jitter
    config.set_initial_max_streams_bidi(4);
    config.set_initial_max_streams_uni(4);
    config.set_initial_max_stream_data_bidi_local(5_242_880);
    config.set_initial_max_stream_data_bidi_remote(5_242_880);
    config.set_initial_max_stream_data_uni(524_288);
    config.set_initial_max_data(10_485_760);
    config.set_application_protos(&[ALPN_RANMT])?;
    Ok(config)
}

fn load_server_tls(
    config: &mut quiche::Config,
    cert_path: &str,
    key_path: &str,
) -> Result<(), quiche::Error> {
    config.load_cert_chain_from_pem_file(cert_path)?;
    config.load_priv_key_from_pem_file(key_path)?;
    Ok(())
}

fn generate_dev_cert() -> Result<(String, String), rcgen::Error> {
    let CertifiedKey { cert, signing_key } =
        generate_simple_self_signed(vec!["ranmt-dev.local".to_string()])?;
    Ok((cert.pem(), signing_key.serialize_pem()))
}

fn load_or_generate_cert(
    config: &Cli,
) -> Result<(Option<String>, Option<String>), Box<dyn std::error::Error>> {
    match (&config.cert, &config.key) {
        (Some(cert_path), Some(key_path)) => Ok((
            Some(cert_path.to_string_lossy().to_string()),
            Some(key_path.to_string_lossy().to_string()),
        )),
        (None, None) => {
            let (cert_pem, key_pem) = generate_dev_cert()?;
            let tmp = std::env::temp_dir();
            let cert_path = tmp.join("ranmt-dev-cert.pem");
            let key_path = tmp.join("ranmt-dev-key.pem");
            std::fs::write(&cert_path, cert_pem)?;
            std::fs::write(&key_path, key_pem)?;
            Ok((
                Some(cert_path.to_string_lossy().to_string()),
                Some(key_path.to_string_lossy().to_string()),
            ))
        }
        _ => Err("both --cert and --key must be provided, or neither".into()),
    }
}

const MIN_BITRATE_BPS: u32 = 1_000;
const MAX_BITRATE_BPS: u32 = 1_000_000;
const MEASUREMENT_STATE_LOG_MS: u64 = 5_000;

/// Validate handshake fields. Returns an error message if validation fails.
fn validate_handshake(h: &Handshake) -> Option<String> {
    if h.bitrate_bps < MIN_BITRATE_BPS || h.bitrate_bps > MAX_BITRATE_BPS {
        return Some(format!(
            "bitrate_bps {} out of range [{}, {}]",
            h.bitrate_bps, MIN_BITRATE_BPS, MAX_BITRATE_BPS
        ));
    }
    // Semver check: must have 3 parts X.Y.Z (e.g. "0.1.0")
    let parts = h.client_version.split('.').count();
    if parts < 3 {
        return Some(format!(
            "client_version '{}' does not look like semver (expected X.Y.Z)",
            h.client_version
        ));
    }
    None
}

fn send_handshake_ack(conn: &mut quiche::Connection, status: HandshakeStatus, message: &str) {
    tracing::debug!(?status, %message, "sending handshake ack");
    let ack = WireMessage::HandshakeAck(HandshakeAck {
        status,
        message: message.to_string(),
    });
    let Ok(json) = serde_json::to_string(&ack) else {
        tracing::warn!("failed to serialize handshake ack");
        return;
    };
    let mut line = json.into_bytes();
    line.push(b'\n');
    if let Err(e) = conn.stream_send(0, &line, false)
        && !matches!(e, quiche::Error::Done)
    {
        tracing::warn!(?e, "failed to send handshake ack");
    }
}

fn close_stream_0(conn: &mut quiche::Connection) {
    tracing::debug!("closing stream 0");
    let _ = conn.stream_shutdown(STREAM_ID, quiche::Shutdown::Read, 0);
    let _ = conn.stream_shutdown(STREAM_ID, quiche::Shutdown::Write, 0);
}

fn dcid_key_from_packet(packet: &mut [u8]) -> Option<[u8; 16]> {
    let hdr = quiche::Header::from_slice(packet, 16).ok()?;
    if hdr.dcid.len() != 16 {
        return None;
    }

    let mut dcid = [0u8; 16];
    dcid.copy_from_slice(hdr.dcid.as_ref());
    Some(dcid)
}

fn remove_binding_for_scid(session_bindings: &mut HashMap<uuid::Uuid, [u8; 16]>, scid: [u8; 16]) {
    session_bindings.retain(|_, bound_scid| *bound_scid != scid);
}

fn extract_quic_stats(conn: &quiche::Connection) -> QuicStats {
    let s = conn.stats();
    let path = conn.path_stats().next();
    let (rtt_ms, rttvar_ms, cwnd, send_rate_bps) = path
        .map(|ps| {
            (
                ps.rtt.as_secs_f64() * 1000.0,
                ps.rttvar.as_secs_f64() * 1000.0,
                ps.cwnd as u64,
                ps.delivery_rate,
            )
        })
        .unwrap_or((0.0, 0.0, 0, 0));
    QuicStats {
        rtt_ms,
        rttvar_ms,
        tx_bytes: s.sent_bytes,
        rx_bytes: s.recv_bytes,
        cwnd,
        lost_packets: s.lost as u64,
        send_rate_bps,
    }
}

fn log_measurement_state(
    sessions: &HashMap<uuid::Uuid, SessionState>,
    active: &HashMap<[u8; 16], ActiveConn>,
    session_bindings: &HashMap<uuid::Uuid, [u8; 16]>,
) {
    let now = Instant::now();
    let total = sessions.len();
    let dormant = sessions.values().filter(|s| s.is_dormant()).count();
    let active_sessions = total.saturating_sub(dormant);
    tracing::info!(
        total_sessions = total,
        active_sessions,
        dormant_sessions = dormant,
        active_conns = active.len(),
        "measurement state"
    );

    for (sid, sess) in sessions {
        let bound_scid = session_bindings.get(sid).copied();
        let conn_entry = bound_scid.and_then(|scid| active.get(&scid));
        let conn_open = conn_entry
            .map(|entry| !entry.conn.is_closed())
            .unwrap_or(false);
        let conn_established = conn_entry
            .map(|entry| entry.conn.is_established())
            .unwrap_or(false);
        let last_rx_age_ms = conn_entry
            .map(|entry| now.saturating_duration_since(entry.last_rx_at).as_millis() as u64);
        let last_stream_age_ms = conn_entry
            .and_then(|entry| entry.last_stream_at)
            .map(|ts| now.saturating_duration_since(ts).as_millis() as u64);
        let last_dgram_age_ms = conn_entry
            .and_then(|entry| entry.last_dgram_at)
            .map(|ts| now.saturating_duration_since(ts).as_millis() as u64);

        let state = if sess.is_dormant() {
            "dormant"
        } else if conn_open {
            "active"
        } else {
            "disconnected"
        };

        if sess.direction == Direction::Ul {
            let stats = conn_entry.map(|e| extract_quic_stats(&e.conn));
            let rttvar_ms = stats.map(|s| s.rttvar_ms);
            let lost_packets = stats.map(|s| s.lost_packets);

            tracing::debug!(
                session_id = ?sid,
                state,
                direction = ?sess.direction,
                bitrate_bps = sess.bitrate_bps,
                conn_open,
                conn_established,
                bound_scid = ?bound_scid,
                peer = ?conn_entry.map(|e| e.peer),
                last_rx_age_ms,
                last_stream_age_ms,
                last_dgram_age_ms,
                ?lost_packets,
                ?rttvar_ms,
                "measurement detail"
            );
        } else {
            tracing::debug!(
                session_id = ?sid,
                state,
                direction = ?sess.direction,
                bitrate_bps = sess.bitrate_bps,
                conn_open,
                conn_established,
                bound_scid = ?bound_scid,
                peer = ?conn_entry.map(|e| e.peer),
                last_rx_age_ms,
                last_stream_age_ms,
                last_dgram_age_ms,
                datagram_send_seq = sess.datagram_send_seq,
                "measurement detail"
            );
        }
    }
}

// ─────────────────────────────────────
// Stream 0 processing helpers
// ─────────────────────────────────────

fn process_stream_messages(
    entry: &mut ActiveConn,
    sessions: &mut HashMap<uuid::Uuid, SessionState>,
    output_dir: &Path,
) {
    let stream_id = STREAM_ID;
    let mut tmp = [0u8; 8192];
    loop {
        match entry.conn.stream_recv(stream_id, &mut tmp) {
            Ok((n, _fin)) if n > 0 => {
                tracing::trace!(bytes = n, "stream 0 bytes received");
                entry.rx_buf.extend_from_slice(&tmp[..n]);
                entry.last_stream_at = Some(Instant::now());
            }
            Ok(_) | Err(quiche::Error::Done) => break,
            Err(quiche::Error::InvalidStreamState(_)) => break,
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
            tracing::trace!(line = %line, "stream 0 line");
            match serde_json::from_str::<WireMessage>(line) {
                Ok(WireMessage::Handshake(h)) => {
                    tracing::info!(
                        session_id = ?h.session_id,
                        direction = ?h.direction,
                        bitrate_bps = h.bitrate_bps,
                        client_version = %h.client_version,
                        "handshake received"
                    );
                    // For session reconnects, skip validation (already accepted)
                    if sessions.get(&h.session_id).is_none()
                        && let Some(err_msg) = validate_handshake(&h)
                    {
                        tracing::warn!(
                            session_id = ?h.session_id,
                            %err_msg,
                            "handshake rejected"
                        );
                        send_handshake_ack(&mut entry.conn, HandshakeStatus::Error, &err_msg);
                        close_stream_0(&mut entry.conn);
                        // Close the QUIC connection so the client can reconnect.
                        let _ = entry.conn.close(true, 0x00, b"handshake error");
                        continue;
                    }

                    let path = output_dir.join(format!("session_{}.jsonl", h.session_id));

                    if let Some(existing) = sessions.get_mut(&h.session_id) {
                        if existing.is_dormant() {
                            if let Err(e) = existing.wake() {
                                tracing::error!(
                                    session_id = ?h.session_id,
                                    ?e,
                                    "failed to wake session jsonl file"
                                );
                                send_handshake_ack(
                                    &mut entry.conn,
                                    HandshakeStatus::Error,
                                    "server error: failed to reopen session file",
                                );
                                close_stream_0(&mut entry.conn);
                                let _ = entry.conn.close(true, 0x00, b"session error");
                                continue;
                            }
                            tracing::info!(
                                session_id = ?h.session_id,
                                "session reconnected, waking up"
                            );
                        }
                    } else {
                        let mut sess = match SessionState::new(
                            h.session_id,
                            h.direction,
                            h.bitrate_bps,
                            &path,
                        ) {
                            Ok(s) => s,
                            Err(e) => {
                                tracing::error!(
                                    session_id = ?h.session_id,
                                    ?e,
                                    "failed to create session file"
                                );
                                send_handshake_ack(
                                    &mut entry.conn,
                                    HandshakeStatus::Error,
                                    "server error: cannot open session file",
                                );
                                close_stream_0(&mut entry.conn);
                                let _ = entry.conn.close(true, 0x00, b"session error");
                                continue;
                            }
                        };
                        sess.client_version = h.client_version.clone();
                        sessions.insert(h.session_id, sess);
                    }

                    send_handshake_ack(&mut entry.conn, HandshakeStatus::Ok, "");

                    // Initialize traffic pacer for DL, restoring persisted seq
                    if h.direction == Direction::Dl {
                        let interval = calc_traffic_interval(h.bitrate_bps);
                        let mut ivl = tokio::time::interval(interval);
                        ivl.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
                        // Restore datagram seq from session state (spec §8.2 rule 4)
                        if let Some(existing) = sessions.get(&h.session_id) {
                            entry.server_traffic_state = Some(ServerTrafficState {
                                interval: ivl,
                                next_seq: existing.datagram_send_seq,
                            });
                        } else {
                            entry.server_traffic_state = Some(ServerTrafficState {
                                interval: ivl,
                                next_seq: 0,
                            });
                        }
                    }

                    entry.session_id = Some(h.session_id);
                    entry.direction = Some(h.direction);
                    entry.bitrate = Some(h.bitrate_bps);
                    tracing::info!(
                        session_id = ?h.session_id,
                        direction = ?h.direction,
                        bitrate_bps = h.bitrate_bps,
                        "handshake complete"
                    );
                }
                Ok(WireMessage::Goodbye(reason)) => {
                    tracing::info!("client said goodbye (reason={reason:?})");
                    if let Some(sid) = entry.session_id
                        && let Some(sess) = sessions.get_mut(&sid)
                    {
                        sess.close_jsonl();
                        sessions.remove(&sid);
                    }
                    entry.session_id = None;
                    entry.direction = None;
                    entry.bitrate = None;
                    entry.server_traffic_state = None;
                    // Per spec §9.1: close the QUIC connection immediately.
                    let _ = entry.conn.close(true, 0x00, b"goodbye");
                }
                Ok(WireMessage::ClientTelemetry(tel)) => {
                    if let Some(sid) = entry.session_id
                        && let Some(sess) = sessions.get_mut(&sid)
                    {
                        tracing::trace!(session_id = ?sid, timestamp = tel.timestamp_ms, "telemetry received");
                        sess.write_telemetry(&tel);
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

fn process_datagrams(entry: &mut ActiveConn, _session: &mut SessionState) {
    if entry.direction != Some(Direction::Ul) {
        return;
    }

    let mut dgram_buf = vec![0u8; MAX_DGRAM_SIZE];
    loop {
        match entry.conn.dgram_recv(&mut dgram_buf) {
            Ok(n) if n > 0 => {
                tracing::trace!(bytes = n, "datagram received");
                entry.last_dgram_at = Some(Instant::now());
                if n != MAX_DGRAM_SIZE {
                    tracing::warn!(size = n, "UL datagram size mismatch, skipping");
                    continue;
                }
                if let Some((seq_num, _send_ts)) = decode_traffic_payload(&dgram_buf[..n]) {
                    tracing::debug!(seq = seq_num, "UL datagram received");
                }
            }
            Ok(_) | Err(quiche::Error::Done) => break,
            Err(e) => {
                tracing::warn!(?e, "dgram_recv error");
                break;
            }
        }
    }
}

async fn flush_conn(conn: &mut quiche::Connection, socket: &UdpSocket, peer: SocketAddr) {
    let mut buf = [0u8; MAX_QUIC_PACKET];
    loop {
        match conn.send(&mut buf) {
            Ok((n, _info)) => {
                tracing::trace!(bytes = n, ?peer, "flushing QUIC packet");
                let _ = socket.send_to(&buf[..n], peer).await;
            }
            Err(quiche::Error::Done) => break,
            Err(e) => {
                tracing::warn!(?e, "conn.send error");
                break;
            }
        }
    }
}

/// Non-blocking process of QUIC timeout + stats + traffic for a single
/// connection.  Returns true if the connection should be kept alive.
async fn process_connection_periodic(entry: &mut ActiveConn, socket: &UdpSocket) -> bool {
    if entry.conn.is_closed() {
        tracing::debug!("connection already closed");
        return false;
    }

    // Process QUIC timeout
    if let Some(timeout) = entry.conn.timeout()
        && timeout.is_zero() {
            tracing::debug!("QUIC timeout fired");
            entry.conn.on_timeout();
            if entry.conn.is_closed() {
                tracing::debug!("connection closed after timeout");
                return false;
            }
        }

    // Send server stats on interval
    if entry.session_id.is_some() {
        let stats_fired = tokio::time::timeout(Duration::ZERO, entry.stats_interval.tick())
            .await
            .is_ok();
        if stats_fired {
            tracing::trace!("sending server stats");
            let stats = WireMessage::ServerStats(ServerStats {
                timestamp_ms: current_epoch_ms(),
                quic_stats: extract_quic_stats(&entry.conn),
            });
            match serde_json::to_string(&stats) {
                Ok(json) => {
                    let mut line = json.into_bytes();
                    line.push(b'\n');
                    if let Err(e) = entry.conn.stream_send(0, &line, false)
                        && !matches!(e, quiche::Error::Done)
                    {
                        tracing::warn!(?e, "failed to send server stats");
                    }
                }
                Err(e) => tracing::warn!(?e, "failed to serialize server stats"),
            }
        }
    }

    // Traffic pacer (DL only)
    if let Some(sts) = entry.server_traffic_state.as_mut() {
        let traffic_fired = tokio::time::timeout(Duration::ZERO, sts.interval.tick())
            .await
            .is_ok();
        if traffic_fired && entry.conn.is_established() {
            tracing::trace!(seq = sts.next_seq, "sending DL datagram");
            let mut payload = [0u8; MAX_DGRAM_SIZE];
            encode_traffic_payload(sts.next_seq, current_epoch_ms(), &mut payload);
            match entry.conn.dgram_send(&payload) {
                Ok(_) => {
                    tracing::debug!(seq = sts.next_seq, "DL datagram sent");
                    sts.next_seq += 1;
                }
                Err(quiche::Error::Done) => {}
                Err(e) => {
                    tracing::warn!(?e, "server dgram_send error");
                }
            }
        }
    }

    if !entry.conn.is_closed() {
        flush_conn(&mut entry.conn, socket, entry.peer).await;
    }
    true
}

// ─────────────────────────────────────
// Server entry point
// ─────────────────────────────────────

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();

    tracing_subscriber::fmt()
        .with_env_filter(if cli.verbose {
            "ranmt_server=debug,quiche=debug"
        } else {
            "ranmt_server=info,quiche=warn"
        })
        .init();

    tracing::info!(
        addr = %cli.bind_addr,
        port = cli.port,
        "starting RANMT server"
    );
    tracing::debug!(output_dir = %cli.output_dir.display(), "session output directory");

    // Load TLS
    let mut quic_cfg = make_quic_config()?;
    let (cert_path, key_path) = load_or_generate_cert(&cli)?;
    if let (Some(c), Some(k)) = (&cert_path, &key_path) {
        tracing::debug!(cert = %c, key = %k, "loading TLS material");
        load_server_tls(&mut quic_cfg, c, k)?;
    }

    // Bind UDP socket
    let socket = UdpSocket::bind(format!("{}:{}", cli.bind_addr, cli.port)).await?;
    tracing::info!(local_addr = %socket.local_addr()?, "UDP socket bound");

    let output_dir = &cli.output_dir;
    std::fs::create_dir_all(output_dir)?;

    let mut active: HashMap<[u8; 16], ActiveConn> = HashMap::new();
    let mut sessions: HashMap<uuid::Uuid, SessionState> = HashMap::new();
    let mut session_bindings: HashMap<uuid::Uuid, [u8; 16]> = HashMap::new();
    let mut udp_buf = vec![0u8; MAX_QUIC_PACKET];

    // Poll interval for periodic server tasks (flushing, timeouts)
    // even when no UDP packets arrive.  This keeps the handshake alive
    // between client packets.
    let mut tick_interval = tokio::time::interval(Duration::from_millis(5));
    tick_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    let mut measurement_state_interval =
        tokio::time::interval(Duration::from_millis(MEASUREMENT_STATE_LOG_MS));
    measurement_state_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        tokio::select! {
            biased;

            // — Periodic tick: flush pending packets, process timeouts,
            //   send stats/traffic —
            _ = tick_interval.tick() => {
                tracing::trace!("periodic tick");
                // Process all connections
                let mut to_remove = Vec::new();
                for (scid, entry) in active.iter_mut() {
                    tracing::trace!(scid = ?scid, "periodic connection sweep");
                    if !entry.conn.is_closed() {
                        let alive = process_connection_periodic(
                            entry, &socket,
                        ).await;
                        if !alive {
                            to_remove.push(*scid);
                        }
                    } else {
                        to_remove.push(*scid);
                    }
                }

                // Clean up dead connections
                for scid in to_remove {
                    if let Some(entry) = active.remove(&scid)
                        && let Some(sid) = entry.session_id
                            && let Some(sess) = sessions.get_mut(&sid) {
                                // Persist datagram send seq before disconnect
                                // (spec §8.2 rule 4: never reset datagram seq)
                                if let Some(ref sts) = entry.server_traffic_state {
                                    sess.datagram_send_seq = sts.next_seq;
                                }
                                tracing::info!(
                                    "Session {sid} conn dropped, marking dormant"
                                );
                                sess.mark_dormant();
                            }
                    remove_binding_for_scid(&mut session_bindings, scid);
                }

                // Prune expired dormant sessions
                sessions.retain(|sid, sess| {
                    if sess.is_dormant_expiring() {
                        sess.close_jsonl();
                        tracing::info!("Session {sid} expired, removing");
                        false
                    } else {
                        true
                    }
                });
                session_bindings.retain(|sid, scid| {
                    sessions.contains_key(sid) && active.contains_key(scid)
                });
            }

            _ = measurement_state_interval.tick() => {
                log_measurement_state(&sessions, &active, &session_bindings);
            }

            // — Incoming UDP datagram —
            result = socket.recv_from(&mut udp_buf) => {
                let (len, peer) = match result {
                    Ok(v) => v,
                    Err(e) => {
                        tracing::warn!(?e, "UDP recv error");
                        tokio::time::sleep(Duration::from_millis(100)).await;
                        continue;
                    }
                };
                tracing::trace!(bytes = len, ?peer, "UDP packet received");

                let local_addr = socket.local_addr()?;
                let recv_info = quiche::RecvInfo {
                    from: peer,
                    to: local_addr,
                };

                // Check for stale connections (closed, same peer)
                let stale_peers: Vec<[u8; 16]> = active
                    .iter()
                    .filter(|(_, e)| e.peer == peer && e.conn.is_closed())
                    .map(|(scid, _)| *scid)
                    .collect();
                for scid in &stale_peers {
                    if let Some(entry) = active.remove(scid)
                        && let Some(sid) = entry.session_id
                            && let Some(sess) = sessions.get_mut(&sid) {
                                // Persist datagram send seq before disconnect
                                if let Some(ref sts) = entry.server_traffic_state {
                                    sess.datagram_send_seq = sts.next_seq;
                                }
                                sess.mark_dormant();
                                tracing::info!(
                                    "Session {sid} marked dormant (conn recycled)"
                                );
                            }
                    remove_binding_for_scid(&mut session_bindings, *scid);
                }

                // Route packet by DCID when possible.
                let mut delivered = false;
                let dcid_key = dcid_key_from_packet(&mut udp_buf[..len]);

                if let Some(scid) = dcid_key
                    && let Some(entry) = active.get_mut(&scid)
                {
                    tracing::trace!(?peer, dcid = ?scid, "packet routed by DCID");
                    entry.last_rx_at = Instant::now();
                    if !entry.conn.is_closed() {
                        match entry.conn.recv(&mut udp_buf[..len], recv_info) {
                            Ok(_) | Err(quiche::Error::Done) => {}
                            Err(e) => {
                                tracing::debug!(
                                    ?e,
                                    ?peer,
                                    dcid = ?scid,
                                    "failed to feed packet to mapped connection"
                                );
                            }
                        }

                        delivered = true;
                    }
                }

                if !delivered {
                    tracing::debug!(?peer, "no existing connection, accepting");
                    let mut cid_bytes = [0u8; 16];
                    if let Err(e) = getrandom::fill(&mut cid_bytes) {
                        tracing::error!(?e, "failed to generate server connection id");
                        continue;
                    }
                    let scid = ConnectionId::from_ref(&cid_bytes);
                    match quiche::accept(&scid, None, local_addr, peer, &mut quic_cfg) {
                        Ok(mut conn) => {
                            // Feed the initial CHLO packet
                            if let Err(e) = conn.recv(&mut udp_buf[..len], recv_info) {
                                tracing::debug!(?e, "failed to process initial client packet");
                                continue;
                            }

                            // Flush handshake response immediately
                            flush_conn(&mut conn, &socket, peer).await;

                            let mut stats_interval =
                                tokio::time::interval(Duration::from_millis(
                                    STATS_INTERVAL_MS,
                                ));
                            stats_interval.set_missed_tick_behavior(
                                tokio::time::MissedTickBehavior::Skip,
                            );
                            stats_interval.reset();

                            active.insert(cid_bytes, ActiveConn {
                                conn,
                                rx_buf: Vec::new(),
                                peer,
                                session_id: None,
                                direction: None,
                                bitrate: None,
                                stats_interval,
                                server_traffic_state: None,
                                last_rx_at: Instant::now(),
                                last_stream_at: None,
                                last_dgram_at: None,
                            });
                            tracing::info!(?peer, "new connection");
                        }
                        Err(e) => {
                            tracing::debug!(?e, "accept failed (may be retransmit)");
                        }
                    }
                }

                // Process all connections: Stream 0, datagrams, flush.
                let mut to_remove = Vec::new();
                for (scid, entry) in active.iter_mut() {
                    tracing::trace!(scid = ?scid, "post-recv connection sweep");
                    let alive = process_connection_periodic(
                        entry, &socket,
                    ).await;
                    if !alive || entry.conn.is_closed() {
                        to_remove.push(*scid);
                    }
                }

                let mut rebinds: Vec<(uuid::Uuid, [u8; 16])> = Vec::new();

                // Second pass: read stream + datagrams
                for (scid, entry) in active.iter_mut() {
                    if entry.conn.is_closed() {
                        continue;
                    }
                    // Process Stream 0 messages
                    process_stream_messages(entry, &mut sessions, output_dir);

                    if let Some(sid) = entry.session_id {
                        rebinds.push((sid, *scid));

                        // Process UL datagrams
                        if let Some(sess) = sessions.get_mut(&sid) {
                            process_datagrams(entry, sess);
                        }
                    }

                    // Final flush
                    flush_conn(&mut entry.conn, &socket, entry.peer).await;

                    if entry.conn.is_closed() {
                        to_remove.push(*scid);
                    }
                }

                for (sid, new_scid) in rebinds {
                    if let Some(previous_scid) = session_bindings.get(&sid).copied()
                        && previous_scid != new_scid
                    {
                        tracing::info!(
                            session_id = ?sid,
                            old_scid = ?previous_scid,
                            new_scid = ?new_scid,
                            "rebinding session to new connection"
                        );
                        if let Some(old_entry) = active.remove(&previous_scid) {
                            if let Some(sess) = sessions.get_mut(&sid)
                                && let Some(ref sts) = old_entry.server_traffic_state
                            {
                                sess.datagram_send_seq =
                                    sess.datagram_send_seq.max(sts.next_seq);
                            }

                            tracing::info!(
                                session_id = ?sid,
                                old_scid = ?previous_scid,
                                new_scid = ?new_scid,
                                "session rebound to newer connection"
                            );
                            to_remove.retain(|s| *s != previous_scid);
                        }
                    }

                    session_bindings.insert(sid, new_scid);
                }

                // Clean up dead connections
                for scid in to_remove.drain(..) {
                    if let Some(entry) = active.remove(&scid) {
                        if let Some(sid) = entry.session_id {
                            if let Some(sess) = sessions.get_mut(&sid) {
                                // Persist datagram send seq before disconnect
                                if let Some(ref sts) = entry.server_traffic_state {
                                    sess.datagram_send_seq = sts.next_seq;
                                }
                                tracing::info!(
                                    "Session {sid} conn dropped, marking dormant"
                                );
                                sess.mark_dormant();
                            }
                        } else {
                            tracing::info!("Unknown connection dropped");
                        }
                    }
                    remove_binding_for_scid(&mut session_bindings, scid);
                }

                // Prune expired dormant sessions
                sessions.retain(|sid, sess| {
                    if sess.is_dormant_expiring() {
                        sess.close_jsonl();
                        tracing::info!("Session {sid} expired, removing");
                        false
                    } else {
                        true
                    }
                });
                session_bindings.retain(|sid, scid| {
                    sessions.contains_key(sid) && active.contains_key(scid)
                });
            }
        }
    }
}
