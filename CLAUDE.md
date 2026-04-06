# RAN Mobility Tester (RANMT) - AI Agent Instructions

## 1. Project Overview
You are an expert Rust developer and telecommunications engineer building **RANMT** (RAN Mobility Tester), a client-server application to measure Radio Access Network (RAN) performance during mobility events (e.g., cell handovers, tunnels).

**Critical Constraints & Future Proofing:**
1.  **Network Engine:** You MUST use **Cloudflare's `quiche`** for the QUIC state machine, implementing the UDP socket I/O manually via `tokio`.
2.  **IPv4 Strictly:** The network environment is strictly IPv4. The server must bind to `0.0.0.0`, and the client must only resolve IPv4 addresses. Do not implement IPv6 support.
3.  **Android & UniFFI Future:** The client `src/lib.rs` must be structured so it can eventually be compiled via `uniffi` into an Android App (Kotlin). Avoid `std::process::exit` or standard library threads.
4.  **Long Disconnects:** The client will experience complete coverage loss (e.g., entering a tunnel). You must implement a robust **Reconnection Loop** with an application-level **Session ID** so the server can stitch resumed tests together.

## 2. Tech Stack & Libraries
*   **Language:** Rust (Edition 2021)
*   **Async Runtime:** `tokio` (`UdpSocket`, `time`, `select!`)
*   **QUIC State Machine:** `quiche`
*   **Serialization:** `serde`, `serde_json`
*   **CLI & Utils:** `clap`, `uuid`
*   **Logging:** `tracing`, `tracing-subscriber`

## 3. Architecture & Data Flow
Each test generates a unique UUID `Session ID` on the client.
We use a mix of Reliable Streams and Unreliable Datagrams:

1.  **Stream 0 (Single bidirectional stream):** All control messages (Handshake, ServerStats, Telemetry, Goodbye) are multiplexed over one bidirectional stream. Messages are distinguished by the JSON `"type"` field (internal tagging via `WireMessage` enum). Do NOT use multiple streams.
2.  **QUIC Datagrams (Traffic Flow):** Uses the QUIC Datagram Extension (RFC 9221). The 8kbps dummy traffic flows here. Being unreliable allows us to accurately measure true over-the-air packet loss without QUIC retransmissions hiding it.

## 4. Telemetry & Stats Requirements
*   **`ClientTelemetry` struct:** Include `timestamp`, `lat`, `lon`, `speed`, `network_type`, `cell_id`, `pci`, `earfcn`, `rsrp`, `rsrq`, `sinr`. *(Generate oscillating mock data in the CLI for now).*
*   **`ServerStats` struct:** Map from `quiche::Stats` (rtt, tx_bytes, rx_bytes, lost_packets).
*   **Traffic Payload struct:** Sent via Datagrams. Must contain `seq_num` (u64), `send_ts` (u64 unix ms), and padding bytes to match the bitrate.

## 5. Advanced Mechanics (Crucial)

### 5.1 Store-and-Forward Telemetry (The Tunnel Problem)
When the client loses network connectivity, telemetry MUST NOT be lost. 
*   Implement a local `VecDeque` memory buffer on the Client.
*   While disconnected, keep generating telemetry and push it to this buffer.
*   Upon successful reconnection, flush the backlog to the server via Stream 0 before resuming real-time streaming.

### 5.2 Congestion Control & Jitter
*   **BBR:** Configure `quiche::Config` to use BBR (`config.set_cc_algorithm(quiche::CongestionControlAlgorithm::BBR)`). Standard CUBIC distorts RTT on cellular due to bufferbloat.
*   **Relative Jitter:** Do not calculate absolute One-Way Delay (clocks drift). Instead, use the `send_ts` and Datagram arrival times to calculate Relative Jitter (Packet Delay Variation) and track gap-loss using `seq_num`.

## 6. Step-by-Step Implementation Plan
AI Agent, execute the following steps in strict order.

### Phase 1: Project Scaffolding & Structs
1. Initialize Cargo workspace (`server`, `client`).
2. Define the shared structs (`Session ID`, `ClientTelemetry`, `ServerStats`, `TrafficPayload`).

### Phase 2: Quiche Event Loop & Reconnection
1. Generate in-memory certs (`rcgen`) for the server. Enable Datagrams in `quiche::Config`.
2. **Server:** Implement the UDP loop binding to `0.0.0.0:<PORT>`. Map Connection IDs. Route timeouts correctly.
3. **Client:** Implement the `quiche` loop inside a `tokio` Reconnection Loop. If connection drops, wait 2s and retry to the IPv4 address.
4. Prove basic connectivity and robust reconnection if the server restarts.

### Phase 3: Handshake & Store-and-Forward Telemetry
1. Client connects and sends the Handshake via Stream 0.
2. Start the Telemetry loop. Implement the `VecDeque` store-and-forward logic. Send via Stream 0.
3. Server receives Telemetry and appends it to `session_<uuid>.jsonl`.

### Phase 4: Traffic Generation & Datagrams
1. Implement the pacing algorithm (target: default 8kbps).
2. Format `TrafficPayload` and send via `conn.dgram_send()`.
3. The receiving side must read via `conn.dgram_recv()`, calculate packet loss based on `seq_num`, calculate relative jitter, and log it.

### Phase 5: Stats & Refinement
1. Server periodically extracts `conn.stats()` and sends via Stream 0. Client displays this.
2. Abstract Client logic in `lib.rs` for future `uniffi` usage (ensure clean shutdown without `std::process::exit`).

## 7. Critical Coding Rules
*   **The Quiche Paradigm:** `quiche` performs NO I/O. You handle `UdpSocket` reads/writes yourself.
*   **Handling `quiche` Timers:** You MUST integrate `conn.timeout()` into your `tokio::select!` loops and call `conn.on_timeout()`. If you forget, QUIC stalls.
*   **Error Handling:** Never `.unwrap()` socket or parsing errors. Network drops are normal here. Break the inner loop, log the error, and let the outer Reconnection Loop take over.
*   **Strict IPv4:** Ensure all sockets explicitly bind/connect to IPv4 addresses only.

