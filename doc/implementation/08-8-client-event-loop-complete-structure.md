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
