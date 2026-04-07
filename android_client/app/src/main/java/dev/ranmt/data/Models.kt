package dev.ranmt.data

data class SessionSummary(
    val id: String,
    val startedAt: Long,
    val durationSec: Int,
    val averageJitterMs: Double,
    val lossPct: Double,
    val primaryRat: String
)

data class SessionMetrics(
    val maxRsrp: Int,
    val minRsrp: Int,
    val avgRsrp: Int,
    val connectionDrops: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val peakJitterMs: Double
)

data class TelemetryPoint(
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double = 0.0,
    val rsrp: Int,
    val rsrq: Int,
    val sinr: Int,
    val cellId: String,
    val pci: Int,
    val earfcn: Int,
    val networkType: String = "Unknown",
    val jitterMs: Double,
    val lossPct: Double
)

data class SessionDetail(
    val summary: SessionSummary,
    val metrics: SessionMetrics,
    val telemetry: List<TelemetryPoint>
)

data class MeasurementConfig(
    val serverIp: String,
    val serverPort: Int,
    val direction: String,
    val bitrateBps: Int,
    val insecure: Boolean = false
)

data class TransportStats(
    val rttMs: Double?,
    val txBytes: Long?,
    val rxBytes: Long?,
    val cwnd: Long?,
    val lostPackets: Long?,
    val sendRateBps: Long?,
    val lossPct: Double?,
    val jitterMs: Double?,
    val jitterEwmaMs: Double?,
    val lossJitterSource: LossJitterSource?
)

enum class LossJitterSource {
    ReceivePath,
    SendPacing
}

enum class ConnectionState {
    Connected,
    Buffering,
    Reconnecting
}

enum class ExportFormat {
    Jsonl,
    Csv
}

enum class ExportDestination {
    Share,
    Downloads
}

enum class AccuracyMode {
    Balanced,
    High
}

data class AppSettings(
    val samplingIntervalMs: Long = 1000L,
    val accuracyMode: AccuracyMode = AccuracyMode.High,
    val defaultExportFormat: ExportFormat = ExportFormat.Csv,
    val defaultExportDestination: ExportDestination = ExportDestination.Share,
    val includeMetadataInCsv: Boolean = true
)
