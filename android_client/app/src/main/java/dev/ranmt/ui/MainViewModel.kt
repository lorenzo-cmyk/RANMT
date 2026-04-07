package dev.ranmt.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.ranmt.data.ConnectionState
import dev.ranmt.data.MeasurementConfig
import dev.ranmt.data.ExportFormat
import dev.ranmt.data.AppSettings
import dev.ranmt.data.AppSettingsStore
import dev.ranmt.data.ExportDestination
import dev.ranmt.data.SessionDetail
import dev.ranmt.data.SessionRepository
import dev.ranmt.data.SessionSummary
import dev.ranmt.service.MeasurementService
import dev.ranmt.service.RunningSessionState
import dev.ranmt.service.RunningUiState
import dev.ranmt.service.SessionPrefs
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(application)
    private val sessionPrefs = SessionPrefs(application)
    private val settingsStore = AppSettingsStore(application)

    val sessions = mutableStateListOf<SessionSummary>()
    var selectedDetail by mutableStateOf<SessionDetail?>(null)
        private set

    var config by mutableStateOf(
        MeasurementConfig(
            serverIp = "192.0.2.10",
            serverPort = 4433,
            direction = "Uplink",
            bitrateBps = 8000,
            insecure = false
        )
    )
        private set

    var connectionState by mutableStateOf(ConnectionState.Connected)
        private set

    var isRunning by mutableStateOf(false)
        private set

    var hasActiveSession by mutableStateOf(false)
        private set

    var suppressAutoNavigate by mutableStateOf(false)
        private set

    val runningState: StateFlow<RunningUiState> = RunningSessionState.state

    var settings by mutableStateOf(settingsStore.load())
        private set

    init {
        refreshSessions()
        refreshActiveSession()
    }

    fun refreshActiveSession() {
        hasActiveSession = sessionPrefs.hasActive()
        isRunning = hasActiveSession
    }

    fun updateSettings(updated: AppSettings) {
        settings = updated
        settingsStore.save(updated)
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
        val intent = MeasurementService.startIntent(getApplication(), config)
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopMeasurement() {
        isRunning = false
        hasActiveSession = false
        suppressAutoNavigate = true
        val intent = MeasurementService.stopIntent(getApplication())
        getApplication<Application>().startService(intent)
        refreshSessions()
    }

    fun updateConnectionState(state: ConnectionState) {
        connectionState = state
    }

    fun exportSession(id: String, format: ExportFormat) = when (format) {
        ExportFormat.Jsonl -> repository.exportSessionAsJsonl(id)
        ExportFormat.Csv -> repository.exportSessionAsCsv(id, settings.includeMetadataInCsv)
    }

    fun exportWithDefaults(id: String): Pair<java.io.File?, ExportDestination> {
        val file = exportSession(id, settings.defaultExportFormat)
        return file to settings.defaultExportDestination
    }

    fun sessionFileSize(id: String) = repository.getSessionFileSize(id)

    fun clearSuppressAutoNavigate() {
        suppressAutoNavigate = false
    }
}
