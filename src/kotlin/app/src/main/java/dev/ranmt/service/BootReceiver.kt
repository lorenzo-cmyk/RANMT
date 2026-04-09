package dev.ranmt.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = SessionPrefs(context)
        val active = prefs.loadActive() ?: return
        val resumeIntent = MeasurementService.resumeIntent(context, active)
        context.startForegroundService(resumeIntent)
    }
}
