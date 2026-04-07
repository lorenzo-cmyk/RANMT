package dev.ranmt.ui.screens

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.core.content.FileProvider
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import dev.ranmt.data.ExportDestination
import dev.ranmt.data.ExportFormat
import dev.ranmt.data.SessionDetail
import dev.ranmt.data.TelemetryPoint
import dev.ranmt.ui.formatBytes
import dev.ranmt.ui.formatDateTime
import dev.ranmt.ui.formatJitter
import dev.ranmt.ui.formatPct

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    detail: SessionDetail?,
    onBack: () -> Unit,
    onExport: (ExportFormat, ExportDestination) -> Unit,
    sessionFileSize: Long? = null
) {
    val (showExport, setShowExport) = remember { mutableStateOf(false) }
    val (isExporting, setIsExporting) = remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp)) {
        CenterAlignedTopAppBar(
            title = { Text("Session Details") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { setShowExport(true) }) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = "Export")
                }
            }
        )

        if (detail == null) {
            Text(
                text = "Loading session...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            return
        }

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

        Spacer(modifier = Modifier.height(14.dp))
        MetricsPanel(detail)
        Spacer(modifier = Modifier.height(16.dp))
        RouteMap(detail.telemetry)
    }

    if (showExport) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { setShowExport(false) },
            title = { Text("Export session") },
            text = {
                val rows = detail?.telemetry?.size ?: 0
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Choose a format and destination.")
                    Text("Rows: $rows", style = MaterialTheme.typography.bodyMedium)
                    sessionFileSize?.let { size ->
                        Text("File size: ${formatBytes(size)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (isExporting) {
                        Text("Exporting...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextButton(onClick = {
                        setIsExporting(true)
                        setShowExport(false)
                        onExport(ExportFormat.Csv, ExportDestination.Share)
                        setIsExporting(false)
                    }) { Text("Quick Export (Defaults)") }
                    TextButton(onClick = {
                        setIsExporting(true)
                        setShowExport(false)
                        onExport(ExportFormat.Jsonl, ExportDestination.Share)
                        setIsExporting(false)
                    }) { Text("Share JSONL") }
                    TextButton(onClick = {
                        setIsExporting(true)
                        setShowExport(false)
                        onExport(ExportFormat.Csv, ExportDestination.Share)
                        setIsExporting(false)
                    }) { Text("Share CSV") }
                    TextButton(onClick = {
                        setIsExporting(true)
                        setShowExport(false)
                        onExport(ExportFormat.Csv, ExportDestination.Downloads)
                        setIsExporting(false)
                    }) { Text("Save CSV to Downloads") }
                }
            },
            dismissButton = {
                TextButton(onClick = { setShowExport(false) }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun MetricsPanel(detail: SessionDetail) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Overall Metrics",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricTile("Max RSRP", "${detail.metrics.maxRsrp} dBm")
                MetricTile("Min RSRP", "${detail.metrics.minRsrp} dBm")
                MetricTile("Avg RSRP", "${detail.metrics.avgRsrp} dBm")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricTile("Drops", detail.metrics.connectionDrops.toString())
                MetricTile("Bytes Tx", formatBytes(detail.metrics.bytesSent))
                MetricTile("Bytes Rx", formatBytes(detail.metrics.bytesReceived))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricTile("Peak Jitter", formatJitter(detail.metrics.peakJitterMs))
                MetricTile("Avg Jitter", formatJitter(detail.summary.averageJitterMs))
                MetricTile("Loss", formatPct(detail.summary.lossPct))
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

@Composable
private fun RouteMap(points: List<TelemetryPoint>) {
    var selectedIndex by remember { mutableIntStateOf(points.lastIndex.coerceAtLeast(0)) }
    val mapHeight = 240.dp
    val cameraPositionState = rememberCameraPositionState()
    val (mapLoaded, setMapLoaded) = remember { mutableStateOf(false) }
    val segments = remember(points) { buildSegments(points) }

    androidx.compose.runtime.LaunchedEffect(points, mapLoaded) {
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
                text = "Route",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (points.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapHeight)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No route data",
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
                    uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
                    onMapLoaded = { setMapLoaded(true) },
                    onMapClick = { latLng ->
                        selectedIndex = nearestPointIndex(points, latLng)
                    }
                ) {
                    segments.forEach { segment ->
                        Polyline(points = segment.points, color = segment.color, width = 10f)
                    }
                    points.getOrNull(selectedIndex)?.let { point ->
                        Marker(
                            state = MarkerState(position = LatLng(point.lat, point.lon)),
                            title = "Sample"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendChip(color = Color(0xFF2FB7A3), label = "0-1% loss")
                    LegendChip(color = Color(0xFFF6B64B), label = "1-5% loss")
                    LegendChip(color = Color(0xFFE76D5A), label = ">5% loss")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column {
                    Text(
                        text = "Timeline",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = selectedIndex.toFloat(),
                        onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, points.lastIndex) },
                        valueRange = 0f..points.lastIndex.toFloat(),
                        steps = (points.lastIndex - 1).coerceAtLeast(0)
                    )
                    points.getOrNull(selectedIndex)?.let { point ->
                        Text(
                            text = formatDateTime(point.timestamp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = points.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                points.getOrNull(selectedIndex)?.let { point ->
                    TelemetrySheet(point)
                }
            }
        }
    }
}

private data class RouteSegment(val points: List<LatLng>, val color: Color)

private fun buildSegments(points: List<TelemetryPoint>): List<RouteSegment> {
    if (points.size < 2) return emptyList()
    val smoothed = smoothPoints(points, window = 3)
    val segments = mutableListOf<RouteSegment>()
    for (i in 0 until smoothed.lastIndex) {
        val start = smoothed[i]
        val end = smoothed[i + 1]
        val color = when {
            start.lossPct <= 1.0 -> Color(0xFF2FB7A3)
            start.lossPct <= 5.0 -> Color(0xFFF6B64B)
            else -> Color(0xFFE76D5A)
        }
        segments.add(
            RouteSegment(
                points = listOf(LatLng(start.lat, start.lon), LatLng(end.lat, end.lon)),
                color = color
            )
        )
    }
    return segments
}

private fun smoothPoints(points: List<TelemetryPoint>, window: Int): List<TelemetryPoint> {
    if (points.size <= window) return points
    val half = window / 2
    return points.mapIndexed { index, point ->
        val start = (index - half).coerceAtLeast(0)
        val end = (index + half).coerceAtMost(points.lastIndex)
        val slice = points.subList(start, end + 1)
        val avgLat = slice.map { it.lat }.average()
        val avgLon = slice.map { it.lon }.average()
        point.copy(lat = avgLat, lon = avgLon)
    }
}

private fun nearestPointIndex(points: List<TelemetryPoint>, target: LatLng): Int {
    if (points.isEmpty()) return 0
    var bestIndex = 0
    var bestDistance = Double.MAX_VALUE
    points.forEachIndexed { index, point ->
        val dist = haversineMeters(point.lat, point.lon, target.latitude, target.longitude)
        if (dist < bestDistance) {
            bestDistance = dist
            bestIndex = index
        }
    }
    return bestIndex
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

@Composable
private fun TelemetrySheet(point: TelemetryPoint) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color = Color(0xFF2FB7A3), shape = RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sample at ${formatDateTime(point.timestamp)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (point.cellId.isBlank()) {
                        "Cell info unavailable"
                    } else {
                        "Cell ${point.cellId} | PCI ${point.pci} | EARFCN ${point.earfcn}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    MetricTile("RSRP", "${point.rsrp} dBm")
                    MetricTile("RSRQ", "${point.rsrq} dB")
                    MetricTile("SINR", "${point.sinr} dB")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    MetricTile("Jitter", formatJitter(point.jitterMs))
                    MetricTile("Loss", formatPct(point.lossPct))
                }
            }
        }
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
        put(MediaStore.MediaColumns.MIME_TYPE, if (file.extension.lowercase() == "csv") "text/csv" else "application/x-ndjson")
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
