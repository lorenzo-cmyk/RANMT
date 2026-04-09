## 6. UDP Socket I/O Pattern

### 6.1 Flush QUIC to UDP (common to both client and server)

After every QUIC operation that might produce packets (connect, accept, `stream_send`, `on_timeout`, dgram), **immediately** flush to the socket:

```rust
const MAX_QUIC_PACKET: usize = 1500;

async fn flush_quic(
    conn: &mut quiche::Connection,
    socket: &tokio::net::UdpSocket,
    peer: std::net::SocketAddr,
) -> std::io::Result<()> {
    let mut buf = [0u8; MAX_QUIC_PACKET];
    loop {
        let write_len = match conn.send(&mut buf) {
            Ok(n) => n,
            Err(quiche::Error::Done) => return Ok(()),
            Err(e) => {
                tracing::warn!(?e, "conn.send() returned error");
                return Err(std::io::Error::other(e.to_string()));
            }
        };
        if let Err(e) = socket.send_to(&buf[..write_len], peer).await {
            tracing::warn!(?e, "send_to failed");
        }
    }
}
```

> **Why 1500?** With `max_send_udp_payload_size = 1200`, quiche's packets stay well within standard MTU. 1500 is safe for local/LAN.

### 6.2 UDP Bind/Connect

**Client** — binds local UDP socket to an ephemeral port:
```rust
let socket = tokio::net::UdpSocket::bind("0.0.0.0:0").await?;
socket.connect(peer_addr).await?; // connect for routing
```

**Server** — binds to explicit port:
```rust
let socket = tokio::net::UdpSocket::bind(
    &format!("0.0.0.0:{port}")
).await?;
```
