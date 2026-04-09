## 12. Server-Side Session Lifecycle Summary

SessionState management on the server involves two phases:

### 12.1 Connection Loss → Mark Dormant

When a QUIC connection is dropped (idle_timeout or error), the server calls `SessionState::mark_dormant()` on the matching session. This:
1. Records `dormant_since = Some(Instant::now())`
2. Syncs the JSONL file to disk
3. **Does not close or remove** the file — it stays on disk for 24h

### 12.2 Reconnect → Wake Up

When a new handshake arrives with an existing `session_id`, the server checks:
```rust
if let Some(sess) = sessions.get_mut(&h.session_id) {
    if sess.is_dormant() {
        sess.wake(); // Reopens file handle in append mode
    }
}
```

### 12.3 Expired Dormancy → Cleanup

At the end of each server loop iteration:
```rust
sessions.retain(|sid, sess| {
    if sess.is_dormant_expiring() {
        sess.close_jsonl();
        tracing::info!("Session {sid} expired, removing");
        false
    } else {
        true
    }
});
```

> **See** `datatypes.md §8` for the complete `SessionState` struct definition with all dormant lifecycle methods.
