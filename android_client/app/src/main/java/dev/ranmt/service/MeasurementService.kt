package dev.ranmt.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.ranmt.R
import dev.ranmt.data.ConnectionState
import dev.ranmt.data.MeasurementConfig
import dev.ranmt.data.AppSettingsStore
import dev.ranmt.data.AccuracyMode
import dev.ranmt.data.SessionMetrics
import dev.ranmt.data.SessionRepository
import dev.ranmt.data.SessionSummary
import dev.ranmt.data.TelemetryPoint
import dev.ranmt.data.TransportStats
import dev.ranmt.rust.RustClient
import dev.ranmt.rust.RustClientHandle
import dev.ranmt.rust.RustConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class MeasurementService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: SessionRepository
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sessionPrefs: SessionPrefs
    private lateinit var settingsStore: AppSettingsStore
    private var locationCallback: LocationCallback? = null
    private var sessionId: String? = null
    private var startedAt: Long = 0L
    private var elapsedJob: Job? = null
    private var rustPollingJob: Job? = null
    private var rustStartJob: Job? = null
    private var metrics = MetricsAccumulator()
    private var rustHandle: RustClientHandle? = null
    private var lastTransportStats: TransportStats? = null
    private var wifiNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = SessionRepository(this)
        telephonyManager = getSystemService(TelephonyManager::class.java)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        sessionPrefs = SessionPrefs(this)
        settingsStore = AppSettingsStore(this)
        bindWifiNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMeasurement(intent)
            ACTION_RESUME -> resumeMeasurement()
            ACTION_STOP -> stopMeasurement()
            else -> resumeMeasurement()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopRustClient()
        unbindWifiNetwork()
        elapsedJob?.cancel()
        RunningSessionState.stop()
        super.onDestroy()
    }

    private fun startMeasurement(intent: Intent) {
        if (sessionId != null) return
        val config = MeasurementConfig(
            serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: "",
            serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 0),
            direction = intent.getStringExtra(EXTRA_DIRECTION) ?: "Uplink",
            bitrateBps = intent.getIntExtra(EXTRA_BITRATE, 0),
            insecure = intent.getBooleanExtra(EXTRA_INSECURE, false)
        )
        sessionId = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        metrics = MetricsAccumulator()

        val id = sessionId ?: return
        sessionPrefs.saveActive(id, startedAt, config)
        startForeground(NOTIFICATION_ID, buildNotification())
        repository.startSession(id, startedAt, config)
        RunningSessionState.start(id, resumed = false)
        updateConnectionState()
        startElapsedTimer()
        startRustClient(config)
        startLocationUpdates()
    }

    private fun resumeMeasurement() {
        if (sessionId != null) return
        val active = sessionPrefs.loadActive() ?: return
        sessionId = active.sessionId
        startedAt = active.startedAt
        metrics = MetricsAccumulator()
        repository.startSession(active.sessionId, active.startedAt, active.config)
        RunningSessionState.start(active.sessionId, resumed = true)
        startForeground(NOTIFICATION_ID, buildNotification())
        updateConnectionState()
        startElapsedTimer()
        startRustClient(active.config)
        startLocationUpdates()
    }

    private fun stopMeasurement() {
        val id = sessionId ?: return
        stopLocationUpdates()
        stopRustClient()
        elapsedJob?.cancel()

        val endTime = System.currentTimeMillis()
        val summary = SessionSummary(
            id = id,
            startedAt = startedAt,
            durationSec = ((endTime - startedAt) / 1000).toInt().coerceAtLeast(0),
            averageJitterMs = metrics.avgJitter(),
            lossPct = metrics.avgLoss(),
            primaryRat = metrics.primaryRat ?: "Unknown"
        )
        val metricsSnapshot = SessionMetrics(
            maxRsrp = metrics.maxRsrp,
            minRsrp = metrics.minRsrp,
            avgRsrp = metrics.avgRsrp(),
            connectionDrops = metrics.connectionDrops,
            bytesSent = metrics.bytesSent,
            bytesReceived = metrics.bytesReceived,
            peakJitterMs = metrics.peakJitter
        )
        repository.finalizeSession(summary, metricsSnapshot)
        RunningSessionState.stop()
        sessionPrefs.clearActive()
        stopForeground(STOP_FOREGROUND_REMOVE)
        sessionId = null
        stopSelf()
    }

    private fun startElapsedTimer() {
        elapsedJob?.cancel()
        elapsedJob = scope.launch {
            while (isActive) {
                val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                RunningSessionState.updateElapsed(elapsed)
                delay(1000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            RunningSessionState.updateConnection(ConnectionState.Reconnecting)
            RunningSessionState.setLocationPermissionMissing(true)
            return
        }
        RunningSessionState.setLocationPermissionMissing(false)
        val settings = settingsStore.load()
        val priority = when (settings.accuracyMode) {
            AccuracyMode.High -> Priority.PRIORITY_HIGH_ACCURACY
            AccuracyMode.Balanced -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, settings.samplingIntervalMs)
            .setMinUpdateIntervalMillis(settings.samplingIntervalMs)
            .setMinUpdateDistanceMeters(0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val id = sessionId ?: return
                result.locations.forEach { location ->
                    val snapshot = snapshotRadio()
                    val transport = lastTransportStats
                    val point = TelemetryPoint(
                        timestamp = location.time,
                        lat = location.latitude,
                        lon = location.longitude,
                        speedMps = location.speed.toDouble(),
                        rsrp = snapshot.rsrp,
                        rsrq = snapshot.rsrq,
                        sinr = snapshot.sinr,
                        cellId = snapshot.cellId,
                        pci = snapshot.pci,
                        earfcn = snapshot.earfcn,
                        networkType = snapshot.networkType,
                        jitterMs = transport?.jitterMs ?: 0.0,
                        lossPct = transport?.lossPct ?: 0.0
                    )
                    scope.launch(Dispatchers.IO) {
                        repository.appendTelemetry(id, point)
                    }
                    RunningSessionState.pushTelemetry(point)
                    metrics.update(point)
                    updateConnectionState()
                }
            }
        }

        LocationServices.getFusedLocationProviderClient(this)
            .requestLocationUpdates(request, locationCallback!!, mainLooper)
    }

    private fun stopLocationUpdates() {
        val callback = locationCallback ?: return
        LocationServices.getFusedLocationProviderClient(this)
            .removeLocationUpdates(callback)
        locationCallback = null
    }

    private fun updateConnectionState() {
        if (rustHandle != null) {
            return
        }
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val state = if (hasInternet) ConnectionState.Connected else ConnectionState.Reconnecting
        RunningSessionState.updateConnection(state)
    }

    private fun startRustClient(config: MeasurementConfig) {
        stopRustClient()
        rustStartJob?.cancel()
        rustStartJob = scope.launch(Dispatchers.IO) {
            val handle = RustClient.start(config) ?: return@launch
            rustHandle = handle
            startRustPolling(handle)
        }
    }

    private fun startRustPolling(handle: RustClientHandle) {
        rustPollingJob?.cancel()
        rustPollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val snapshot = RustClient.getStats(handle)
                if (snapshot != null) {
                    lastTransportStats = snapshot.transport
                    snapshot.transport?.let { metrics.updateTransport(it) }
                    RunningSessionState.updateTransport(snapshot.transport)
                    RunningSessionState.updateConnection(mapConnectionState(snapshot.state))
                }
                delay(1000)
            }
        }
    }

    private fun stopRustClient() {
        rustStartJob?.cancel()
        rustStartJob = null
        rustPollingJob?.cancel()
        rustPollingJob = null
        rustHandle?.let { handle ->
            scope.launch(Dispatchers.IO) {
                RustClient.stop(handle)
            }
        }
        rustHandle = null
        lastTransportStats = null
        RunningSessionState.updateTransport(null)
    }

    private fun mapConnectionState(state: RustConnectionState): ConnectionState {
        return when (state) {
            RustConnectionState.Connected -> ConnectionState.Connected
            RustConnectionState.Connecting -> ConnectionState.Reconnecting
            RustConnectionState.Reconnecting -> ConnectionState.Reconnecting
            RustConnectionState.Disconnected -> ConnectionState.Reconnecting
        }
    }

    private fun normalizeServer(config: MeasurementConfig): Pair<String, Int> {
        val raw = config.serverIp.trim()
        val idx = raw.lastIndexOf(':')
        if (idx > 0 && idx == raw.indexOf(':')) {
            val host = raw.substring(0, idx).trim()
            val portText = raw.substring(idx + 1).trim()
            val port = portText.toIntOrNull()
            if (host.isNotBlank() && port != null && port in 1..65535) {
                return host to port
            }
        }
        return raw to config.serverPort
    }

    private fun bindWifiNetwork() {
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                connectivityManager.bindProcessToNetwork(network)
                android.util.Log.i("MeasurementService", "Bound process to Wi-Fi network")
            }

            override fun onLost(network: Network) {
                if (wifiNetwork == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    wifiNetwork = null
                    android.util.Log.w("MeasurementService", "Wi-Fi network lost; unbound process")
                }
            }
        }
        try {
            connectivityManager.requestNetwork(request, callback)
            networkCallback = callback
        } catch (err: SecurityException) {
            android.util.Log.e("MeasurementService", "Wi-Fi bind requires CHANGE_NETWORK_STATE", err)
        }
    }

    private fun unbindWifiNetwork() {
        networkCallback?.let { callback ->
            connectivityManager.unregisterNetworkCallback(callback)
        }
        networkCallback = null
        connectivityManager.bindProcessToNetwork(null)
        wifiNetwork = null
    }

    @SuppressLint("MissingPermission")
    private fun snapshotRadio(): RadioSnapshot {
        val networkType = networkTypeName(telephonyManager.dataNetworkType)
        val cells = telephonyManager.allCellInfo
        val registered = cells.firstOrNull { it.isRegistered }

        return when (registered) {
            is CellInfoNr -> {
                val identity = registered.cellIdentity
                val signal = registered.cellSignalStrength
                val rsrp = reflectInt(signal, "getSsRsrp") ?: signal.dbm
                val rsrq = reflectInt(signal, "getSsRsrq") ?: 0
                val sinr = reflectInt(signal, "getSsSinr") ?: 0
                val nci = reflectLong(identity, "getNci")
                val pci = reflectInt(identity, "getPci") ?: 0
                val nrarfcn = reflectInt(identity, "getNrarfcn") ?: 0
                RadioSnapshot(
                    rsrp = safeSignal(rsrp),
                    rsrq = safeSignal(rsrq),
                    sinr = safeSignal(sinr),
                    cellId = nci?.toString() ?: "",
                    pci = pci,
                    earfcn = nrarfcn,
                    networkType = networkType
                )
            }
            is CellInfoLte -> {
                val identity = registered.cellIdentity
                val signal = registered.cellSignalStrength
                RadioSnapshot(
                    rsrp = safeSignal(signal.rsrp),
                    rsrq = safeSignal(signal.rsrq),
                    sinr = safeSignal(signal.rssnr),
                    cellId = identity.ci.toString(),
                    pci = identity.pci,
                    earfcn = identity.earfcn,
                    networkType = networkType
                )
            }
            else -> RadioSnapshot(networkType = networkType)
        }
    }

    private fun safeSignal(value: Int): Int {
        return if (value == Int.MAX_VALUE || value == Int.MIN_VALUE) 0 else value
    }

    private fun reflectInt(target: Any, method: String): Int? {
        return try {
            val m = target.javaClass.getMethod(method)
            (m.invoke(target) as? Int)
        } catch (_: Exception) {
            null
        }
    }

    private fun reflectLong(target: Any, method: String): Long? {
        return try {
            val m = target.javaClass.getMethod(method)
            (m.invoke(target) as? Long)
        } catch (_: Exception) {
            null
        }
    }

    private fun networkTypeName(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            else -> "Unknown"
        }
    }

    private fun buildNotification(): Notification {
        val channelId = ensureChannel()
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MeasurementService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("RANMT Measurement")
            .setContentText("Session running in the foreground")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RANMT Measurements",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    private class MetricsAccumulator {
        var maxRsrp: Int = Int.MIN_VALUE
        var minRsrp: Int = Int.MAX_VALUE
        var sumRsrp: Long = 0
        var count: Int = 0
        var connectionDrops: Int = 0
        var peakJitter: Double = 0.0
        var totalJitter: Double = 0.0
        var totalLoss: Double = 0.0
        var primaryRat: String? = null
        var bytesSent: Long = 0
        var bytesReceived: Long = 0

        fun update(point: TelemetryPoint) {
            count += 1
            sumRsrp += point.rsrp
            maxRsrp = maxRsrp.coerceAtLeast(point.rsrp)
            minRsrp = minRsrp.coerceAtMost(point.rsrp)
            totalJitter += point.jitterMs
            totalLoss += point.lossPct
            if (point.jitterMs > peakJitter) peakJitter = point.jitterMs
            if (primaryRat == null && point.networkType.isNotBlank()) primaryRat = point.networkType
        }

        fun updateTransport(stats: TransportStats) {
            stats.txBytes?.let { bytesSent = it }
            stats.rxBytes?.let { bytesReceived = it }
            stats.jitterMs?.let { jitter ->
                if (jitter > peakJitter) peakJitter = jitter
            }
        }

        fun avgRsrp(): Int = if (count == 0) 0 else (sumRsrp / count).toInt()
        fun avgJitter(): Double = if (count == 0) 0.0 else totalJitter / count
        fun avgLoss(): Double = if (count == 0) 0.0 else totalLoss / count
    }

    private data class RadioSnapshot(
        val rsrp: Int = 0,
        val rsrq: Int = 0,
        val sinr: Int = 0,
        val cellId: String = "",
        val pci: Int = 0,
        val earfcn: Int = 0,
        val networkType: String = "Unknown"
    )

    companion object {
        private const val CHANNEL_ID = "ranmt_measurement"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "dev.ranmt.action.START"
        const val ACTION_RESUME = "dev.ranmt.action.RESUME"
        const val ACTION_STOP = "dev.ranmt.action.STOP"
        const val EXTRA_SERVER_IP = "extra_server_ip"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_DIRECTION = "extra_direction"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_INSECURE = "extra_insecure"

        fun startIntent(context: Context, config: MeasurementConfig): Intent {
            return Intent(context, MeasurementService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_IP, config.serverIp)
                putExtra(EXTRA_SERVER_PORT, config.serverPort)
                putExtra(EXTRA_DIRECTION, config.direction)
                putExtra(EXTRA_BITRATE, config.bitrateBps)
                putExtra(EXTRA_INSECURE, config.insecure)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, MeasurementService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun resumeIntent(context: Context, active: SessionPrefs.ActiveSession): Intent {
            return Intent(context, MeasurementService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_SERVER_IP, active.config.serverIp)
                putExtra(EXTRA_SERVER_PORT, active.config.serverPort)
                putExtra(EXTRA_DIRECTION, active.config.direction)
                putExtra(EXTRA_BITRATE, active.config.bitrateBps)
                putExtra(EXTRA_INSECURE, active.config.insecure)
            }
        }
    }
}
