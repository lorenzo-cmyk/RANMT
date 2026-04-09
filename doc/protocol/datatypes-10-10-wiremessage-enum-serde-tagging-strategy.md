## 10. `WireMessage` Enum (serde tagging strategy)

This enum is the **sole owner** of the `"type"` JSON key. Individual structs (Handshake, HandshakeAck, etc.) must NOT have `#[serde(tag = "type")]` — that would create nested duplicate keys.

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum WireMessage {
    #[serde(rename = "handshake")]
    Handshake(Handshake),
    #[serde(rename = "handshake_ack")]
    HandshakeAck(HandshakeAck),
    #[serde(rename = "server_stats")]
    ServerStats(ServerStats),
    #[serde(rename = "telemetry")]
    ClientTelemetry(ClientTelemetry),
    #[serde(rename = "goodbye")]
    Goodbye(Goodbye),
}
```

**How serde handles this** for newtype variants with `#[serde(tag = "type")]`:
- **Serialization:** `WireMessage::Handshake(h)` → serde writes `{"type":"handshake", <Handshake fields>}`
- **Deserialization:** `{"type":"handshake", ...}` → serde reads `"type"`, finds `"handshake"`, matches the variant, deserializes the inner struct

Usage in code:
```rust
// Sender side — wrap in enum for automatic type injection
let msg = WireMessage::Handshake(Handshake { ... });
let json = serde_json::to_string(&msg).unwrap();
// → {"type":"handshake","session_id":"...","direction":"dl",...}

// Receiver side — automatic dispatch
let msg: WireMessage = serde_json::from_str(&line).unwrap();
```

**Alternative:** If you prefer to serialize structs directly (without the enum wrapper), manually prepend the `"type"` key:
```rust
let payload = format!("\"type\":\"handshake\",{}", serde_json::to_string(&h).unwrap());
```
The enum approach is preferred — it's less error-prone.

---

