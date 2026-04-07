package dev.ranmt.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.ranmt.data.ConnectionState
import dev.ranmt.data.MeasurementConfig
import dev.ranmt.data.SessionDetail
import dev.ranmt.data.SessionRepository
import dev.ranmt.data.SessionSummary

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(application)

    val sessions = mutableStateListOf<SessionSummary>()
    var selectedDetail by mutableStateOf<SessionDetail?>(null)
        private set

    var config by mutableStateOf(
        MeasurementConfig(
            serverIp = "192.0.2.10",
            serverPort = 4433,
            direction = "Uplink",
            bitrateBps = 8000
        )
    )
        private set

    var connectionState by mutableStateOf(ConnectionState.Connected)
        private set

    var isRunning by mutableStateOf(false)
        private set

    init {
        refreshSessions()
    }

    fun refreshSessions() {
        sessions.clear()
        sessions.addAll(repository.listSessions())
    }

    fun loadDetail(id: String) {
        selectedDetail = repository.getSessionDetail(id)
    }

    fun clearDetail() {
        selectedDetail = null
    }

    fun deleteSession(id: String) {
        repository.deleteSession(id)
        refreshSessions()
    }

    fun updateConfig(newConfig: MeasurementConfig) {
        config = newConfig
    }

    fun startMeasurement() {
        isRunning = true
        connectionState = ConnectionState.Connected
    }

    fun stopMeasurement() {
        isRunning = false
    }

    fun updateConnectionState(state: ConnectionState) {
        connectionState = state
    }

    fun exportSession(id: String) = repository.exportSessionAsJsonl(id)
}
