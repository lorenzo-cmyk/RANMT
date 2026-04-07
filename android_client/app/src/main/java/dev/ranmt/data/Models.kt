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
    val rsrp: Int,
    val rsrq: Int,
    val sinr: Int,
    val cellId: String,
    val pci: Int,
    val earfcn: Int,
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
    val bitrateBps: Int
)

enum class ConnectionState {
    Connected,
    Buffering,
    Reconnecting
}
