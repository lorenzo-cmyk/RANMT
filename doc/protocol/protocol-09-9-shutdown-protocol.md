## 9. Shutdown Protocol

### 9.1 Graceful Shutdown (Initiated by Client)

Client sends a `{"type":"goodbye"}` message on Stream 0, then closes the QUIC connection gracefully:

```jsonc
{
  "type": "goodbye",
  "reason": "test_complete"   // "test_complete" | "user_abort" | "error"
}
```

Server behavior:
1. Receives `goodbye`, flushes any pending writes.
2. Closes the session JSONL file.
3. Closes the QUIC connection.

### 9.2 Abrupt Shutdown

If the client crashes or drops connection, the server relies on `idle_timeout` (30 s) to detect dead connections. The session JSONL file remains open with whatever data was received — this is the expected behavior.
