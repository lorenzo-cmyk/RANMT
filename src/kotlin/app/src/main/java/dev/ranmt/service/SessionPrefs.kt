package dev.ranmt.service

import android.content.Context
import dev.ranmt.data.MeasurementConfig

class SessionPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveActive(sessionId: String, startedAt: Long, config: MeasurementConfig) {
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_STARTED_AT, startedAt)
            .putString(KEY_SERVER_IP, config.serverIp)
            .putInt(KEY_SERVER_PORT, config.serverPort)
            .putString(KEY_DIRECTION, config.direction)
            .putInt(KEY_BITRATE, config.bitrateBps)
            .putBoolean(KEY_INSECURE, config.insecure)
            .apply()
    }

    fun saveLossSpikes(spikes: Int) {
        prefs.edit()
            .putInt(KEY_LOSS_SPIKES, spikes)
            .apply()
    }

    fun clearActive() {
        prefs.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_STARTED_AT)
            .remove(KEY_SERVER_IP)
            .remove(KEY_SERVER_PORT)
            .remove(KEY_DIRECTION)
            .remove(KEY_BITRATE)
            .remove(KEY_INSECURE)
            .remove(KEY_LOSS_SPIKES)
            .apply()
    }

    fun hasActive(): Boolean {
        return prefs.contains(KEY_SESSION_ID)
    }

    fun loadActive(): ActiveSession? {
        val id = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        val serverIp = prefs.getString(KEY_SERVER_IP, "") ?: ""
        val serverPort = prefs.getInt(KEY_SERVER_PORT, 0)
        val direction = prefs.getString(KEY_DIRECTION, "Uplink") ?: "Uplink"
        val bitrate = prefs.getInt(KEY_BITRATE, 0)
        val insecure = prefs.getBoolean(KEY_INSECURE, false)
        val lossSpikes = prefs.getInt(KEY_LOSS_SPIKES, 0)
        return ActiveSession(
            sessionId = id,
            startedAt = startedAt,
            config = MeasurementConfig(serverIp, serverPort, direction, bitrate, insecure),
            lossSpikes = lossSpikes
        )
    }

    data class ActiveSession(
        val sessionId: String,
        val startedAt: Long,
        val config: MeasurementConfig,
        val lossSpikes: Int
    )

    companion object {
        private const val PREFS = "ranmt_session"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_DIRECTION = "direction"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_INSECURE = "insecure"
        private const val KEY_LOSS_SPIKES = "connection_drops"
    }
}
