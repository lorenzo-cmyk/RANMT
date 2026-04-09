## 4. Client Telemetry

### 4.1 `ClientTelemetry` (Client → Server, Stream 0)

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientTelemetry {
    /// Monotonically increasing sequence number
    pub seq_num: u64,
    /// Epoch milliseconds
    pub timestamp_ms: u64,
    /// Latitude, decimal degrees
    pub lat: f64,
    /// Longitude, decimal degrees
    pub lon: f64,
    /// Speed in km/h
    pub speed: f64,
    /// Network type
    pub network_type: NetworkType,
    /// Cell identifier
    pub cell_id: u32,
    /// Physical Cell ID
    pub pci: u16,
    /// E-UTRA Absolute RF Channel Number
    pub earfcn: u32,
    /// Reference Signal Received Power, dBm
    pub rsrp: f64,
    /// Reference Signal Received Quality, dB
    pub rsrq: f64,
    /// Signal-to-Interference-plus-Noise Ratio, dB
    pub sinr: f64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum NetworkType {
    #[serde(rename = "5g")]
    FiveG,
    Lte,
    #[serde(rename = "3g")]
    ThreeG,
    #[serde(rename = "2g")]
    TwoG,
    Unknown,
}
```

---

