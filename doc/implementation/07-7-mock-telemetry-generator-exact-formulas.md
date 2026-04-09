## 7. Mock Telemetry Generator (Exact Formulas)

All mock data is deterministic given a `seed` (from CLI `--seed`) and elapsed seconds `t` (monotonic clock since test start).

```rust
use std::f64::consts::TAU;
use std::time::Instant;

pub struct MockTelemetry {
    test_start: Instant,
    seq_num: u64,
}

impl MockTelemetry {
    pub fn new(test_start: Instant) -> Self {
        Self { test_start, seq_num: 0 }
    }

    pub fn generate(&mut self) -> ClientTelemetry {
        let phase = self.test_start.elapsed().as_secs_f64();
        let seq = self.seq_num;
        self.seq_num += 1;

        let lat = 41.9028 + 0.001 * (phase * 0.5).sin();
        let lon = 12.4964 + 0.001 * (phase * 0.3 + 1.0).sin();

        let speed = 60.0 + 60.0 * (phase * TAU / 60.0).sin();

        // Network type cycles every 30 s
        let cycle_30 = (phase / 30.0).floor() as i64;
        let network_type = match (cycle_30 % 4).abs() {
            0 => NetworkType::Lte,
            1 => NetworkType::FiveG,
            2 => NetworkType::ThreeG,
            3 => NetworkType::Lte,
            _ => NetworkType::Unknown,
        };

        let cell_id   = 12345 + (phase / 30.0) as u32;
        let pci       = (150 + (phase / 30.0) as u16) % 504;  // range 0-503
        let earfcn    = 1850 + (phase / 30.0) as u32;

        let rsrp = -100.0 + 20.0 * (phase * TAU / 45.0).sin();    // -120 to -80
        let rsrq = -11.5 + 8.5 * (phase * TAU / 45.0 + 0.5).sin(); // -20 to -3
        let sinr = 10.0 + 15.0 * (phase * TAU / 50.0 + 1.2).sin(); // -5 to 25

        ClientTelemetry {
            seq_num: seq,
            timestamp_ms: current_epoch_ms(),
            lat, lon, speed, network_type,
            cell_id, pci, earfcn, rsrp, rsrq, sinr,
        }
    }
}
```

### Oscillation Table

| Field    | Center   | Amplitude | Period | Phase offset |
|----------|----------|-----------|--------|-------------|
| lat      | 41.9028  | 0.001     | ~12.57s| 0.0         |
| lon      | 12.4964  | 0.001     | ~20.94s| 1.0         |
| speed    | 60.0     | 60.0      | 60 s   | 0.0         |
| rsrp     | -100.0   | 20.0      | 45 s   | 0.0         |
| rsrq     | -11.5    | 8.5       | 45 s   | 0.5*TAU     |
| sinr     | 10.0     | 15.0      | 50 s   | 1.2         |

### Reproducibility

The test uses `Instant::now()` for `t_secs` (monotonic clock), NOT `SystemTime`.
- Same seed → same telemetry values relative to test start.
- `SystemTime` wall clock is only used for `timestamp_ms` (which must be real).
- Note: `--seed` currently does not feed into a PRNG; all oscillations are deterministic `sin()` functions. The `--seed` parameter is reserved for future PRNG-based mock data.

---

