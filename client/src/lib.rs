use std::collections::VecDeque;
use std::net::SocketAddr;
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use quiche::ConnectionId;
use ranmt_shared::*;

// ─────────────────────────────────────
// Client Configuration
// ─────────────────────────────────────

#[derive(Clone)]
pub struct ClientConfig {
    pub server_addr: String,
    pub server_fqdn: Option<String>,
    pub port: u16,
    pub direction: Direction,
    pub bitrate_bps: u32,
    pub duration: u64,
    pub insecure: bool,
    pub cert_fingerprint: Option<String>,
    pub seed: u64,
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
    config.set_cc_algorithm(quiche::CongestionControlAlgorithm::BBR);
    config.enable_dgram(true, 1024, 1024);
    config.set_max_recv_udp_payload_size(MAX_DGRAM_SIZE);
    config.set_max_send_udp_payload_size(MAX_DGRAM_SIZE);
    config.set_max_idle_timeout(IDLE_TIMEOUT_MS * 75);
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
) -> Result<quiche::Connection, quiche::Error> {
    let mut scid = [0u8; 16];
    getrandom::fill(&mut scid).unwrap();
    let scid = ConnectionId::from_ref(&scid);
    quiche::connect(
        Some(server_fqdn),
        &scid,
        local,
        peer,
        quic_cfg,
    )
}

// ─────────────────────────────────────
// Stream 0 helpers
// ─────────────────────────────────────

fn drain_stream(
    conn: &mut quiche::Connection,
    rx_buf: &mut Vec<u8>,
) -> Vec<WireMessage> {
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

async fn flush_quic(
    conn: &mut quiche::Connection,
    socket: &UdpSocket,
    peer: SocketAddr,
) {
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
        "TX={}B | RX={}B | Lost={}",
        stats.quic_stats.tx_bytes,
        stats.quic_stats.rx_bytes,
        stats.quic_stats.lost_packets,
    );
}

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

/// Non-blocking interval check — returns true if the interval has fired.
async fn tick_check(ivl: &mut tokio::time::Interval) -> bool {
    let mut ready = std::future::ready(false);
    tokio::select! {
        _ = ivl.tick() => true,
        _ = &mut ready => false,
    }
}

/// Non-blocking backlog drain — sends as much as QUIC allows per call.
fn drain_backlog_quic(
    conn: &mut quiche::Connection,
    backlog: &mut VecDeque<String>,
) {
    while let Some(msg) = backlog.front() {
        let mut payload = msg.as_bytes().to_vec();
        payload.push(b'\n');
        match conn.stream_send(STREAM_ID, &payload, false) {
            Ok(_) => { backlog.pop_front(); }
            Err(quiche::Error::Done) => break,
            Err(e) => {
                tracing::warn!(?e, "backlog send failed, dropping");
                backlog.pop_front();
            }
        }
    }
}

// ─────────────────────────────────────
// Public API
// ─────────────────────────────────────

pub async fn run_client(config: ClientConfig) -> Result<(), Box<dyn std::error::Error>> {
    let session_id = uuid::Uuid::new_v4();
    tracing::info!(session_id = %session_id, "starting RANMT client");

    let peer_addr = resolve_ipv4(&config.server_addr, config.port)?;

    loop {
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
        let mut conn = build_client_connection(
            &mut quic_cfg,
            config.sni_hostname(),
            local_addr,
            peer_addr,
        )?;

        // Flush initial handshake packets immediately
        flush_quic(&mut conn, &socket, peer_addr).await;

        let mut rx_buf = Vec::new();
        let mut udp_buf = [0u8; MAX_QUIC_PACKET];
        let mut telemetry_backlog = VecDeque::new();
        let mut handshake_sent = false;
        let mut handshake_done = false;

        let test_start = Instant::now();
        let mut mock_gen = MockTelemetry::new(test_start);

        // Telemetry timer (1 Hz)
        let mut telemetry_interval =
            tokio::time::interval(Duration::from_millis(TELEMETRY_INTERVAL_MS));
        telemetry_interval.set_missed_tick_behavior(
            tokio::time::MissedTickBehavior::Delay,
        );
        let mut traffic_interval =
            tokio::time::interval(if config.direction == Direction::Ul {
                TrafficPacer::new(config.bitrate_bps).interval()
            } else {
                Duration::from_secs(1)
            });
        traffic_interval.set_missed_tick_behavior(
            tokio::time::MissedTickBehavior::Delay,
        );

        let mut pacer = TrafficPacer::new(config.bitrate_bps);
        let mut tracker = LossJitterTracker::new();

        'inner: loop {
            let quic_timeout = match conn.timeout() {
                Some(t) if !t.is_zero() => t,
                _ => Duration::from_millis(TELEMETRY_INTERVAL_MS),
            };

            tokio::select! {
                biased;

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
            }

            if conn.is_closed() {
                break 'inner;
            }

            // Process stream 0 messages
            let msgs = drain_stream(&mut conn, &mut rx_buf);
            for msg in msgs {
                match msg {
                    WireMessage::HandshakeAck(a)
                        if a.status == HandshakeStatus::Ok =>
                    {
                        if !handshake_done {
                            handshake_done = true;
                            drain_backlog_quic(&mut conn, &mut telemetry_backlog);
                            tracing::info!("handshake complete");
                        }
                    }
                    WireMessage::ServerStats(s) => {
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
                            let payload = dgram_buf[..n].to_vec();
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
                let json = serde_json::to_string(&hs).unwrap();
                let _ = conn.stream_send(STREAM_ID, json.as_bytes(), false);
                let _ = conn.stream_send(STREAM_ID, b"\n", false);
                handshake_sent = true;
            }

            // Telemetry tick
            if tick_check(&mut telemetry_interval).await {
                let tel = mock_gen.generate();
                let json = serde_json::to_string(
                    &WireMessage::ClientTelemetry(tel)).unwrap();

                if handshake_done && conn.is_established() {
                    let _ = conn.stream_send(STREAM_ID, json.as_bytes(), false);
                    let _ = conn.stream_send(STREAM_ID, b"\n", false);
                } else {
                    if telemetry_backlog.len() < TELEMETRY_BUFFER_CAP {
                        telemetry_backlog.push_back(json);
                    }
                }
            }

            // Traffic pacer (UL only)
            if config.direction == Direction::Ul
                && conn.is_established()
                && tick_check(&mut traffic_interval).await
            {
                let (_seq, payload) = pacer.next_payload();
                let _ = conn.dgram_send(&payload);
            }

            // Drain telemetry backlog after reconnect
            if handshake_done && conn.is_established() {
                drain_backlog_quic(&mut conn, &mut telemetry_backlog);
            }

            // Test duration check
            if config.duration > 0
                && test_start.elapsed() >= Duration::from_secs(config.duration)
            {
                let msg = serde_json::to_string(
                    &WireMessage::Goodbye(Goodbye { reason: GoodbyeReason::TestComplete })
                ).unwrap();
                let _ = conn.stream_send(STREAM_ID, msg.as_bytes(), true);
                flush_quic(&mut conn, &socket, peer_addr).await;
                break 'inner;
            }

            // Flush QUIC packets to UDP
            flush_quic(&mut conn, &socket, peer_addr).await;

            if conn.is_closed() {
                tracing::info!("Connection lost (QUIC closed)");
                break 'inner;
            }
        }

        tracing::info!("Disconnected, reconnecting in 2s...");
        tokio::time::sleep(Duration::from_millis(RECONNECT_DELAY_MS)).await;
    }
}
