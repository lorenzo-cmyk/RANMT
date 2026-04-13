use quiche::ConnectionId;
use ranmt_shared::*;
use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

#[cfg(feature = "ffi")]
uniffi::setup_scaffolding!();

#[cfg(feature = "ffi")]
mod ffi;

// ─────────────────────────────────────
// Client Configuration
// ─────────────────────────────────────

#[derive(Clone)]
pub struct ClientConfig {
    pub server_addr: String,
    pub server_fqdn: Option<String>,
    pub session_id: Option<String>,
    pub port: u16,
    pub direction: Direction,
    pub bitrate_bps: u32,
    pub duration: u64,
    pub insecure: bool,
    pub seed: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClientConnectionState {
    Connecting,
    Connected,
    Reconnecting,
    Disconnected,
}

#[derive(Debug, Clone)]
pub struct ClientSnapshot {
    pub connection_state: ClientConnectionState,
    pub last_stats: Option<ServerStats>,
}

async fn update_state(
    state: &Option<Arc<Mutex<ClientSnapshot>>>,
    new_state: ClientConnectionState,
) {
    let Some(shared) = state else {
        return;
    };
    let mut snapshot = shared.lock().await;
    snapshot.connection_state = new_state;
}

async fn update_stats(state: &Option<Arc<Mutex<ClientSnapshot>>>, stats: ServerStats) {
    let Some(shared) = state else {
        return;
    };
    let mut snapshot = shared.lock().await;
    snapshot.last_stats = Some(stats);
}

impl ClientConfig {
    pub fn sni_hostname(&self) -> &str {
        self.server_fqdn.as_deref().unwrap_or(&self.server_addr)
    }
}

// ─────────────────────────────────────
// QUIC Config
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

fn build_client_connection(
    quic_cfg: &mut quiche::Config,
    server_fqdn: &str,
    local: SocketAddr,
    peer: SocketAddr,
) -> Result<quiche::Connection, Box<dyn std::error::Error>> {
    let mut scid = [0u8; 16];
    getrandom::fill(&mut scid)?;
    let scid = ConnectionId::from_ref(&scid);
    Ok(quiche::connect(
        Some(server_fqdn),
        &scid,
        local,
        peer,
        quic_cfg,
    )?)
}

// ─────────────────────────────────────
// Stream 0 helpers
// ─────────────────────────────────────

fn drain_stream(conn: &mut quiche::Connection, rx_buf: &mut Vec<u8>) -> Vec<WireMessage> {
    let mut msgs = Vec::new();
    let mut tmp = [0u8; 8192];
    loop {
        match conn.stream_recv(STREAM_ID, &mut tmp) {
            Ok((n, _fin)) if n > 0 => {
                rx_buf.extend_from_slice(&tmp[..n]);
            }
            Ok(_) | Err(quiche::Error::Done) => break,
            Err(quiche::Error::InvalidStreamState(_)) => break,
            Err(e) => {
                tracing::warn!(?e, "stream_recv error");
                break;
            }
        }
    }

    if let Some(pos) = rx_buf.iter().rposition(|&b| b == b'\n') {
        let complete = rx_buf.drain(..=pos).collect::<Vec<u8>>();
        let text = String::from_utf8_lossy(&complete);
        for line in text.split('\n').filter(|s| !s.is_empty()) {
            match serde_json::from_str::<WireMessage>(line) {
                Ok(msg) => msgs.push(msg),
                Err(e) => tracing::warn!(%e, "malformed JSON on stream"),
            }
        }
    }
    msgs
}

async fn flush_quic(conn: &mut quiche::Connection, socket: &UdpSocket, peer: SocketAddr) {
    let mut buf = [0u8; MAX_QUIC_PACKET];
    loop {
        let (write_len, _send_info) = match conn.send(&mut buf) {
            Ok(v) => v,
            Err(quiche::Error::Done) => return,
            Err(e) => {
                tracing::warn!(?e, "conn.send() error");
                return;
            }
        };
        if let Err(e) = socket.send_to(&buf[..write_len], peer).await {
            tracing::warn!(?e, "send_to failed");
        }
    }
}

fn display_stats(stats: &ServerStats) {
    tracing::info!(
        "RTT={:.1}ms | TX={}B | RX={}B | CWND={}B | Lost={} | Rate={}bps",
        stats.quic_stats.rtt_ms,
        stats.quic_stats.rx_bytes, // Client TX is Server RX
        stats.quic_stats.tx_bytes, // Client RX is Server TX
        stats.quic_stats.cwnd,
        stats.quic_stats.lost_packets,
        stats.quic_stats.send_rate_bps,
    );
}

fn serialize_message(msg: &WireMessage) -> Option<String> {
    match serde_json::to_string(msg) {
        Ok(v) => Some(v),
        Err(e) => {
            tracing::warn!(?e, "failed to serialize stream message");
            None
        }
    }
}

fn try_send_line(conn: &mut quiche::Connection, line: &str, is_final: bool) -> bool {
    let mut payload = line.as_bytes().to_vec();
    payload.push(b'\n');

    match conn.stream_send(STREAM_ID, &payload, is_final) {
        Ok(_) => true,
        Err(quiche::Error::Done) => false,
        Err(e) => {
            tracing::warn!(?e, "stream_send failed");
            false
        }
    }
}

fn push_backlog(backlog: &mut VecDeque<String>, msg: String) {
    if backlog.len() >= TELEMETRY_BUFFER_CAP {
        backlog.pop_front();
    }
    backlog.push_back(msg);
}

/// Non-blocking backlog drain — sends as much as QUIC allows per call.
fn drain_backlog_quic(conn: &mut quiche::Connection, backlog: &mut VecDeque<String>) {
    while let Some(msg) = backlog.front() {
        if try_send_line(conn, msg, false) {
            backlog.pop_front();
        } else {
            break;
        }
    }
}

// ─────────────────────────────────────
// Public API
// ─────────────────────────────────────

pub async fn run_client(config: ClientConfig) -> Result<(), Box<dyn std::error::Error>> {
    run_client_with_cancel(config, CancellationToken::new()).await
}

pub async fn run_client_with_cancel(
    config: ClientConfig,
    cancel: CancellationToken,
) -> Result<(), Box<dyn std::error::Error>> {
    run_client_with_state(config, cancel, None, None).await
}

pub async fn run_client_with_state(
    config: ClientConfig,
    cancel: CancellationToken,
    state: Option<Arc<Mutex<ClientSnapshot>>>,
    mut telemetry_rx: Option<tokio::sync::mpsc::UnboundedReceiver<ranmt_shared::ClientTelemetry>>,
) -> Result<(), Box<dyn std::error::Error>> {
    if let Some(shared) = &state {
        let mut snapshot = shared.lock().await;
        *snapshot = ClientSnapshot {
            connection_state: ClientConnectionState::Connecting,
            last_stats: None,
        };
    }

    let session_id = config
        .session_id
        .as_ref()
        .and_then(|id| {
            match core::str::FromStr::from_str(id) {
                Ok(uuid) => Some(uuid),
                Err(_) => {
                    tracing::warn!(session_id = %id, "failed to parse session_id as UUID, generating new one");
                    None
                }
            }
        })
        .unwrap_or_else(uuid::Uuid::new_v4);

    tracing::info!(session_id = %session_id, "starting RANMT client");

    let peer_addr = resolve_ipv4(&config.server_addr, config.port)?;

    // Persist stateful counters across reconnects (spec §8.2 rule 4)
    let test_start = Instant::now();
    let mut telemetry_seq: u64 = 0;
    let mut datagram_seq: u64 = 0;
    let mut mock_gen: Option<MockTelemetry> = None;
    let mut telemetry_backlog: VecDeque<String> = VecDeque::new();

    let mut test_complete = false;

    loop {
        if cancel.is_cancelled() {
            update_state(&state, ClientConnectionState::Disconnected).await;
            return Ok(());
        }
        update_state(&state, ClientConnectionState::Connecting).await;
        tracing::info!(
            "Connecting to {} (port {})...",
            config.server_addr,
            config.port
        );

        let socket = UdpSocket::bind("0.0.0.0:0").await?;
        let local_addr = socket.local_addr()?;

        let mut quic_cfg = make_quic_config()?;
        if config.insecure {
            quic_cfg.verify_peer(false);
        }
        let mut conn =
            build_client_connection(&mut quic_cfg, config.sni_hostname(), local_addr, peer_addr)?;

        // Flush initial handshake packets immediately
        flush_quic(&mut conn, &socket, peer_addr).await;

        let mut rx_buf = Vec::new();
        let mut udp_buf = [0u8; MAX_QUIC_PACKET];
        let mut handshake_sent = false;
        let mut handshake_done = false;

        let mut mock_gen = match mock_gen.take() {
            Some(g) => g,
            None => MockTelemetry::with_seed_seq(test_start, telemetry_seq, config.seed),
        };

        // Telemetry timer (1 Hz)
        let mut telemetry_interval =
            tokio::time::interval(Duration::from_millis(TELEMETRY_INTERVAL_MS));
        telemetry_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        let mut traffic_interval = tokio::time::interval(if config.direction == Direction::Ul {
            TrafficPacer::new(config.bitrate_bps).interval()
        } else {
            Duration::from_secs(1)
        });
        traffic_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

        let mut pacer = TrafficPacer::with_seq(config.bitrate_bps, datagram_seq);

        let mut shutdown_requested = false;

        'inner: loop {
            let quic_timeout = match conn.timeout() {
                Some(t) if !t.is_zero() => t,
                _ => Duration::from_millis(TELEMETRY_INTERVAL_MS),
            };

            tokio::select! {
                biased;

                _ = cancel.cancelled() => {
                    shutdown_requested = true;
                    if conn.is_established() {
                        if let Some(msg) = serialize_message(
                            &WireMessage::Goodbye(Goodbye {
                                reason: GoodbyeReason::UserAbort,
                            })
                        ) {
                            let _ = try_send_line(&mut conn, &msg, true);
                        }
                        flush_quic(&mut conn, &socket, peer_addr).await;
                    }
                    update_state(&state, ClientConnectionState::Disconnected).await;
                    break 'inner;
                }

                // QUIC timeout fires
                _ = tokio::time::sleep(quic_timeout) => {
                    conn.on_timeout();
                    if conn.is_closed() {
                        tracing::warn!("QUIC connection closed after timeout");
                        break 'inner;
                    }
                }

                // Incoming data from server
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
                        from: src,
                        to: local_addr,
                    };
                    if conn.recv(&mut udp_buf[..len], recv_info).is_err() {
                        tracing::warn!("recv error, reconnecting");
                        break 'inner;
                    }
                }

                // Telemetry generation must continue even during silent links.
                tel_opt = async {
                    if let Some(rx) = telemetry_rx.as_mut() {
                        match rx.recv().await { Some(t) => Some(t), None => std::future::pending().await }
                    } else {
                        telemetry_interval.tick().await;
                        Some(mock_gen.generate())
                    }
                } => {
                    let Some(mut tel) = tel_opt else { continue; };
                    tel.seq_num = telemetry_seq;
                    telemetry_seq += 1;

                    let Some(json) = serialize_message(
                        &WireMessage::ClientTelemetry(tel)
                    ) else {
                        continue;
                    };

                    if handshake_done
                        && conn.is_established()
                        && telemetry_backlog.is_empty()
                    {
                        if !try_send_line(&mut conn, &json, false) {
                            push_backlog(&mut telemetry_backlog, json);
                        }
                    } else {
                        push_backlog(&mut telemetry_backlog, json);
                    }
                }

                _ = traffic_interval.tick(),
                    if config.direction == Direction::Ul
                        && conn.is_established()
                        && handshake_done
                => {
                    let mut payload = [0u8; MAX_DGRAM_SIZE];
                    encode_traffic_payload(
                        pacer.next_seq(),
                        current_epoch_ms(),
                        &mut payload,
                    );
                    match conn.dgram_send(&payload) {
                        Ok(_) => {
                            pacer.mark_sent();
                        }
                        Err(quiche::Error::Done) => {}
                        Err(e) => tracing::warn!(?e, "dgram_send error"),
                    }
                }
            }

