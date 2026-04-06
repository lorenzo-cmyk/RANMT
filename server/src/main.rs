use clap::Parser;
use ranmt_shared::*;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;
use tokio::net::UdpSocket;
use rcgen::{generate_simple_self_signed, CertifiedKey};
use quiche::ConnectionId;

/// RANMT Server — measures RAN performance during mobility events.
#[derive(Parser)]
#[command(name = "ranmt-server")]
struct Cli {
    #[arg(long, default_value = "0.0.0.0")]
    bind_addr: String,

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
    config.set_cc_algorithm(quiche::CongestionControlAlgorithm::BBR);
    config.enable_dgram(true, 1024, 1024);
    config.set_max_recv_udp_payload_size(MAX_DGRAM_SIZE);
    config.set_max_send_udp_payload_size(MAX_DGRAM_SIZE);
    config.set_max_idle_timeout(IDLE_TIMEOUT_MS * 1000);
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

fn generate_dev_cert() -> (String, String) {
    let CertifiedKey { cert, key_pair } =
        generate_simple_self_signed(vec!["ranmt-dev.local".to_string()]).unwrap();
    (cert.pem(), key_pair.serialize_pem())
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
            let (cert_pem, key_pem) = generate_dev_cert();
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
        _ => Err(
            "both --cert and --key must be provided, or neither".into(),
        ),
    }
}

fn extract_quic_stats(conn: &quiche::Connection) -> QuicStats {
    let s = conn.stats();
    QuicStats {
        rtt_ms: 0.0,
        tx_bytes: s.sent_bytes,
        rx_bytes: s.recv_bytes,
        cwnd: 0,
        lost_packets: s.lost as u64,
        send_rate_bps: 0,
    }
}

// ─────────────────────────────────────
// Stream 0 processing helpers
// ─────────────────────────────────────

