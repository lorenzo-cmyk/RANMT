package dev.ranmt.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionRepository(private val context: Context) {
    private val sessionsDir = File(context.filesDir, "sessions")
    private val indexFile = File(sessionsDir, "index.json")

    fun listSessions(): List<SessionSummary> = readIndex()

    fun getSessionDetail(id: String): SessionDetail? {
        val detailFile = sessionFile(id)
        if (!detailFile.exists()) return null
        val telemetry = mutableListOf<TelemetryPoint>()
        var summary: SessionSummary? = null
        var metrics: SessionMetrics? = null

        detailFile.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val json = JSONObject(line)
            when (json.optString("type")) {
                "summary" -> summary = summaryFromJson(json.getJSONObject("payload"))
                "metrics" -> metrics = metricsFromJson(json.getJSONObject("payload"))
                "telemetry" -> telemetry.add(telemetryFromJson(json.getJSONObject("payload")))
            }
        }

        val resolvedSummary = summary ?: buildSummary(id, telemetry)
        val resolvedMetrics = metrics ?: buildMetrics(telemetry)
        return SessionDetail(
            summary = resolvedSummary,
            metrics = resolvedMetrics,
            telemetry = telemetry
        )
    }

    fun deleteSession(id: String) {
        sessionFile(id).delete()
        writeIndex(readIndex().filterNot { it.id == id })
    }

    fun startSession(sessionId: String, startedAt: Long, config: MeasurementConfig) {
        ensureDir()
        val file = sessionFile(sessionId)
        if (!file.exists()) {
            val startEvent = JSONObject().apply {
                put("type", "start")
                put("payload", JSONObject().apply {
                    put("id", sessionId)
                    put("startedAt", startedAt)
                    put("serverIp", config.serverIp)
                    put("serverPort", config.serverPort)
                    put("direction", config.direction)
                    put("bitrateBps", config.bitrateBps)
                })
            }
            file.writeText(startEvent.toString() + "\n")
        }

        val summaries = readIndex()
        if (summaries.none { it.id == sessionId }) {
            val placeholder = SessionSummary(
                id = sessionId,
                startedAt = startedAt,
                durationSec = 0,
                averageRttvarMs = 0.0,
                lostPackets = 0L,
                primaryRat = "Unknown"
            )
            val updated = (summaries + placeholder)
                .sortedByDescending { it.startedAt }
            writeIndex(updated)
        }
    }

    fun appendTelemetry(sessionId: String, point: TelemetryPoint) {
        val line = JSONObject().apply {
            put("type", "telemetry")
            put("payload", telemetryToJson(point))
        }.toString()
        sessionFile(sessionId).appendText(line + "\n")
    }

    fun aggregateTelemetry(sessionId: String): TelemetryAggregate? {
        val detailFile = sessionFile(sessionId)
        if (!detailFile.exists()) return null
        var count = 0
        var maxRsrp = Int.MIN_VALUE
        var minRsrp = Int.MAX_VALUE
        var sumRsrp = 0L
        var totalRttvar = 0.0
        var totalLostPackets = 0L
        var peakRttvar = 0.0
        var primaryRat: String? = null

        detailFile.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val json = JSONObject(line)
            if (json.optString("type") != "telemetry") return@forEach
            val point = telemetryFromJson(json.getJSONObject("payload"))
            count += 1
            sumRsrp += point.rsrp
            maxRsrp = maxRsrp.coerceAtLeast(point.rsrp)
            minRsrp = minRsrp.coerceAtMost(point.rsrp)
            totalRttvar += point.rttvarMs
            totalLostPackets += point.lostPackets
            if (point.rttvarMs > peakRttvar) peakRttvar = point.rttvarMs
            if (primaryRat == null && point.networkType.isNotBlank()) {
                primaryRat = point.networkType
            }
        }

        if (count == 0) return null
        return TelemetryAggregate(
            count = count,
            maxRsrp = maxRsrp,
            minRsrp = minRsrp,
            sumRsrp = sumRsrp,
            totalRttvar = totalRttvar,
            totalLostPackets = totalLostPackets,
            peakRttvar = peakRttvar,
            primaryRat = primaryRat
        )
    }

    fun finalizeSession(summary: SessionSummary, metrics: SessionMetrics) {
        val file = sessionFile(summary.id)
        if (file.exists()) {
            file.appendText(JSONObject().apply {
                put("type", "summary")
                put("payload", summaryToJson(summary))
            }.toString() + "\n")
            file.appendText(JSONObject().apply {
                put("type", "metrics")
                put("payload", metricsToJson(metrics))
            }.toString() + "\n")
        }
        val summaries = (readIndex().filterNot { it.id == summary.id } + summary)
            .sortedByDescending { it.startedAt }
        val pruned = pruneSessions(summaries)
        writeIndex(pruned)
    }

    fun exportSessionAsJsonl(id: String): File? {
        val source = sessionFile(id)
        if (!source.exists()) return null
        val file = File(context.cacheDir, "ranmt_session_$id.jsonl")
        file.writeText(source.readText())
        return file
    }

    fun exportSessionAsCsv(id: String, includeMetadata: Boolean): File? {
        val detail = getSessionDetail(id) ?: return null
        val file = File(context.cacheDir, "ranmt_session_$id.csv")
        val meta = if (includeMetadata) {
            listOf(
                "# session_id=${detail.summary.id}",
                "# started_at=${detail.summary.startedAt}",
                "# duration_sec=${detail.summary.durationSec}",
                "# avg_rttvar_ms=${String.format("%.2f", detail.summary.averageRttvarMs)}",
                "# loss_pct=${String.format("%.2f", detail.summary.lostPackets)}",
                "# max_rsrp=${detail.metrics.maxRsrp}",
                "# min_rsrp=${detail.metrics.minRsrp}",
                "# avg_rsrp=${detail.metrics.avgRsrp}",
                "# peak_rttvar_ms=${String.format("%.2f", detail.metrics.peakRttvarMs)}"
            )
        } else {
            emptyList()
        }
        val header = listOf(
            "timestamp",
            "lat",
            "lon",
            "speed_mps",
            "rsrp",
            "rsrq",
            "sinr",
            "cell_id",
            "pci",
            "earfcn",
            "network_type",
            "rttvar_ms",
            "loss_pct"
        ).joinToString(",")
        val rows = detail.telemetry.map { point ->
            listOf(
                point.timestamp,
                point.lat,
                point.lon,
                String.format("%.2f", point.speedMps),
                point.rsrp,
                point.rsrq,
                point.sinr,
                point.cellId,
                point.pci,
                point.earfcn,
                point.networkType,
                String.format("%.2f", point.rttvarMs),
                String.format("%.2f", point.lostPackets)
            ).joinToString(",")
        }
        file.writeText((meta + header + rows).joinToString("\n"))
        return file
    }

    fun getSessionFileSize(id: String): Long? {
        val file = sessionFile(id)
        return if (file.exists()) file.length() else null
    }

    private fun pruneSessions(summaries: List<SessionSummary>): List<SessionSummary> {
        if (summaries.size <= MAX_SESSIONS) return summaries
        val keep = summaries.take(MAX_SESSIONS)
        val remove = summaries.drop(MAX_SESSIONS)
        remove.forEach { sessionFile(it.id).delete() }
        return keep
    }

    private fun ensureDir() {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
    }

    private fun sessionFile(id: String) = File(sessionsDir, "session_$id.jsonl")

    private fun readIndex(): List<SessionSummary> {
        if (!indexFile.exists()) return emptyList()
        val json = JSONArray(indexFile.readText())
        return (0 until json.length()).map { index ->
            summaryFromJson(json.getJSONObject(index))
        }
    }

    private fun writeIndex(summaries: List<SessionSummary>) {
        ensureDir()
        val json = JSONArray()
        summaries.forEach { summary ->
            json.put(summaryToJson(summary))
        }
        indexFile.writeText(json.toString())
    }

    private fun buildSummary(id: String, telemetry: List<TelemetryPoint>): SessionSummary {
        val start = telemetry.firstOrNull()?.timestamp ?: System.currentTimeMillis()
        val end = telemetry.lastOrNull()?.timestamp ?: start
        val duration = ((end - start) / 1000).toInt().coerceAtLeast(0)
        val avgRttvar = telemetry.map { it.rttvarMs }.average().takeIf { it.isFinite() } ?: 0.0
        val avgLoss =
            telemetry.map { it.lostPackets }.average().takeIf { it.isFinite() }?.toLong() ?: 0L
        val rat = telemetry.firstOrNull()?.networkType ?: "Unknown"
        return SessionSummary(
            id = id,
            startedAt = start,
            durationSec = duration,
            averageRttvarMs = avgRttvar,
            lostPackets = avgLoss,
            primaryRat = rat
        )
    }

    private fun buildMetrics(telemetry: List<TelemetryPoint>): SessionMetrics {
        val rsrpValues = telemetry.map { it.rsrp }
        val avgRsrp = rsrpValues.average().takeIf { it.isFinite() }?.toInt() ?: 0
        return SessionMetrics(
            maxRsrp = rsrpValues.maxOrNull() ?: 0,
            minRsrp = rsrpValues.minOrNull() ?: 0,
            avgRsrp = avgRsrp,
            connectionDrops = telemetry.count { it.lostPackets > 5.0 },
            bytesSent = 0,
            bytesReceived = 0,
            peakRttvarMs = telemetry.maxOfOrNull { it.rttvarMs } ?: 0.0
        )
    }

    private fun summaryToJson(summary: SessionSummary): JSONObject {
        return JSONObject().apply {
            put("id", summary.id)
            put("startedAt", summary.startedAt)
            put("durationSec", summary.durationSec)
            put("averageRttvarMs", summary.averageRttvarMs)
            put("lostPackets", summary.lostPackets)
            put("primaryRat", summary.primaryRat)
        }
    }

    private fun summaryFromJson(json: JSONObject): SessionSummary {
        return SessionSummary(
            id = json.getString("id"),
            startedAt = json.getLong("startedAt"),
            durationSec = json.getInt("durationSec"),
            averageRttvarMs = json.optDouble("averageRttvarMs", 0.0),
            lostPackets = json.optLong("lostPackets", 0L),
            primaryRat = json.getString("primaryRat")
        )
    }

    private fun metricsToJson(metrics: SessionMetrics): JSONObject {
        return JSONObject().apply {
            put("maxRsrp", metrics.maxRsrp)
            put("minRsrp", metrics.minRsrp)
            put("avgRsrp", metrics.avgRsrp)
            put("connectionDrops", metrics.connectionDrops)
            put("bytesSent", metrics.bytesSent)
            put("bytesReceived", metrics.bytesReceived)
            put("peakRttvarMs", metrics.peakRttvarMs)
        }
    }

    private fun metricsFromJson(json: JSONObject): SessionMetrics {
        return SessionMetrics(
            maxRsrp = json.getInt("maxRsrp"),
            minRsrp = json.getInt("minRsrp"),
            avgRsrp = json.getInt("avgRsrp"),
            connectionDrops = json.getInt("connectionDrops"),
            bytesSent = json.getLong("bytesSent"),
            bytesReceived = json.getLong("bytesReceived"),
            peakRttvarMs = json.getDouble("peakRttvarMs")
        )
    }

    private fun telemetryToJson(point: TelemetryPoint): JSONObject {
        return JSONObject().apply {
            put("timestamp", point.timestamp)
            put("lat", point.lat)
            put("lon", point.lon)
            put("speedMps", point.speedMps)
            put("rsrp", point.rsrp)
            put("rsrq", point.rsrq)
            put("sinr", point.sinr)
            put("cellId", point.cellId)
            put("pci", point.pci)
            put("earfcn", point.earfcn)
            put("networkType", point.networkType)
            put("rttvarMs", point.rttvarMs)
            put("lostPackets", point.lostPackets)
        }
    }

    private fun telemetryFromJson(json: JSONObject): TelemetryPoint {
        return TelemetryPoint(
            timestamp = json.getLong("timestamp"),
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            speedMps = json.optDouble("speedMps", 0.0),
            rsrp = json.optInt("rsrp", 0),
            rsrq = json.optInt("rsrq", 0),
            sinr = json.optInt("sinr", 0),
            cellId = json.optString("cellId", ""),
            pci = json.optInt("pci", 0),
            earfcn = json.optInt("earfcn", 0),
            networkType = json.optString("networkType", "Unknown"),
            rttvarMs = json.optDouble("rttvarMs", 0.0),
            lostPackets = json.optLong("lostPackets", 0L)
        )
    }

    companion object {
        private const val MAX_SESSIONS = 100
    }
}
