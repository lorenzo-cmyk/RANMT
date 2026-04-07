package dev.ranmt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Column(modifier = Modifier.padding(16.dp)) {
        CenterAlignedTopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                Text("Default Export Format", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(ExportFormat.Csv, ExportFormat.Jsonl).forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = format == mode,
                            onClick = { format = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)
                        ) {
                            Text(mode.name)
                        }
                    }
                }
                Text("Default Export Destination", style = MaterialTheme.typography.labelLarge)
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
                    Text("Include metadata in CSV", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = includeCsvMeta, onCheckedChange = { includeCsvMeta = it })
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.Button(onClick = {
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
    }
}
