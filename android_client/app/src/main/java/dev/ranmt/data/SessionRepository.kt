package dev.ranmt.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionRepository(private val context: Context) {
    private val sessionsDir = File(context.filesDir, "sessions")
    private val indexFile = File(sessionsDir, "index.json")

    fun listSessions(): List<SessionSummary> {
        ensureSeeded()
        return readIndex()
    }

    fun getSessionDetail(id: String): SessionDetail? {
        ensureSeeded()
        val detailFile = File(sessionsDir, "session_$id.json")
        if (!detailFile.exists()) return null
        return detailFromJson(JSONObject(detailFile.readText()))
    }

    fun deleteSession(id: String) {
        ensureSeeded()
        File(sessionsDir, "session_$id.json").delete()
        val summaries = readIndex().filterNot { it.id == id }
        writeIndex(summaries)
    }

    fun exportSessionAsJsonl(id: String): File? {
        val detail = getSessionDetail(id) ?: return null
        val file = File(context.cacheDir, "ranmt_session_${detail.summary.id}.jsonl")
        val lines = mutableListOf<String>()
        lines.add(JSONObject().apply {
            put("type", "summary")
            put("payload", summaryToJson(detail.summary))
        }.toString())
        lines.add(JSONObject().apply {
            put("type", "metrics")
            put("payload", metricsToJson(detail.metrics))
        }.toString())
        detail.telemetry.forEach { point ->
            lines.add(JSONObject().apply {
                put("type", "telemetry")
                put("payload", telemetryToJson(point))
            }.toString())
        }
        file.writeText(lines.joinToString("\n"))
        return file
    }

    private fun ensureSeeded() {
        if (indexFile.exists()) return
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        val sessions = SampleData.sessions()
        val summaries = sessions.map { it.summary }
        writeIndex(summaries)
        sessions.forEach { detail ->
            val detailFile = File(sessionsDir, "session_${detail.summary.id}.json")
            detailFile.writeText(detailToJson(detail).toString())
        }
    }

    private fun readIndex(): List<SessionSummary> {
        if (!indexFile.exists()) return emptyList()
        val json = JSONArray(indexFile.readText())
        return (0 until json.length()).map { index ->
            summaryFromJson(json.getJSONObject(index))
        }
    }

    private fun writeIndex(summaries: List<SessionSummary>) {
        val json = JSONArray()
        summaries.forEach { summary ->
            json.put(summaryToJson(summary))
        }
        indexFile.writeText(json.toString())
    }

    private fun detailToJson(detail: SessionDetail): JSONObject {
        return JSONObject().apply {
            put("summary", summaryToJson(detail.summary))
            put("metrics", metricsToJson(detail.metrics))
            put("telemetry", JSONArray().apply {
                detail.telemetry.forEach { put(telemetryToJson(it)) }
            })
        }
    }

    private fun detailFromJson(json: JSONObject): SessionDetail {
        val summary = summaryFromJson(json.getJSONObject("summary"))
        val metrics = metricsFromJson(json.getJSONObject("metrics"))
        val telemetryJson = json.getJSONArray("telemetry")
        val telemetry = (0 until telemetryJson.length()).map { index ->
            telemetryFromJson(telemetryJson.getJSONObject(index))
        }
        return SessionDetail(summary = summary, metrics = metrics, telemetry = telemetry)
    }

    private fun summaryToJson(summary: SessionSummary): JSONObject {
        return JSONObject().apply {
            put("id", summary.id)
            put("startedAt", summary.startedAt)
            put("durationSec", summary.durationSec)
            put("averageJitterMs", summary.averageJitterMs)
            put("lossPct", summary.lossPct)
            put("primaryRat", summary.primaryRat)
        }
    }

    private fun summaryFromJson(json: JSONObject): SessionSummary {
        return SessionSummary(
            id = json.getString("id"),
            startedAt = json.getLong("startedAt"),
            durationSec = json.getInt("durationSec"),
            averageJitterMs = json.getDouble("averageJitterMs"),
            lossPct = json.getDouble("lossPct"),
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
            put("peakJitterMs", metrics.peakJitterMs)
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
            peakJitterMs = json.getDouble("peakJitterMs")
        )
    }

    private fun telemetryToJson(point: TelemetryPoint): JSONObject {
        return JSONObject().apply {
            put("timestamp", point.timestamp)
            put("lat", point.lat)
            put("lon", point.lon)
            put("rsrp", point.rsrp)
            put("rsrq", point.rsrq)
            put("sinr", point.sinr)
            put("cellId", point.cellId)
            put("pci", point.pci)
            put("earfcn", point.earfcn)
            put("jitterMs", point.jitterMs)
            put("lossPct", point.lossPct)
        }
    }

    private fun telemetryFromJson(json: JSONObject): TelemetryPoint {
        return TelemetryPoint(
            timestamp = json.getLong("timestamp"),
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            rsrp = json.getInt("rsrp"),
            rsrq = json.getInt("rsrq"),
            sinr = json.getInt("sinr"),
            cellId = json.getString("cellId"),
            pci = json.getInt("pci"),
            earfcn = json.getInt("earfcn"),
            jitterMs = json.getDouble("jitterMs"),
            lossPct = json.getDouble("lossPct")
        )
    }
}
