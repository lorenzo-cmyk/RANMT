## 1. `quiche::Config` — Exact Parameters

Every `quiche::Connection` (client and server) must be configured with the same parameters. Code below is the exact sequence of `set_*` calls.

```rust
use quiche::{Config, CongestionControlAlgorithm, ProtocolVersion};

fn make_quic_config() -> Result<Config, quiche::Error> {
    let mut config = Config::new(ProtocolVersion::V1)?;

    // --- BBR (cellular-friendly) ---
    config.set_cc_algorithm(CongestionControlAlgorithm::BBR)?;

    // --- Datagrams (RFC 9221) ---
    config.enable_dgram_recv(true);
    config.enable_dgram_send(true);
    config.set_max_recv_udp_payload_size(1200);
    config.set_max_send_udp_payload_size(1200);

    // --- Idle timeout (10 s) ---
    config.set_max_idle_timeout(10_000); // milliseconds

    // --- Stream limits ---
    config.set_initial_max_streams_bidi(4);
    config.set_initial_max_streams_uni(4);

    // --- Flow control ---
    config.set_initial_max_stream_data_bidi_local(5_242_880);   // 5 MiB
    config.set_initial_max_stream_data_bidi_remote(5_242_880);  // 5 MiB
    config.set_initial_max_stream_data_uni(524_288);            // 512 KiB
    config.set_initial_max_data(10_485_760);                    // 10 MiB

    // --- ALPN ---
    config.set_application_protos(&[b"ranmt/0.1"])?;

    Ok(config)
}
```

### Parameter Rationale

| Parameter | Why this value |
|-----------|---------------|
| `max_idle_timeout = 10 s` | Detects silent disconnects quickly; client reconnects after 2 s. |
| `max_stream_data = 5 MiB (bidi)` | Telemetry backlog flush can be ~2 MiB (10 000 entries × ~200 bytes). 5 MiB avoids flow-control stalls. |
| `max_data = 10 MiB` | Connection-level ceiling. At 8 kbps, 10 MiB ≈ 10 000 seconds of data. |
| `max_send/recv_udp_payload = 1200` | Matches `MAX_DGRAM_SIZE`, avoids IP fragmentation. |

---

