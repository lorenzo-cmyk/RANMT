## 11. Utility Functions

### 11.1 `current_epoch_ms()`

```rust
pub fn current_epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
```

Returns Unix epoch in milliseconds. Used for `timestamp_ms` and `send_ts`.
