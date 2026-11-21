package com.usbbootable.creator.core.storage

import android.content.Context
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class WriteBufferOption(val label: String, val bytesOrAuto: Int) {
    AUTO("Auto (Adaptive)", 0),
    KB_64("64 KiB", 64 * 1024),
    KB_128("128 KiB", 128 * 1024),
    KB_256("256 KiB", 256 * 1024),
    KB_512("512 KiB", 512 * 1024);

    companion object {
        fun fromName(name: String?): WriteBufferOption =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bufferOption: WriteBufferOption = WriteBufferOption.AUTO,
    val verifyAfterWrite: Boolean = true
)

data class OperationHistoryRecord(
    val id: Long,
    val timestampMs: Long,
    val isoName: String,
    val isoSizeBytes: Long,
    val driveName: String,
    val driveCapacityBytes: Long,
    val strategyLabel: String,
    val status: WritePhase,
    val averageSpeedBytesPerSec: Double,
    val durationMs: Long,
    val verified: Boolean,
    val summaryNote: String
)

/**
 * Lightweight, zero-dependency persistence for user settings and real USB flashing history.
 */
class AppPreferencesStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<OperationHistoryRecord>> = _history.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        val updated = _settings.value.copy(themeMode = mode)
        _settings.value = updated
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun setBufferOption(option: WriteBufferOption) {
        val updated = _settings.value.copy(bufferOption = option)
        _settings.value = updated
        prefs.edit().putString(KEY_BUFFER_OPTION, option.name).apply()
    }

    fun setVerifyAfterWrite(enabled: Boolean) {
        val updated = _settings.value.copy(verifyAfterWrite = enabled)
        _settings.value = updated
        prefs.edit().putBoolean(KEY_VERIFY_AFTER_WRITE, enabled).apply()
    }

    fun recordOperation(entry: OperationHistoryRecord) {
        val updated = (listOf(entry) + _history.value).take(MAX_HISTORY_ITEMS)
        _history.value = updated
        saveHistory(updated)
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY_JSON).apply()
    }

    private fun loadSettings(): AppSettings {
        val theme = ThemeMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        } ?: ThemeMode.SYSTEM
        val buffer = WriteBufferOption.fromName(prefs.getString(KEY_BUFFER_OPTION, WriteBufferOption.AUTO.name))
        val verify = prefs.getBoolean(KEY_VERIFY_AFTER_WRITE, true)
        return AppSettings(
            themeMode = theme,
            bufferOption = buffer,
            verifyAfterWrite = verify
        )
    }

    private fun loadHistory(): List<OperationHistoryRecord> {
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<OperationHistoryRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val statusName = obj.optString("status", WritePhase.FAILED.name)
                val status = WritePhase.entries.firstOrNull { it.name == statusName } ?: WritePhase.FAILED
                list.add(
                    OperationHistoryRecord(
                        id = obj.optLong("id", i.toLong()),
                        timestampMs = obj.optLong("timestampMs", 0L),
                        isoName = obj.optString("isoName", "Unknown ISO"),
                        isoSizeBytes = obj.optLong("isoSizeBytes", 0L),
                        driveName = obj.optString("driveName", "USB Drive"),
                        driveCapacityBytes = obj.optLong("driveCapacityBytes", 0L),
                        strategyLabel = obj.optString("strategyLabel", ""),
                        status = status,
                        averageSpeedBytesPerSec = obj.optDouble("avgSpeed", 0.0),
                        durationMs = obj.optLong("durationMs", 0L),
                        verified = obj.optBoolean("verified", false),
                        summaryNote = obj.optString("summaryNote", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(records: List<OperationHistoryRecord>) {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("timestampMs", r.timestampMs)
                    put("isoName", r.isoName)
                    put("isoSizeBytes", r.isoSizeBytes)
                    put("driveName", r.driveName)
                    put("driveCapacityBytes", r.driveCapacityBytes)
                    put("strategyLabel", r.strategyLabel)
                    put("status", r.status.name)
                    put("avgSpeed", r.averageSpeedBytesPerSec)
                    put("durationMs", r.durationMs)
                    put("verified", r.verified)
                    put("summaryNote", r.summaryNote)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY_JSON, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "usb_bootable_creator_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BUFFER_OPTION = "buffer_option"
        private const val KEY_VERIFY_AFTER_WRITE = "verify_after_write"
        private const val KEY_HISTORY_JSON = "operation_history_json"
        private const val MAX_HISTORY_ITEMS = 50
    }
}
