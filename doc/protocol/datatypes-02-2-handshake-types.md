## 2. Handshake Types

### 2.1 `Handshake` (Client → Server, Stream 0)

```rust
use serde::{Serialize, Deserialize};
use uuid::Uuid;

/// No `#[serde(tag)]` here — the `WireMessage` enum owns the `"type"` key.
/// When serialized directly (for sending), we manually prepend `"type"`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Handshake {
    /// RFC 4122 UUID v4, hyphenated lowercase
    pub session_id: Uuid,
    /// Direction of the test
    pub direction: Direction,
    /// Target bitrate in bits per second
    pub bitrate_bps: u32,
    /// Protocol version for future compatibility checks
    pub client_version: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Direction {
    Dl, // downlink: server → client traffic
    Ul, // uplink: client → server traffic
}
```

**Wire format (JSON, `\n`-terminated):**
```json
{"type":"handshake","session_id":"550e8400-e29b-41d4-a716-446655440000","direction":"dl","bitrate_bps":8000,"client_version":"0.1.0"}\n
```

### 2.2 `HandshakeAck` (Server → Client, Stream 0)

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HandshakeAck {
    pub status: HandshakeStatus,
    pub message: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HandshakeStatus {
    Ok,
    Error,
}
```

### 2.3 `Goodbye` (Client → Server, Stream 0)

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Goodbye {
    pub reason: GoodbyeReason,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoodbyeReason {
    TestComplete,
    UserAbort,
    Error,
}
```
