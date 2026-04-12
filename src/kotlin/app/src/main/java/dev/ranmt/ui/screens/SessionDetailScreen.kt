package dev.ranmt.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import dev.ranmt.data.ExportDestination
import dev.ranmt.data.ExportFormat
import dev.ranmt.data.SessionDetail
import dev.ranmt.data.TelemetryPoint
import dev.ranmt.ui.formatBytes
import dev.ranmt.ui.formatDateTime
import dev.ranmt.ui.formatDuration
import dev.ranmt.ui.formatRttvar

@Composable
fun SessionDetailScreen(
    detail: SessionDetail?,
    onBack: () -> Unit,
    onExport: (ExportFormat, ExportDestination) -> Unit,
    sessionFileSize: Long? = null
) {
    val (showExport, setShowExport) = remember { mutableStateOf(false) }
    val (exportFormat, setExportFormat) = remember { mutableStateOf(ExportFormat.Csv) }
    val (exportDestination, setExportDestination) = remember { mutableStateOf(ExportDestination.Share) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    text = "Session Details",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Radio and transport metrics for this drive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        }

        if (detail == null) {
            Text(
                text = "Loading session...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            return
        }

        Spacer(modifier = Modifier.height(16.dp))
        SessionHeader(detail)
        Spacer(modifier = Modifier.height(14.dp))
        OverviewPanel(detail)
        Spacer(modifier = Modifier.height(16.dp))
        RadioPanel(detail)
        Spacer(modifier = Modifier.height(16.dp))
        TransportPanel(detail)
        Spacer(modifier = Modifier.height(16.dp))
        MapPanel(detail.telemetry)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { setShowExport(true) }) {
                Text("Export")
            }
        }
    }

    if (showExport) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { setShowExport(false) },
            title = { Text("Export session") },
            text = {
                val rows = detail?.telemetry?.size ?: 0
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose a format and destination.")
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Rows: $rows", style = MaterialTheme.typography.bodyMedium)
                            sessionFileSize?.let { size ->
                                Text(
                                    "File size: ${formatBytes(size)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    Text("Format", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            ExportFormat.Csv,
                            ExportFormat.Jsonl
                        ).forEachIndexed { index, format ->
                            SegmentedButton(
                                selected = exportFormat == format,
                                onClick = { setExportFormat(format) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2)
                            ) {
                                Text(format.name.uppercase())
                            }
                        }
                    }
                    Text("Destination", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            ExportDestination.Share,
                            ExportDestination.Downloads
                        ).forEachIndexed { index, destination ->
                            SegmentedButton(
                                selected = exportDestination == destination,
                                onClick = { setExportDestination(destination) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2)
                            ) {
                                Text(destination.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    setShowExport(false)
                    onExport(exportFormat, exportDestination)
                }) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { setShowExport(false) }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SessionHeader(detail: SessionDetail) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = formatDateTime(detail.summary.startedAt),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Session: ...${detail.summary.id.takeLast(5)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun OverviewPanel(detail: SessionDetail) {
    val avgSpeed = remember(detail.telemetry) { averageSpeed(detail.telemetry) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Overview",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricTile("Duration", formatDuration(detail.summary.durationSec))
                MetricTile("Primary RAN", detail.summary.primaryRat)
                MetricTile("Avg Speed", formatSpeed(avgSpeed))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricTile("Bytes Tx", formatBytes(detail.metrics.bytesSent))
                MetricTile("Bytes Rx", formatBytes(detail.metrics.bytesReceived))
                MetricTile("Drops", detail.metrics.connectionDrops.toString())
            }
        }
    }
}

@Composable
private fun RadioPanel(detail: SessionDetail) {
    val rsrqStats =
        remember(detail.telemetry) { rangeStat(detail.telemetry) { it.rsrq.toDouble() } }
    val sinrStats =
        remember(detail.telemetry) { rangeStat(detail.telemetry) { it.sinr.toDouble() } }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Radio Quality",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricTile("RSRP Max", "${detail.metrics.maxRsrp} dBm")
                MetricTile("RSRP Avg", "${detail.metrics.avgRsrp} dBm")
                MetricTile("RSRP Min", "${detail.metrics.minRsrp} dBm")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricTile("RSRQ Max", formatDb(rsrqStats.max))
                MetricTile("RSRQ Min", formatDb(rsrqStats.min))
                MetricTile("SINR Max", formatDb(sinrStats.max))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricTile("SINR Min", formatDb(sinrStats.min))
            }
        }
    }
}

