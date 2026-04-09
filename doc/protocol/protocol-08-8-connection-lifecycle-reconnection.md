## 8. Connection Lifecycle & Reconnection

### 8.1 Full Lifecycle (DL test example)

```
  Client                                          Server
    |                                                 |
    | ---- QUIC handshake (TLS 1.3, 1-RTT) ---------> |
    | ---- {"type":"handshake", ...} (Stream 0) ----> |
    | <--- {"type":"handshake_ack","status":"ok"} --- |
    |                                                 |
    | ---- {"type":"telemetry",...} (Stream 0) ----->  | (periodic)
    | <--- {"type":"server_stats",...} (Stream 0) --- | (periodic)
    | <--- [datagrams: traffic] --------------------- | (pacing loop)
    |                                                 |
    | ==== [DISCONNECT: tunnel, handover, timeout] == |
    |                                                 |
    | (wait 2s, retry: build new QUIC conn)           |
    | ---- QUIC re-connect -------------------------> |
    | ---- {"type":"handshake", same session_id} --> |
    | <--- {"type":"handshake_ack","status":"ok"} --- |
    | ---- flush stored telemetry (Stream 0) ------->  |
    | ---- resume telemetry + receive stats --------- |
    | <--- resume datagrams ------------------------ |
```

### 8.2 Reconnection Rules

1. **Trigger:** ANY of the following triggers reconnection:
   - `conn.is_closed()` returns `true` (after `on_timeout()` or `recv()`).
   - `conn.recv()` returns `Err(_)`.
   - UDP socket error on `recv_from()`.
   - Any `quiche::Error::Done` from `stream_recv`, `dgram_recv`, or `conn.send()` is **NORMAL** — it does NOT trigger disconnect. Only stream/socket-level failures do.

2. **Reconnection delay:** **2000 ms** (fixed, not exponential backoff). While disconnected, telemetry continues to be generated and buffered (see Section 7).

3. **Session ID persistence:** The `session_id` UUID is **constant** across all reconnections within the same test run. This allows the server to stitch all data into the same `session_<uuid>.jsonl` file.

4. **Seq_num continuity:** `seq_num` for datagrams **is never reset**. It continues incrementing from the last value, even across reconnections. The same applies to telemetry `seq_num`. This ensures that post-processing tools can detect gaps and calculate true data completeness across the entire session, regardless of how many disconnections occurred.

5. **Server-side reconnect handling:** The server does **not** actively probe for dead connections. It relies on:
  - QUIC `idle_timeout` (10 s) to detect when a client has silently dropped.
   - A new QUIC connection arriving with a `Handshake` containing a `session_id` that matches an existing session. When this happens, the server:
     1. Tears down the old (dead) connection and its associated resources (stats ticker, traffic pacer).
     2. Associates the **new** QUIC connection with the existing `SessionState`.
     3. Sends `HandshakeAck` and resumes sending stats and datagrams on the new connection.
     4. **Does NOT truncate or reset** the JSONL file — it continues appending.

### 8.3 Typical Disconnect / Reconnect Timeline (Tunnel Scenario)

```
  Time      Client State              Server State
──────────  ─────────────────────────────────────────────────────────
  T+0s      Connected, sending telem.  Active session, sending DL traffic
  T+5s      Enters tunnel (no IP)     Still sending → UDP loss, no ACK
  T+10s     QUIC detects loss         idle_timeout timer still running
             (breaks event loop)
  T+12s     Starts 2s reconnect wait  idle_timeout still ticking
  T+14s     Retry #1 (socket fails)   —
  T+16s     Retry #2 (socket fails)   —
  ...
  T+15s     Old QUIC conn timed out   Server cleans up old conn,
             on server                  but SessionState kept alive
                                        (session is "dormant")
  T+40s     Tunnel exit. Retry N.     —
  T+40.5s   DNS resolve OK.           —
             New QUIC handshake
  T+41s     Handshake sent (same sid) Server: "Hey, session X back!"
             → Stream 0                → Tears old conn, binds new conn
  T+41.1s   Receives HandshakeAck     → Sends stats on Stream 0
  T+41.1s   Flushes backlog (18 tel.) → Appends all to session_X.jsonl
  T+42s     Normal operation resumes   → Resumes DL traffic pacer
                                       → Resumes stats ticker
```

#### Key behaviors during the disconnected period (T+5s → T+42s):

| Behavior                          | What happens                                                                                                                                                                                                                              |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Telemetry generation              | Continues at 1 Hz. Entries pushed to `VecDeque` buffer.                                                                                                                                                                                   |
| Datagram seq_num                  | **Paused** — no traffic sent or received while disconnected.                                                                                                                                                                              |
| Buffer overflow (>10 000 entries) | Oldest entries silently dropped. ~2h46m of telemetry at 1 Hz before loss.                                                                                                                                                                 |
| Server old conn cleanup           | After 10s idle timeout, QUIC conn is closed. SessionState marked "dormant".                                                                                                                                                               |
| Server dormant session lifetime   | **24 hours max**. If no reconnect within 24h, SessionState is evicted and JSONL closed.                                                                                                                                                   |
| Post-reconnect jitter calculation | Jitter will show a **massive spike** for the first datagram after reconnect (large `delta_arrival`). This is expected and should be visible in analysis but not skew EWMA excessively — EWMA naturally dampens single outliers over time. |

### 8.4 Multi-Disconnect Edge Cases

- **Server restart while client is disconnected:** Upon reconnect, the server has no memory of the session. The client sends a `Handshake` with its existing `session_id`. The server creates a **new** `SessionState` with the same UUID and opens the existing JSONL file in append mode. The JSONL file path is `session_<uuid>.jsonl`, so it will naturally append to the pre-existing file. This means a server restart **does not lose data** — the session continuity is preserved on disk. However, per-connection state (stats ticker, traffic pacer, datagram tracker) is rebuilt from scratch.

- **Client-side `seq_num` for datagrams while offline:** The seq_num counter is **not** incremented during disconnection. It only increments on actual `dgram_send()` calls. This ensures the receiver can still use gaps in `seq_num` to identify lost packets vs. offline periods.

- **Concurrent sessions on server:** The server supports multiple active sessions (different `session_id` values) simultaneously. Each session has its own QUIC connection, JSONL file, stats ticker, and traffic pacer.
