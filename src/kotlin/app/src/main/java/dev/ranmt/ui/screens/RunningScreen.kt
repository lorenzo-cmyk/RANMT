package dev.ranmt.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ranmt.data.ConnectionState
import dev.ranmt.data.MeasurementConfig
import dev.ranmt.service.RunningSessionState
import dev.ranmt.service.RunningUiState

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

@Composable
fun RunningScreen(
    config: MeasurementConfig,
    runningState: RunningUiState,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Column(
        modifier = Modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Live Dashboard",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        val point = runningState.lastPoint
        val networkType = point?.networkType ?: "Unknown"
        val cellId = point?.cellId?.ifBlank { "--" } ?: "--"
        val cellInfoMissing = cellId == "--" || networkType == "Unknown"
        Text(
            text = "${config.serverIp}:${config.serverPort} | ${config.direction} | ${config.bitrateBps} bps",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        val rsrp = point?.rsrp?.toString() ?: "--"
        val rsrq = point?.rsrq?.toString() ?: "--"
        val sinr = point?.sinr?.toString() ?: "--"
        val pci = point?.pci?.toString() ?: "--"
        val earfcn = point?.earfcn?.toString() ?: "--"
        val speed = point?.let { String.format("%.1f", it.speedMps) } ?: "--"
        val lat = point?.let { String.format("%.5f", it.lat) } ?: "--"
        val lon = point?.let { String.format("%.5f", it.lon) } ?: "--"
        val timestamp = point?.timestamp?.toString() ?: "--"
        val transport = runningState.transportStats
        val rtt = transport?.rttMs?.let { String.format("%.1f", it) } ?: "--"
        val loss = point?.lossPct?.let { String.format("%.2f %%", it) } ?: "--"
        val cwnd = transport?.cwnd?.let { dev.ranmt.ui.formatBytes(it) } ?: "--"
        val bytesTx = transport?.txBytes?.let { dev.ranmt.ui.formatBytes(it) } ?: "--"
        val bytesRx = transport?.rxBytes?.let { dev.ranmt.ui.formatBytes(it) } ?: "--"
        val rttvar = transport?.rttvarMs?.let { String.format("%.1f", it) } ?: "--"

        Spacer(modifier = Modifier.height(12.dp))
        TimerCard(seconds = runningState.elapsedSec, state = runningState.connectionState)
        if (runningState.resumed || runningState.locationPermissionMissing || cellInfoMissing) {
            Spacer(modifier = Modifier.height(8.dp))
            StatusTile(
                entries = listOfNotNull(
                    if (runningState.resumed) "Session" to "Resumed ⚠" else null,
                    if (runningState.locationPermissionMissing) "Location" to "Permission missing" else null,
                    if (cellInfoMissing) "Cell Info" to "Unavailable" else null
                ),
                showDismiss = runningState.resumed,
                showSettings = runningState.locationPermissionMissing,
                onDismiss = { RunningSessionState.clearResumed() },
                onOpenSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        InfoTile(
            title = "Radio",
            subtitle = "RAN and signal quality.",
            entries = listOf(
                "Network" to networkType,
                "RSRP" to "$rsrp dBm",
                "RSRQ" to "$rsrq dB",
                "SINR" to "$sinr dB",
                "Cell ID" to cellId,
                "PCI" to pci,
                "EARFCN" to earfcn
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoTile(
            title = "Transport",
            subtitle = "QUIC statistics.",
            entries = listOf(
                "RTT" to "$rtt ms",
                "Packet Loss" to loss,
                "CWND" to cwnd,
                "Rttvar" to "$rttvar ms",
                "Bytes TX" to bytesTx,
                "Bytes RX" to bytesRx
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoTile(
            title = "Location",
            subtitle = "GPS telemetry.",
            entries = listOf(
                "Speed" to "$speed m/s",
                "Latitude" to lat,
                "Longitude" to lon,
                "Timestamp" to timestamp
            )
        )

        Spacer(modifier = Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .combinedClickable(onClick = {}, onLongClick = onStop),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.Power,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.onTertiary
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Long press to stop",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerCard(seconds: Int, state: ConnectionState) {
    val minutes = seconds / 60
    val remaining = seconds % 60
    val display = String.format("%02d:%02d", minutes, remaining)
    val stateColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.Connected -> Color(0xFF2FB7A3)
            ConnectionState.Reconnecting -> Color(0xFFE76D5A)
        },
        label = "state-color"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = display,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(stateColor, shape = RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = when (state) {
                        ConnectionState.Connected -> "Connected"
                        ConnectionState.Reconnecting -> "Reconnecting"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun InfoTile(title: String, subtitle: String, entries: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            val chunked = entries.chunked(2)
            chunked.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { (label, value) ->
                        MetricCard(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StatusTile(
    entries: List<Pair<String, String>>,
    showDismiss: Boolean,
    showSettings: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Session health checks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            val chunked = entries.chunked(2)
            chunked.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { (label, value) ->
                        MetricCard(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (showDismiss || showSettings) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showDismiss) {
                        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("Dismiss")
                        }
                    }
                    if (showSettings) {
                        Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                            Text("App Settings")
                        }
                    }
                }
            }
        }
    }
}
