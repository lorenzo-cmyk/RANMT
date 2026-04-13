package dev.ranmt.rust

import android.util.Log
import dev.ranmt.data.MeasurementConfig
import dev.ranmt.data.TransportStats
import uniffi.ranmt_client.ClientHandle
import uniffi.ranmt_client.FfiClientConfig
import uniffi.ranmt_client.FfiClientTelemetry
import uniffi.ranmt_client.FfiConnectionState
import uniffi.ranmt_client.FfiDirection
import uniffi.ranmt_client.FfiStatsSnapshot
import uniffi.ranmt_client.getStats as ffiGetStats
import uniffi.ranmt_client.sendTelemetry as ffiSendTelemetry
import uniffi.ranmt_client.startClient as ffiStartClient
import uniffi.ranmt_client.stopClient as ffiStopClient

private const val TAG = "RustClient"

enum class RustConnectionState {
    Connecting,
    Connected,
    Reconnecting,
    Disconnected
}

typealias RustClientHandle = ClientHandle

data class RustSnapshot(
    val state: RustConnectionState,
    val transport: TransportStats?
)

object RustClient {
    private var loaded = false

    fun isAvailable(): Boolean {
        return ensureLoaded()
    }

    suspend fun start(config: MeasurementConfig, sessionId: String?): RustClientHandle? {
        if (!ensureLoaded()) {
            Log.e(TAG, "Rust library not loaded")
            return null
        }
        val (host, port) = normalizeServer(config)
        if (host.isBlank()) {
            Log.e(TAG, "Server address is empty")
            return null
        }
        Log.i(TAG, "Starting Rust client host=$host port=$port insecure=${config.insecure}")
        val ffiConfig = FfiClientConfig(
            serverAddr = host,
            serverFqdn = null,
            sessionId = sessionId,
            port = port,
            direction = if (config.direction == "Downlink") FfiDirection.DL else FfiDirection.UL,
            bitrateBps = config.bitrateBps.toUInt(),
            duration = 0uL,
            insecure = config.insecure,
            seed = 42uL
        )
        return try {
            ffiStartClient(ffiConfig)
        } catch (err: Exception) {
            Log.e(TAG, "Failed to start Rust client", err)
            null
        }
    }

    suspend fun stop(handle: RustClientHandle) {
        if (!loaded) return
        try {
            ffiStopClient(handle)
        } catch (err: Exception) {
            Log.w(TAG, "Failed to stop Rust client", err)
        }
    }

    suspend fun getStats(handle: RustClientHandle): RustSnapshot? {
        if (!loaded) return null
        return try {
            val stats = ffiGetStats(handle)
            mapSnapshot(stats)
        } catch (err: Exception) {
            Log.w(TAG, "Failed to fetch Rust stats", err)
            null
        }
    }

    suspend fun pushTelemetry(handle: RustClientHandle, point: dev.ranmt.data.TelemetryPoint) {
        if (!loaded) return
        try {
            val ffiPoint = FfiClientTelemetry(
                lat = point.lat,
                lon = point.lon,
                speedMps = point.speedMps,
                rsrp = point.rsrp,
                rsrq = point.rsrq,
                sinr = point.sinr,
                networkType = point.networkType,
                cellId = point.cellId,
                pci = point.pci,
                earfcn = point.earfcn
            )
            ffiSendTelemetry(handle, ffiPoint)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push telemetry", e)
        }
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("ranmt_client")
            loaded = true
            Log.i(TAG, "Loaded native library ranmt_client")
            true
        } catch (err: UnsatisfiedLinkError) {
            Log.e(TAG, "Rust native library not found", err)
            false
        }
    }

    private fun mapSnapshot(snapshot: FfiStatsSnapshot): RustSnapshot {
        val state = mapState(snapshot.connectionState)
        val server = snapshot.serverStats
        val transport =
            if (server != null) {
                TransportStats(
                    rttMs = server.quicStats.rttMs,
                    rttvarMs = server.quicStats.rttvarMs,
                    // App TX matches Server RX
                    txBytes = server.quicStats.rxBytes.toLong(),
                    // App RX matches Server TX
                    rxBytes = server.quicStats.txBytes.toLong(),
                    txPackets = server.quicStats.rxPackets.toLong(),
                    rxPackets = server.quicStats.txPackets.toLong(),
                    cwnd = server.quicStats.cwnd.toLong(),
                    totalLostPackets = server.quicStats.lostPackets.toLong(),
                    sendRateBps = server.quicStats.sendRateBps.toLong()
                )
            } else {
                null
            }
        return RustSnapshot(state = state, transport = transport)
    }

    private fun mapState(state: FfiConnectionState): RustConnectionState {
        return when (state) {
            FfiConnectionState.CONNECTING -> RustConnectionState.Connecting
            FfiConnectionState.CONNECTED -> RustConnectionState.Connected
            FfiConnectionState.RECONNECTING -> RustConnectionState.Reconnecting
            FfiConnectionState.DISCONNECTED -> RustConnectionState.Disconnected
        }
    }

    private fun normalizeServer(config: MeasurementConfig): Pair<String, UShort> {
        val raw = config.serverIp.trim()
        val idx = raw.lastIndexOf(':')
        if (idx > 0 && idx == raw.indexOf(':')) {
            val host = raw.substring(0, idx).trim()
            val portText = raw.substring(idx + 1).trim()
            val port = portText.toIntOrNull()
            if (host.isNotBlank() && port != null && port in 1..65535) {
                return host to port.toUShort()
            }
            Log.w(TAG, "Invalid port in server address: $raw")
        }
        return raw to config.serverPort.toUShort()
    }
}
