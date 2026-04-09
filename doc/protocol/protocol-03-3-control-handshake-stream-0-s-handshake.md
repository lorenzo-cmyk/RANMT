## 3. Control Handshake (Stream 0 → S_HANDSHAKE)

Initiated by the client immediately after the QUIC connection is established.

### 3.1 `Handshake` (Client → Server, exactly 1 per connection)

```jsonc
{
  "type": "handshake",
  "session_id": "550e8400-e29b-41d4-a716-446655440000",  // UUID v4, lowercase
  "direction": "dl",                                     // "dl" | "ul"
  "bitrate_bps": 8000,                                   // target bitrate in bits per second
  "client_version": "0.1.0"                              // protocol version
}
```

| Field            | Type   | Constraints                                 |
| ---------------- | ------ | ------------------------------------------- |
| `type`           | string | Must be `"handshake"`                       |
| `session_id`     | string | RFC 4122 UUID v4, canonical hyphenated form |
| `direction`      | string | `"dl"` (downlink) or `"ul"` (uplink)        |
| `bitrate_bps`    | u32    | 1 000 – 1 000 000 (1 kbps – 1 Mbps)         |
| `client_version` | string | Semver `"major.minor.patch"`                |

**Server behavior on receipt:**
1. Parse and validate all fields.
2. If `direction` is `"dl"` (downlink test): server will send datagram traffic to the client.
3. If `direction` is `"ul"` (uplink test): client will send datagram traffic to the server.
4. If bidirectional: both sides send datagrams (future extension, not yet implemented).
5. Begin sending ServerStats on Stream 0 (server → client direction of the same bidi stream).
6. Begin reading telemetry from Stream 0 (client → server direction).
7. Reply on Stream 0 with `HandshakeAck`.

### 3.2 `HandshakeAck` (Server → Client, exactly 1)

```jsonc
{
  "type": "handshake_ack",
  "status": "ok",           // "ok" | "error"
  "message": ""             // non-empty on error
}
```

**Client behavior:** Client MUST NOT start traffic or telemetry until receiving `{"type":"handshake_ack","status":"ok"}`.