            if conn.is_closed() {
                break 'inner;
            }

            // Process stream 0 messages
            let msgs = drain_stream(&mut conn, &mut rx_buf);
            for msg in msgs {
                match msg {
                    WireMessage::HandshakeAck(a) if a.status == HandshakeStatus::Ok => {
                        if !handshake_done {
                            handshake_done = true;
                            update_state(&state, ClientConnectionState::Connected).await;
                            drain_backlog_quic(&mut conn, &mut telemetry_backlog);
                            tracing::info!("handshake complete");
                        }
                    }
                    WireMessage::HandshakeAck(a) => {
                        tracing::warn!(
                            err = %a.message,
                            "server rejected handshake"
                        );
                        let _ = conn.close(true, 0x00, b"handshake rejected");
                        break 'inner;
                    }
                    WireMessage::ServerStats(s) => {
                        update_stats(&state, s.clone()).await;
                        display_stats(&s);
                    }
                    _ => {}
                }
            }

            // Process incoming datagrams (DL test)
            if config.direction == Direction::Dl {
                let mut dgram_buf = vec![0u8; MAX_DGRAM_SIZE];
                loop {
                    match conn.dgram_recv(&mut dgram_buf) {
                        Ok(n) if n > 0 => {
                            if n != MAX_DGRAM_SIZE {
                                tracing::warn!(size = n, "DL datagram size mismatch, skipping");
                                continue;
                            }
                            if let Some((seq, send_ts)) = decode_traffic_payload(&dgram_buf[..n]) {
                                let now = current_epoch_ms();
                                let owd_ms = now.saturating_sub(send_ts) as f64;
                                tracing::debug!(seq, owd_ms, "DL datagram received");
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

            // Send handshake via stream 0 once connection is established
            if !handshake_sent && conn.is_established() {
                let hs = WireMessage::Handshake(Handshake {
                    session_id,
                    direction: config.direction,
                    bitrate_bps: config.bitrate_bps,
                    client_version: "0.1.0".into(),
                });
                if let Some(json) = serialize_message(&hs) {
                    #[allow(clippy::collapsible_if)]
                    if try_send_line(&mut conn, &json, false) {
                        handshake_sent = true;
                    }
                }
            }

            // Drain telemetry backlog after reconnect
            if handshake_done && conn.is_established() {
                drain_backlog_quic(&mut conn, &mut telemetry_backlog);
            }

            // Test duration check
            if config.duration > 0 && test_start.elapsed() >= Duration::from_secs(config.duration) {
                if let Some(msg) = serialize_message(&WireMessage::Goodbye(Goodbye {
                    reason: GoodbyeReason::TestComplete,
                })) {
                    let _ = try_send_line(&mut conn, &msg, true);
                }
                flush_quic(&mut conn, &socket, peer_addr).await;
                test_complete = true;
                update_state(&state, ClientConnectionState::Disconnected).await;
                break 'inner;
            }

            // Flush QUIC packets to UDP
            flush_quic(&mut conn, &socket, peer_addr).await;

            if conn.is_closed() {
                tracing::info!("Connection lost (QUIC closed)");
                break 'inner;
            }
        }

        if test_complete {
            return Ok(());
        }

        if shutdown_requested {
            return Ok(());
        }

        update_state(&state, ClientConnectionState::Reconnecting).await;

        tracing::info!("Disconnected, reconnecting in 2s...");

        let reconnect_deadline =
            tokio::time::Instant::now() + Duration::from_millis(RECONNECT_DELAY_MS);
        loop {
            tokio::select! {
                _ = cancel.cancelled() => {
                    update_state(&state, ClientConnectionState::Disconnected).await;
                    return Ok(());
                }
                _ = tokio::time::sleep_until(reconnect_deadline) => break,
                tel_opt = async {
                    if let Some(rx) = telemetry_rx.as_mut() {
                        match rx.recv().await { Some(t) => Some(t), None => std::future::pending().await }
                    } else {
                        telemetry_interval.tick().await;
                        Some(mock_gen.generate())
                    }
                } => {
                    let Some(mut tel) = tel_opt else { continue; };
                    tel.seq_num = telemetry_seq;
                    telemetry_seq += 1;
                    if let Some(json) = serialize_message(
                        &WireMessage::ClientTelemetry(tel)
                    ) {
                        push_backlog(&mut telemetry_backlog, json);
                    }
                }
            }
        }

        // Persist state across reconnects (spec §8.2 rule 4: never reset seq_nums)
        mock_gen.seq_num = telemetry_seq;
        datagram_seq = pacer.next_seq();
    }
}
