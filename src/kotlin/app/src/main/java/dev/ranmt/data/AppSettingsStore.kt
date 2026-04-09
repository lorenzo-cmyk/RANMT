package dev.ranmt.data

import android.content.Context

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val interval = prefs.getLong(KEY_INTERVAL, 1000L)
        val accuracy =
            prefs.getString(KEY_ACCURACY, AccuracyMode.High.name) ?: AccuracyMode.High.name
        val format =
            prefs.getString(KEY_EXPORT_FORMAT, ExportFormat.Csv.name) ?: ExportFormat.Csv.name
        val destination = prefs.getString(KEY_EXPORT_DEST, ExportDestination.Share.name)
            ?: ExportDestination.Share.name
        val includeMeta = prefs.getBoolean(KEY_CSV_META, true)
        return AppSettings(
            samplingIntervalMs = interval.coerceAtLeast(250L),
            accuracyMode = AccuracyMode.valueOf(accuracy),
            defaultExportFormat = ExportFormat.valueOf(format),
            defaultExportDestination = ExportDestination.valueOf(destination),
            includeMetadataInCsv = includeMeta
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putLong(KEY_INTERVAL, settings.samplingIntervalMs)
            .putString(KEY_ACCURACY, settings.accuracyMode.name)
            .putString(KEY_EXPORT_FORMAT, settings.defaultExportFormat.name)
            .putString(KEY_EXPORT_DEST, settings.defaultExportDestination.name)
            .putBoolean(KEY_CSV_META, settings.includeMetadataInCsv)
            .apply()
    }

    companion object {
        private const val PREFS = "ranmt_settings"
        private const val KEY_INTERVAL = "sampling_interval_ms"
        private const val KEY_ACCURACY = "accuracy_mode"
        private const val KEY_EXPORT_FORMAT = "export_format"
        private const val KEY_EXPORT_DEST = "export_destination"
        private const val KEY_CSV_META = "include_csv_meta"
    }
}
