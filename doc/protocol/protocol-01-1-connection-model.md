## 1. Connection Model

### 1.1 QUIC Transport Parameters

| Parameter                  | Value                  | Notes                                         |
| -------------------------- | ---------------------- | --------------------------------------------- |
| Version                    | QUIC v1 (RFC 9000)     |                                               |
| Max bidirectional streams  | 4                      | We use only 1 bidi stream per connection      |
| Max unidirectional streams | 8                      | Reserved for future use                       |
| Max datagram frame size    | **1200 bytes**         | Matches typical IPv4 MTU; our payloads ≤ 1200 |
| CC algorithm               | BBR                    | Set via `set_cc_algorithm(BBR)`               |
| Datagram support           | **enabled** (RFC 9221) | Required for unreliable traffic flow          |
| idle_timeout               | **10 000 ms**          | Server-side; client reconnects faster         |

### 1.2 TLS Configuration

QUIC mandates TLS 1.3. A TLS certificate is therefore mandatory before any data can flow.

- **Certificate source:** The server loads a certificate + private key from disk at startup. In production, this is a valid certificate (e.g. Let's Encrypt) for a real FQDN that resolves to the server's IPv4 address. For local/dev use, a self-signed cert can be generated on-the-fly via `rcgen`.
- **SNI (Server Name Indication):** The client MUST set the correct `server_name` (FQDN) when calling `quiche::connect()`. This is not optional — QUIC implementations require it for certificate verification and for the server to select the right certificate.
- **Client verification:** In production, the client verifies the server certificate chain against the system CA trust store, matching the cert's SAN against the FQDN. In dev mode, a `--insecure` CLI flag disables verification.
- **ALPN:** Exactly `[b"ranmt/0.1"]`. Both client and server must set this.
- **Server socket binding:** The UDP socket binds to `0.0.0.0:<port>` to accept connections on all interfaces, but the **TLS handshake still requires a valid FQDN** to complete. The FQDN is resolved to an IPv4 address by the client, which then targets that IP. The server's certificate must have the FQDN in its SAN list.
- **Only IPv4 addresses** are used. The client resolves the FQDN and selects only A records (no AAAA).

### 1.3 Stream Registry

QUIC assigns stream IDs according to a fixed formula. For bidirectional streams, client-initiated IDs start at **0** and increment by **4** (0, 4, 8, 12, ...). Server-initiated IDs start at **1** and increment by 4 (1, 5, 9, 13, ...).

We use a single client-initiated bidirectional stream for all application data, multiplexed by the JSON `"type"` field. This avoids the complexity of synchronizing reads/writes across multiple bidirectional streams and eliminates the question of stream ordering.

| Purpose                 | QUIC Stream ID | Direction       | How it works                                                       |
| ----------------------- | -------------- | --------------- | ------------------------------------------------------------------ |
| All application data    | **0**          | Bidirectional   | Client opens first. Client writes Handshake + Telemetry. Server reads. Server writes ServerStats back. Both sides read by JSON type. |
| QUIC Datagrams          | N/A            | Both directions | Not a stream. Uses `dgram_send()` / `dgram_recv()` for traffic.   |

#### Why a single stream?

- Opening a second bidirectional stream (e.g., for stats) would use ID **4**. The client would need to accept it from the server side, which requires additional event handling in quiche for server-initiated stream creation.
- Telemetry and stats flow in opposite directions but never compete: the client writes telemetry and reads stats; the server reads telemetry and writes stats. A single bidi stream handles this naturally.
- The JSON `"type"` discriminator cleanly separates the three message types without any stream-level multiplexing overhead.
