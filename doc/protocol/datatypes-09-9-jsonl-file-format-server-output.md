## 9. JSONL File Format (Server Output)

Each line in `session_<uuid>.jsonl` is one raw `ClientTelemetry` JSON message:

```
{"type":"telemetry","seq_num":0,"timestamp_ms":1712448000000,"lat":41.9028,"lon":12.4964,"speed":0.0,"network_type":"lte","cell_id":12345,"pci":150,"earfcn":1850,"rsrp":-95.2,"rsrq":-10.5,"sinr":8.3}
{"type":"telemetry","seq_num":1,"timestamp_ms":1712448001000,"lat":41.9029,"lon":12.4965,"speed":12.5,"network_type":"lte","cell_id":12345,"pci":150,"earfcn":1850,"rsrp":-94.8,"rsrq":-10.3,"sinr":8.7}
```

> No header line. No blank lines between records. Pure newline-delimited JSON.

---

