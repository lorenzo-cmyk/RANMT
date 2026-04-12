package dev.ranmt.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ranmt.data.AppSettings
import dev.ranmt.data.AppSettingsStore
import dev.ranmt.data.ExportDestination
import dev.ranmt.data.ExportFormat
import dev.ranmt.data.MeasurementConfig
import dev.ranmt.data.SessionDetail
import dev.ranmt.data.SessionRepository
import dev.ranmt.data.SessionSummary
import dev.ranmt.service.MeasurementService
import dev.ranmt.service.RunningSessionState
import dev.ranmt.service.RunningUiState
import dev.ranmt.service.SessionPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(application)
    private val sessionPrefs = SessionPrefs(application)
    private val settingsStore = AppSettingsStore(application)

    val sessions = mutableStateListOf<SessionSummary>()
    var selectedDetail by mutableStateOf<SessionDetail?>(null)
        private set

    var config by mutableStateOf(
        MeasurementConfig(
            serverIp = "192.168.178.20",
            serverPort = 4433,
            direction = "Uplink",
            bitrateBps = 8000,
            insecure = false
        )
    )
        private set

    var isRunning by mutableStateOf(false)
        private set

    var hasActiveSession by mutableStateOf(false)
        private set

    var activeSessionId by mutableStateOf<String?>(null)
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
        val active = sessionPrefs.loadActive()
        activeSessionId = active?.sessionId
        hasActiveSession = active != null
        isRunning = hasActiveSession
        if (active != null) {
            selectedDetail = null
        }
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
        hasActiveSession = true
        activeSessionId = sessionPrefs.loadActive()?.sessionId
        val intent = MeasurementService.startIntent(getApplication(), config)
        getApplication<Application>().startForegroundService(intent)
        refreshActiveSession()
    }

    fun stopMeasurement() {
        isRunning = false
        hasActiveSession = false
        activeSessionId = null
        suppressAutoNavigate = true
        val intent = MeasurementService.stopIntent(getApplication())
        getApplication<Application>().startService(intent)
        viewModelScope.launch {
            delay(750)
            refreshSessions()
        }
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
