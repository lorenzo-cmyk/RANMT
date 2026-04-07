package dev.ranmt.ui.screens

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ranmt.data.ConnectionState
import dev.ranmt.data.MeasurementConfig
import dev.ranmt.service.MeasurementService
import kotlin.random.Random

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

@Composable
fun RunningScreen(
    config: MeasurementConfig,
    connectionState: ConnectionState,
    onStop: () -> Unit,
    onStateChange: (ConnectionState) -> Unit
) {
    val context = LocalContext.current
    var seconds by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        context.startForegroundService(Intent(context, MeasurementService::class.java))
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            context.stopService(Intent(context, MeasurementService::class.java))
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            seconds += 1
            if (seconds % 12 == 0) {
                val next = when (connectionState) {
                    ConnectionState.Connected -> ConnectionState.Buffering
                    ConnectionState.Buffering -> ConnectionState.Reconnecting
                    ConnectionState.Reconnecting -> ConnectionState.Connected
                }
                onStateChange(next)
            }
        }
    }

    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = "Live Dashboard",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "${config.serverIp}:${config.serverPort} | ${config.direction} | ${config.bitrateBps} bps",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(14.dp))

        TimerCard(seconds = seconds, state = connectionState)
        Spacer(modifier = Modifier.height(16.dp))
        SparklineRow()
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
            ConnectionState.Buffering -> Color(0xFFF6B64B)
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
                        ConnectionState.Buffering -> "Buffering for tunnel"
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
private fun SparklineRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SparklineCard(label = "RSRP", modifier = Modifier.weight(1f))
        SparklineCard(label = "RTT / Jitter", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SparklineCard(label: String, modifier: Modifier = Modifier) {
    val values = remember { List(30) { Random.nextFloat() } }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(60.dp)) {
                val step = size.width / (values.size - 1)
                for (i in 0 until values.lastIndex) {
                    drawLine(
                        color = Color(0xFF4A8CFF),
                        start = androidx.compose.ui.geometry.Offset(i * step, size.height * (1 - values[i])),
                        end = androidx.compose.ui.geometry.Offset((i + 1) * step, size.height * (1 - values[i + 1])),
                        strokeWidth = 4f
                    )
                }
            }
        }
    }
}
