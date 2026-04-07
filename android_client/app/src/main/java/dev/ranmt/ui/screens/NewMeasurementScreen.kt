package dev.ranmt.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ranmt.data.MeasurementConfig

@Composable
fun NewMeasurementScreen(
    config: MeasurementConfig,
    onConfigChange: (MeasurementConfig) -> Unit,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    var serverIp by remember { mutableStateOf(config.serverIp) }
    var serverPort by remember { mutableStateOf(config.serverPort.toString()) }
    var bitrate by remember { mutableStateOf(config.bitrateBps.toString()) }
    var direction by remember { mutableStateOf(config.direction) }

    val permissionList = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION to "Precise Location",
        Manifest.permission.ACCESS_COARSE_LOCATION to "Coarse Location",
        Manifest.permission.READ_PHONE_STATE to "Phone State",
        Manifest.permission.POST_NOTIFICATIONS to "Notifications"
    )
    val powerManager = context.getSystemService(PowerManager::class.java)
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var allGranted by remember { mutableStateOf(false) }
    var ignoreBatteryOptimizations by remember { mutableStateOf(true) }

    fun refreshStatus() {
        val states = permissionList.associate { (permission, _) ->
            permission to (ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        permissionStates = states
        allGranted = states.values.all { it }
        ignoreBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
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
                Text(
                    text = "Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Set server and test parameters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
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

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Before You Start",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Make sure each requirement is marked Ready. Battery optimization should be Disabled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                permissionList.forEach { (permission, label) ->
                    val granted = permissionStates[permission] == true
                    StatusRow(
                        label = label,
                        status = if (granted) "Ready" else "Needed",
                        ready = granted
                    )
                }

                StatusRow(
                    label = "Battery optimization",
                    status = if (ignoreBatteryOptimizations) "Disabled" else "Enabled",
                    ready = ignoreBatteryOptimizations
                )

                if (!allGranted) {
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
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Missing Permissions")
                    }
                }

                if (!ignoreBatteryOptimizations) {
                    Button(
                        onClick = {
                            val intent = Intent().apply {
                                action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                } else {
                                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                                }
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disable Battery Optimization")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Button(
            enabled = allGranted && ignoreBatteryOptimizations,
            onClick = {
                val updated = MeasurementConfig(
                    serverIp = serverIp.trim(),
                    serverPort = serverPort.toIntOrNull() ?: 4433,
                    direction = direction,
                    bitrateBps = bitrate.toIntOrNull() ?: 8000
                )
                onConfigChange(updated)
                onStart()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Measurement")
        }
    }
}

@Composable
private fun StatusRow(label: String, status: String, ready: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = if (ready) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
