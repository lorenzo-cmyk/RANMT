package dev.ranmt.data

data class SessionSummary(
    val id: String,
    val startedAt: Long,
    val durationSec: Int,
    val averageRttvarMs: Double,
    val averageLossPct: Double,
    val primaryRat: String
)

data class SessionMetrics(
    val maxRsrp: Int,
    val minRsrp: Int,
    val avgRsrp: Int,
    val lossSpikes: Int,
    val peakRttvarMs: Double
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
    val rttvarMs: Double,
    val lossPct: Double
)

data class TelemetryAggregate(
    val count: Int,
    val maxRsrp: Int,
    val minRsrp: Int,
    val sumRsrp: Long,
    val totalRttvar: Double,
    val totalLossPct: Double,
    val peakRttvar: Double,
    val primaryRat: String?
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
    val rttvarMs: Double?,
    val txBytes: Long?,
    val rxBytes: Long?,
    val txPackets: Long?,
    val rxPackets: Long?,
    val cwnd: Long?,
    val totalLostPackets: Long?,
    val sendRateBps: Long?
)

enum class ConnectionState {
    Connected,
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

enum class VehicleProfile {
    Train, Car, Walking, Generic
}

data class AppSettings(
    val samplingIntervalMs: Long = 1000L,
    val accuracyMode: AccuracyMode = AccuracyMode.High,
    val defaultExportFormat: ExportFormat = ExportFormat.Csv,
    val defaultExportDestination: ExportDestination = ExportDestination.Share,
    val includeMetadataInCsv: Boolean = true,
    val vehicleProfile: VehicleProfile = VehicleProfile.Generic
)
