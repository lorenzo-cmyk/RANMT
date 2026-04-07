package dev.ranmt.service

import dev.ranmt.data.ConnectionState
import dev.ranmt.data.TelemetryPoint
import dev.ranmt.data.TransportStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class RunningUiState(
    val sessionId: String? = null,
    val elapsedSec: Int = 0,
    val connectionState: ConnectionState = ConnectionState.Reconnecting,
    val lastPoint: TelemetryPoint? = null,
    val rsrpHistory: List<Int> = emptyList(),
    val sinrHistory: List<Int> = emptyList(),
    val transportStats: TransportStats? = null,
    val resumed: Boolean = false,
    val locationPermissionMissing: Boolean = false
)

object RunningSessionState {
    private val _state = MutableStateFlow(RunningUiState())
    val state: StateFlow<RunningUiState> = _state

    fun start(sessionId: String, resumed: Boolean) {
        _state.value = RunningUiState(
            sessionId = sessionId,
            connectionState = ConnectionState.Connected,
            resumed = resumed,
            locationPermissionMissing = false
        )
    }

    fun stop() {
        _state.value = RunningUiState()
    }

    fun updateElapsed(seconds: Int) {
        _state.update { it.copy(elapsedSec = seconds) }
    }

    fun updateConnection(state: ConnectionState) {
        _state.update { it.copy(connectionState = state) }
    }

    fun updateTransport(stats: TransportStats?) {
        _state.update { it.copy(transportStats = stats) }
    }

    fun setLocationPermissionMissing(missing: Boolean) {
        _state.update { it.copy(locationPermissionMissing = missing) }
    }

    fun clearResumed() {
        _state.update { it.copy(resumed = false) }
    }

    fun pushTelemetry(point: TelemetryPoint) {
        _state.update { current ->
            val rsrp = (current.rsrpHistory + point.rsrp).takeLast(30)
            val sinr = (current.sinrHistory + point.sinr).takeLast(30)
            current.copy(lastPoint = point, rsrpHistory = rsrp, sinrHistory = sinr)
        }
    }
}