fn process_stream_messages(
    entry: &mut ActiveConn,
    sessions: &mut HashMap<uuid::Uuid, SessionState>,
    output_dir: &PathBuf,
) {
    let stream_id = STREAM_ID;
    let mut tmp = [0u8; 8192];
    loop {
        match entry.conn.stream_recv(stream_id, &mut tmp) {
            Ok((n, _fin)) if n > 0 => {
                entry.rx_buf.extend_from_slice(&tmp[..n]);
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
            match serde_json::from_str::<WireMessage>(line) {
                Ok(WireMessage::Handshake(h)) => {
                    let path = output_dir
                        .join(format!("session_{}.jsonl", h.session_id));

                    if let Some(existing) = sessions.get_mut(&h.session_id) {
                        if existing.is_dormant() {
                            existing.wake();
                            tracing::info!(
                                session_id = ?h.session_id,
                                "session reconnected, waking up"
                            );
                        }
                    } else {
                        let mut sess = SessionState::new(
                            h.session_id,
                            h.direction,
                            h.bitrate_bps,
                            &path,
                        );
                        sess.client_version = h.client_version.clone();
                        sessions.insert(h.session_id, sess);
                    }

                    let ack = WireMessage::HandshakeAck(HandshakeAck {
                        status: HandshakeStatus::Ok,
                        message: String::new(),
                    });
                    let json = serde_json::to_string(&ack).unwrap();
                    let _ = entry.conn.stream_send(0, json.as_bytes(), false);
                    let _ = entry.conn.stream_send(0, b"\n", false);

                    // Initialize traffic pacer for DL
                    if h.direction == Direction::Dl {
                        let interval = calc_traffic_interval(h.bitrate_bps);
                        let mut ivl = tokio::time::interval(interval);
                        ivl.set_missed_tick_behavior(
                            tokio::time::MissedTickBehavior::Delay,
                        );
                        entry.server_traffic_state =
                            Some(ServerTrafficState {
                                interval: ivl,
                                next_seq: 0,
                            });
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
                Ok(WireMessage::Goodbye(_reason)) => {
                    tracing::info!("client said goodbye");
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

fn process_datagrams(
    entry: &mut ActiveConn,
    session: &mut SessionState,
) {
    if entry.direction != Some(Direction::Ul) {
        return;
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
                        seq = seq_num,
                        lost,
                        ?jitter,
                        loss_rate = session.datagram_tracker.loss_rate(),
                        "UL datagram received"
                    );
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

async fn flush_conn(
    conn: &mut quiche::Connection,
    socket: &UdpSocket,
    peer: SocketAddr,
) {
    let mut buf = [0u8; MAX_QUIC_PACKET];
    loop {
        match conn.send(&mut buf) {
            Ok((n, _info)) => {
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
async fn process_connection_periodic(
    entry: &mut ActiveConn,
    socket: &UdpSocket,
) -> bool {
    if entry.conn.is_closed() {
        return false;
    }

    // Process QUIC timeout
    if let Some(timeout) = entry.conn.timeout() {
        if timeout.is_zero() {
            entry.conn.on_timeout();
            if entry.conn.is_closed() {
                return false;
            }
        }
    }

    // Send server stats on interval
    if entry.session_id.is_some() {
        let mut ready = std::future::ready(false);
        let stats_fired = tokio::select! {
            _ = entry.stats_interval.tick() => true,
            _ = &mut ready => false,
        };
        if stats_fired {
            let stats = WireMessage::ServerStats(ServerStats {
                timestamp_ms: current_epoch_ms(),
                quic_stats: extract_quic_stats(&entry.conn),
            });
            let json = serde_json::to_string(&stats).unwrap();
            let _ = entry.conn.stream_send(0, json.as_bytes(), false);
            let _ = entry.conn.stream_send(0, b"\n", false);
        }
    }

    // Traffic pacer (DL only)
    if let Some(sts) = entry.server_traffic_state.as_mut() {
        let mut ready = std::future::ready(false);
        let traffic_fired = tokio::select! {
            _ = sts.interval.tick() => true,
            _ = &mut ready => false,
        };
        if traffic_fired && entry.conn.is_established() {
            let mut payload = [0u8; MAX_DGRAM_SIZE];
            encode_traffic_payload(
                sts.next_seq,
                current_epoch_ms(),
                &mut payload,
            );
            let _ = entry.conn.dgram_send(&payload);
            sts.next_seq += 1;
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

    // Load TLS
    let mut quic_cfg = make_quic_config()?;
    let (cert_path, key_path) = load_or_generate_cert(&cli)?;
    if let (Some(c), Some(k)) = (&cert_path, &key_path) {
        load_server_tls(&mut quic_cfg, c, k)?;
    }

    // Bind UDP socket
    let socket = UdpSocket::bind(format!(
        "{}:{}",
        cli.bind_addr, cli.port
    ))
    .await?;

    let output_dir = &cli.output_dir;
    std::fs::create_dir_all(output_dir)?;

    let mut active: HashMap<[u8; 16], ActiveConn> = HashMap::new();
    let mut sessions: HashMap<uuid::Uuid, SessionState> = HashMap::new();
    let mut udp_buf = vec![0u8; MAX_QUIC_PACKET];

    // Poll interval for periodic server tasks (flushing, timeouts)
    // even when no UDP packets arrive.  This keeps the handshake alive
    // between client packets.
    let mut tick_interval = tokio::time::interval(Duration::from_millis(50));
    tick_interval.set_missed_tick_behavior(
        tokio::time::MissedTickBehavior::Delay,
    );

    loop {
        tokio::select! {
            biased;

            // — Periodic tick: flush pending packets, process timeouts,
            //   send stats/traffic —
            _ = tick_interval.tick() => {
                // Process all connections
                let mut to_remove = Vec::new();
                for (scid, entry) in active.iter_mut() {
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
                    if let Some(entry) = active.remove(&scid) {
                        if let Some(sid) = entry.session_id {
                            if let Some(sess) = sessions.get_mut(&sid) {
                                tracing::info!(
                                    "Session {sid} conn dropped, marking dormant"
                                );
                                sess.mark_dormant();
                            }
                        }
                    }
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
                    if let Some(entry) = active.remove(scid) {
                        if let Some(sid) = entry.session_id {
                            if let Some(sess) = sessions.get_mut(&sid) {
                                sess.mark_dormant();
                                tracing::info!(
                                    "Session {sid} marked dormant (conn recycled)"
                                );
                            }
                        }
                    }
                }

                // Attempt accept if no active connection exists for this peer
                let has_active =
                    active.values().any(|e| e.peer == peer && !e.conn.is_closed());

                if !has_active {
                    let mut cid_bytes = [0u8; 16];
                    getrandom::fill(&mut cid_bytes).unwrap();
                    let scid = ConnectionId::from_ref(&cid_bytes);
                    match quiche::accept(&scid, None, local_addr, peer, &mut quic_cfg) {
                        Ok(mut conn) => {
                            // Feed the initial CHLO packet
                            let _ = conn.recv(&mut udp_buf[..len], recv_info);

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
                            });
                            tracing::info!(?peer, "new connection");
                        }
                        Err(e) => {
                            tracing::debug!(?e, "accept failed (may be retransmit)");
                            continue;
                        }
                    }
                } else {
                    // Feed UDP data to matching connections
                    for entry in active.values_mut() {
                        if !entry.conn.is_closed() && entry.peer == peer {
                            let _ = entry.conn.recv(&mut udp_buf[..len], recv_info);
                        }
                    }

                    // Process all connections: Stream 0, datagrams, flush
                    let mut to_remove = Vec::new();
                    for (scid, entry) in active.iter_mut() {
                        let alive = process_connection_periodic(
                            entry, &socket,
                        ).await;
                        if !alive || entry.conn.is_closed() {
                            to_remove.push(*scid);
                        }
                    }

                    // Second pass: read stream + datagrams
                    for (scid, entry) in active.iter_mut() {
                        if entry.conn.is_closed() {
                            continue;
                        }
                        // Process Stream 0 messages
                        process_stream_messages(entry, &mut sessions, output_dir);

                        // Process UL datagrams
                        if let Some(sid) = entry.session_id {
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

                    // Clean up dead connections
                    for scid in to_remove {
                        if let Some(entry) = active.remove(&scid) {
                            if let Some(sid) = entry.session_id {
                                if let Some(sess) = sessions.get_mut(&sid) {
                                    tracing::info!(
                                        "Session {sid} conn dropped, marking dormant"
                                    );
                                    sess.mark_dormant();
                                }
                            } else {
                                tracing::info!("Unknown connection dropped");
                            }
                        }
                    }
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
            }
        }
    }
}
