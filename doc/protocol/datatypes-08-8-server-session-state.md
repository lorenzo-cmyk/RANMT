## 8. Server Session State

```rust
use std::fs::File;
use std::path::PathBuf;
use std::time::Instant;

pub const DORMANT_TIMEOUT_HOURS: u64 = 24;

pub struct SessionState {
    pub session_id: Uuid,
    pub direction: Direction,
    pub bitrate_bps: u32,
    pub client_version: String,
    pub jsonl_path: PathBuf,
    /// Open JSONL file handle for telemetry (append mode)
    pub jsonl_file: File,
    /// Datagram loss/jitter tracking (for UL tests)
    pub datagram_tracker: LossJitterTracker,
    /// Timestamp when the QUIC connection was lost (None = active)
    pub dormant_since: Option<Instant>,
}

impl SessionState {
    pub fn new(session_id: Uuid, direction: Direction,
               bitrate_bps: u32, jsonl_path: &Path) -> Self {
        let jsonl_file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(jsonl_path)
            .expect("failed to open jsonl");
        Self {
            session_id,
            direction,
            bitrate_bps,
            client_version: String::new(),
            jsonl_path: jsonl_path.to_owned(),
            jsonl_file,
            datagram_tracker: LossJitterTracker::new(),
            dormant_since: None,
        }
    }

    pub fn write_telemetry(&mut self, tel: &ClientTelemetry) {
        use std::io::Write;
        // Serialize through WireMessage to get "type":"telemetry" in output
        let line = serde_json::to_string(&WireMessage::ClientTelemetry(tel.clone())).unwrap();
        let _ = writeln!(self.jsonl_file, "{line}");
        let _ = self.jsonl_file.sync_all();
    }

    /// Mark the session as dormant (QUIC connection lost).
    pub fn mark_dormant(&mut self) {
        self.dormant_since = Some(Instant::now());
        let _ = self.jsonl_file.sync_all();
    }

    /// Check if this dormant session has expired its 24h window.
    pub fn is_dormant_expiring(&self) -> bool {
        match self.dormant_since {
            Some(since) => since.elapsed()
                >= std::time::Duration::from_secs(DORMANT_TIMEOUT_HOURS * 3600),
            None => false,
        }
    }

    /// Check if currently dormant.
    pub fn is_dormant(&self) -> bool {
        self.dormant_since.is_some()
    }

    /// Wake up a dormant session (e.g., after reconnect).
    pub fn wake(&mut self) {
        self.dormant_since = None;
        self.jsonl_file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.jsonl_path)
            .expect("failed to reopen jsonl");
    }

    /// Flush and permanently close the JSONL file.
    pub fn close_jsonl(&mut self) {
        let _ = self.jsonl_file.sync_all();
        tracing::info!(
            session_id = ?self.session_id,
            path = ?self.jsonl_path,
            "JSONL file synced and closed"
        );
    }
}
```

> `write_telemetry` uses `sync_all()` to force disk writes immediately. For production throughput, consider buffering with periodic sync (e.g., every 5 seconds).
