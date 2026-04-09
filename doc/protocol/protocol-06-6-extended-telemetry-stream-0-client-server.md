## 6. Extended Telemetry (Stream 0, Client → Server)

Client periodically pushes environmental telemetry to the server, on the same bidirectional stream. The server distinguishes telemetry from other messages via the `"type"` JSON discriminator.

### 6.1 `ClientTelemetry` (Client → Server, periodic, interval: **1000 ms**)

```jsonc
{
  "type": "telemetry",
  "seq_num": 42,
  "timestamp_ms": 1712448000000,
  "lat": 41.9028,
  "lon": 12.4964,
  "speed": 60.0,
  "network_type": "lte",
  "cell_id": 12345,
  "pci": 150,
  "earfcn": 1850,
  "rsrp": -95.2,
  "rsrq": -10.5,
  "sinr": 8.3
}
```

| Field          | Type   | Unit / Constraints                    |
| -------------- | ------ | ------------------------------------- |
| `type`         | string | Must be `"telemetry"`                 |
| `seq_num`      | u64    | Monotonically increasing, starts at 0 |
| `timestamp_ms` | u64    | Epoch milliseconds                    |
| `lat`          | f64    | Decimal degrees, -90 to 90            |
| `lon`          | f64    | Decimal degrees, -180 to 180          |
| `speed`        | f64    | km/h                                  |
| `network_type` | string | `"5g"                                 | "lte" | "3g" | "2g" | "unknown"` |
| `cell_id`      | u32    | Physical Cell ID                      |
| `pci`          | u16    | Physical Cell ID                      |
| `earfcn`       | u32    | E-UTRA Absolute RF Channel Number     |
| `rsrp`         | f64    | dBm                                   |
| `rsrq`         | f64    | dB                                    |
| `sinr`         | f64    | dB                                    |

### 6.2 Server Behavior

- Telemetry records are appended to a file named `session_<session_id>.jsonl` in the server's output directory.
- Each line is the raw JSON of a single `ClientTelemetry` message.
- File is opened in append mode. No truncation on reconnection.

---

