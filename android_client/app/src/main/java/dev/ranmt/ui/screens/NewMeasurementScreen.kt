package dev.ranmt.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ranmt.data.MeasurementConfig

@Composable
fun NewMeasurementScreen(
    config: MeasurementConfig,
    onConfigChange: (MeasurementConfig) -> Unit,
    onStart: () -> Unit
) {
    var serverIp by remember { mutableStateOf(config.serverIp) }
    var serverPort by remember { mutableStateOf(config.serverPort.toString()) }
    var bitrate by remember { mutableStateOf(config.bitrateBps.toString()) }
    var direction by remember { mutableStateOf(config.direction) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = "New Measurement",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Configure the test parameters before you start.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = serverIp,
                    onValueChange = { serverIp = it },
                    label = { Text("Server IPv4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bitrate,
                    onValueChange = { bitrate = it },
                    label = { Text("Target Bitrate (bps)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Direction",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("Uplink", "Downlink").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = direction == label,
                            onClick = { direction = label },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    )
                }
            ) {
                Text("Grant Permissions")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    val updated = MeasurementConfig(
                        serverIp = serverIp.trim(),
                        serverPort = serverPort.toIntOrNull() ?: 4433,
                        direction = direction,
                        bitrateBps = bitrate.toIntOrNull() ?: 8000
                    )
                    onConfigChange(updated)
                    onStart()
                }
            ) {
                Text("Start")
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "Foreground service and wakelock are enabled when the test starts. " +
                "For long drives, disable battery optimizations for RANMT.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}
