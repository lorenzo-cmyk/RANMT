package dev.ranmt.data

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object SampleData {
    fun sessions(): List<SessionDetail> {
        val base = System.currentTimeMillis() - 1000L * 60 * 60 * 26
        return listOf(
            buildSession(
                id = "2cbeed7a-dfea-4a5f-b142-bcddadb40400",
                start = base,
                durationSec = 765,
                rat = "5G SA",
                rttvar = 9.8,
                loss = 0.8,
                seed = 7
            ),
            buildSession(
                id = "30760e2a-920c-4e3a-b7ad-c58f3f26350b",
                start = base - 1000L * 60 * 60 * 3,
                durationSec = 1289,
                rat = "LTE",
                rttvar = 14.5,
                loss = 2.6,
                seed = 11
            )
        )
    }

    private fun buildSession(
        id: String,
        start: Long,
        durationSec: Int,
        rat: String,
        rttvar: Double,
        loss: Double,
        seed: Int
    ): SessionDetail {
        val telemetry = generateTelemetry(start, durationSec, seed)
        val avgRsrp = telemetry.map { it.rsrp }.average().toInt()
        val metrics = SessionMetrics(
            maxRsrp = telemetry.maxOf { it.rsrp },
            minRsrp = telemetry.minOf { it.rsrp },
            avgRsrp = avgRsrp,
            connectionDrops = telemetry.count { it.lostPackets > 5.0 },
            bytesSent = 12_800_000,
            bytesReceived = 12_300_000,
            peakRttvarMs = telemetry.maxOf { it.rttvarMs }
        )
        val summary = SessionSummary(
            id = id,
            startedAt = start,
            durationSec = durationSec,
            averageRttvarMs = rttvar,
            lostPackets = loss.toLong(),
            primaryRat = rat
        )
        return SessionDetail(summary = summary, metrics = metrics, telemetry = telemetry)
    }

    private fun generateTelemetry(start: Long, durationSec: Int, seed: Int): List<TelemetryPoint> {
        val rand = Random(seed)
        val points = 120
        val baseLat = 37.775
        val baseLon = -122.418
        val step = durationSec / points
        return (0 until points).map { index ->
            val t = start + (index * step * 1000L)
            val angle = index / 12.0
            val radius = 0.002 + 0.0006 * sin(index / 9.0)
            val rttvar = 4.0 + 8.0 * kotlin.math.abs(sin(index / 7.0))
            val loss = if (index % 22 == 0) 8.0 else rand.nextDouble(0.0, 1.6)
            TelemetryPoint(
                timestamp = t,
                lat = baseLat + radius * cos(angle),
                lon = baseLon + radius * sin(angle),
                speedMps = 9.0 + (2 * sin(index / 5.0)),
                rsrp = -90 + (8 * sin(index / 8.0)).toInt(),
                rsrq = -10 + (2 * cos(index / 5.0)).toInt(),
                sinr = 12 + (4 * sin(index / 6.0)).toInt(),
                cellId = "CELL-${100 + index % 8}",
                pci = 200 + index % 10,
                earfcn = 6300 + index % 30,
                networkType = "LTE",
                rttvarMs = rttvar,
                lostPackets = loss.toLong()
            )
        }
    }
}
