## 11. Mock Data Generation (Client CLI)

Until real modem integration, telemetry uses deterministic oscillating mock data:

- **Lat/Lon:** Small oscillation around `41.9028, 12.4964` (Roma Termini) with `sin(t)` wave, amplitude ~0.001°.
- **Speed:** Sinusoid between 0 and 120 km/h, period = 60 s.
- **RSRP:** Oscillates between -120 and -80 dBm, period = 45 s.
- **RSRQ:** Oscillates between -20 and -3 dB, period = 45 s.
- **SINR:** Oscillates between -5 and 25 dB, period = 50 s.
- **Network type:** Cycles lte → 5g → 3g → lte every 30 s.
- **Cell ID / PCI / EARFCN:** Increment by 1 every 30 s (simulating handover).

A `--seed <u64>` CLI flag ensures reproducibility.