@Composable
private fun TransportPanel(detail: SessionDetail) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Transport Hotspots",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricTile("Peak Rttvar", formatRttvar(detail.metrics.peakRttvarMs))
                MetricTile("Lost Packets", detail.summary.lostPackets.toString())
                val dropTile = detail.metrics.connectionDrops.toString()
                MetricTile("Drops", dropTile)
            }
        }
    }
}

@Composable
private fun MapPanel(points: List<TelemetryPoint>) {
    val mapHeight = 260.dp
    val cameraPositionState = rememberCameraPositionState()
    val (mapLoaded, setMapLoaded) = remember { mutableStateOf(false) }
    val sampledPoints = remember(points) { sampleTelemetry(points, maxPoints = 200) }

    LaunchedEffect(points, mapLoaded) {
        if (mapLoaded && points.isNotEmpty()) {
            val bounds = LatLngBounds.builder().apply {
                points.forEach { include(LatLng(it.lat, it.lon)) }
            }.build()
            cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Data Points",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (points.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapHeight)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp)
                        ),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        text = "No location samples available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                GoogleMap(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapHeight)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp)
                        ),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = false),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        myLocationButtonEnabled = false
                    ),
                    onMapLoaded = { setMapLoaded(true) }
                ) {
                    sampledPoints.forEach { point ->
                        Marker(
                            state = MarkerState(position = LatLng(point.lat, point.lon)),
                            icon = BitmapDescriptorFactory.defaultMarker(lossHue(point.lostPackets)),
                            title = "Sample ${formatDateTime(point.timestamp)}",
                            snippet = formatMarkerSnippet(point)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendChip(color = Color(0xFF2FB7A3), label = "0-1% loss")
                    LegendChip(color = Color(0xFFF6B64B), label = "1-5% loss")
                    LegendChip(color = Color(0xFFE76D5A), label = ">5% loss")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Showing ${sampledPoints.size} of ${points.size} samples.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String) {
    Column(modifier = Modifier.width(100.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private data class RangeStat(val min: Double, val max: Double)

private fun rangeStat(
    points: List<TelemetryPoint>,
    selector: (TelemetryPoint) -> Double
): RangeStat {
    if (points.isEmpty()) return RangeStat(0.0, 0.0)
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    points.forEach { point ->
        val value = selector(point)
        if (value < min) min = value
        if (value > max) max = value
    }
    return RangeStat(min, max)
}


private fun averageSpeed(points: List<TelemetryPoint>): Double {
    if (points.isEmpty()) return 0.0
    return points.map { it.speedMps }.average().takeIf { it.isFinite() } ?: 0.0
}

private fun sampleTelemetry(points: List<TelemetryPoint>, maxPoints: Int): List<TelemetryPoint> {
    if (points.size <= maxPoints) return points
    val stride = (points.size + maxPoints - 1) / maxPoints
    return points.filterIndexed { index, _ -> (index % stride) == 0 }
}

private fun formatSpeed(speedMps: Double): String {
    val kmh = speedMps * 3.6
    return "${"%.1f".format(kmh)} km/h"
}

private fun formatDb(value: Double): String {
    return "${"%.0f".format(value)} dB"
}

private fun formatMarkerSnippet(point: TelemetryPoint): String {
    return listOf(
        "RSRP ${point.rsrp} dBm",
        "RSRQ ${point.rsrq} dB",
        "SINR ${point.sinr} dB",
        "Rttvar ${formatRttvar(point.rttvarMs)}",
        "Loss ${point.lostPackets.toString()}"
    ).joinToString(" | ")
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, shape = RoundedCornerShape(50))
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

private fun lossHue(lostPackets: Long): Float {
    return when {
        lostPackets <= 1L -> BitmapDescriptorFactory.HUE_GREEN
        lostPackets <= 5L -> BitmapDescriptorFactory.HUE_YELLOW
        else -> BitmapDescriptorFactory.HUE_RED
    }
}

fun shareFile(context: Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "dev.ranmt.fileprovider", file)
    val type = when (file.extension.lowercase()) {
        "csv" -> "text/csv"
        "jsonl" -> "application/x-ndjson"
        else -> "text/plain"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export Session"))
}

fun saveFileToDownloads(context: Context, file: java.io.File) {
    val resolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Files.getContentUri("external")
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(
            MediaStore.MediaColumns.MIME_TYPE,
            if (file.extension.lowercase() == "csv") "text/csv" else "application/x-ndjson"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(collection, values) ?: return
    resolver.openOutputStream(uri)?.use { output ->
        file.inputStream().use { input -> input.copyTo(output) }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
}
