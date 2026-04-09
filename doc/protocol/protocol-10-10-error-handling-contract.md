## 10. Error Handling Contract

| Condition                     | Action                                                         |
| ----------------------------- | -------------------------------------------------------------- |
| Malformed JSON on any stream  | Log `WARN`, skip message, continue stream                      |
| `Handshake` validation fail   | Send `{"type":"handshake_ack","status":"error"}`, close stream |
| Datagram payload ≠ 1200 bytes | Log `WARN`, skip counting as loss                              |
| `payload_type` ≠ `0x01`       | Log `WARN`, skip                                               |
| Telemetry file write fails    | Log `ERROR`, continue (don't crash test)                       |
| Connection drops mid-stream   | Break inner event loop, log `INFO`, reconnect                  |
| QUIC timeout (`idle_timeout`) | Connection closes, server cleans up resources                  |
