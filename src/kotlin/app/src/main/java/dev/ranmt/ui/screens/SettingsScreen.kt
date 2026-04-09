package dev.ranmt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ranmt.data.AccuracyMode
import dev.ranmt.data.AppSettings
import dev.ranmt.data.ExportDestination
import dev.ranmt.data.ExportFormat

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit
) {
    var interval by remember { mutableStateOf(settings.samplingIntervalMs.toString()) }
    var accuracy by remember { mutableStateOf(settings.accuracyMode) }
    var format by remember { mutableStateOf(settings.defaultExportFormat) }
    var destination by remember { mutableStateOf(settings.defaultExportDestination) }
    var includeCsvMeta by remember { mutableStateOf(settings.includeMetadataInCsv) }

    Column(
        modifier = Modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Tune sampling, accuracy, and exports.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Sampling",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Control location and telemetry cadence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it },
                    label = { Text("Sampling Interval (ms)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Accuracy", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(AccuracyMode.Balanced, AccuracyMode.High).forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = accuracy == mode,
                            onClick = { accuracy = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)
                        ) {
                            Text(mode.name)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Export",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Defaults for sharing sessions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text("Format", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(ExportFormat.Csv, ExportFormat.Jsonl).forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = format == mode,
                            onClick = { format = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)
                        ) {
                            Text(mode.name.uppercase())
                        }
                    }
                }
                Text("Destination", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(ExportDestination.Share, ExportDestination.Downloads).forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = destination == mode,
                            onClick = { destination = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)
                        ) {
                            Text(mode.name)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CSV metadata",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Include summary stats in CSV headers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = includeCsvMeta,
                        onCheckedChange = { includeCsvMeta = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                            checkedTrackColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = {
                val updated = AppSettings(
                    samplingIntervalMs = interval.toLongOrNull() ?: settings.samplingIntervalMs,
                    accuracyMode = accuracy,
                    defaultExportFormat = format,
                    defaultExportDestination = destination,
                    includeMetadataInCsv = includeCsvMeta
                )
                onUpdate(updated)
            }) {
                Text("Save")
            }
        }
    }
}
