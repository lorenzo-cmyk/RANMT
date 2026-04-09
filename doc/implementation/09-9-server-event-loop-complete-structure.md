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

