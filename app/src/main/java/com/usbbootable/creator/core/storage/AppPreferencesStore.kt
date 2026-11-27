package com.usbbootable.creator.core.storage

import android.content.Context
import androidx.core.content.edit
import com.usbbootable.creator.core.writer.WindowsBypassOptions
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

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
    val verifyAfterWrite: Boolean = true,
    val autoEjectAfterWrite: Boolean = false,
    val windowsBypassOptions: WindowsBypassOptions = WindowsBypassOptions.NONE
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
    private val historyLock = Any()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val initialHistory = loadHistory()
    private val lastHistoryId = AtomicLong(initialHistory.maxOfOrNull { it.id } ?: 0L)
    private val _history = MutableStateFlow(initialHistory)
    val history: StateFlow<List<OperationHistoryRecord>> = _history.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _settings.update { it.copy(themeMode = mode) }
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
    }

    fun setBufferOption(option: WriteBufferOption) {
        _settings.update { it.copy(bufferOption = option) }
        prefs.edit { putString(KEY_BUFFER_OPTION, option.name) }
    }

    fun setVerifyAfterWrite(enabled: Boolean) {
        _settings.update { it.copy(verifyAfterWrite = enabled) }
        prefs.edit { putBoolean(KEY_VERIFY_AFTER_WRITE, enabled) }
    }

    fun setAutoEjectAfterWrite(enabled: Boolean) {
        _settings.update { it.copy(autoEjectAfterWrite = enabled) }
        prefs.edit { putBoolean(KEY_AUTO_EJECT_AFTER_WRITE, enabled) }
    }

    fun setWindowsBypassOptions(options: WindowsBypassOptions) {
        _settings.update { it.copy(windowsBypassOptions = options) }
        prefs.edit {
            putBoolean(KEY_BYPASS_TPM, options.bypassTpm)
            putBoolean(KEY_BYPASS_SECURE_BOOT, options.bypassSecureBoot)
            putBoolean(KEY_BYPASS_RAM, options.bypassRam)
            putBoolean(KEY_BYPASS_CPU, options.bypassCpu)
        }
    }

    fun recordOperation(entry: OperationHistoryRecord) {
        synchronized(historyLock) {
            val uniqueId = lastHistoryId.updateAndGet { prev ->
                if (entry.id > prev) entry.id else prev + 1L
            }
            val normalized = if (entry.id == uniqueId) entry else entry.copy(id = uniqueId)
            val updated = (listOf(normalized) + _history.value).take(MAX_HISTORY_ITEMS)
            _history.value = updated
            saveHistory(updated)
        }
    }

    fun appendSummaryNoteToLatestCompletedWrite(driveName: String, extraNote: String) {
        if (extraNote.isBlank()) return
        synchronized(historyLock) {
            val current = _history.value
            val idx = current.indexOfFirst {
                it.status == WritePhase.COMPLETED && it.driveName == driveName
            }
            if (idx < 0) return
            val existing = current[idx]
            val merged = if (existing.summaryNote.isBlank()) {
                extraNote
            } else if (existing.summaryNote.contains(extraNote)) {
                existing.summaryNote
            } else {
                "${existing.summaryNote} | $extraNote"
            }
            val updated = current.toMutableList().apply {
                this[idx] = existing.copy(summaryNote = merged)
            }
            _history.value = updated
            saveHistory(updated)
        }
    }

    fun clearHistory() {
        synchronized(historyLock) {
            _history.value = emptyList()
            prefs.edit { remove(KEY_HISTORY_JSON) }
        }
    }

    /**
     * Restores only user-customizable application settings. Deliberately leaves the operation
     * history key untouched, and never touches ISO files, USB state, or any device configuration.
     */
    fun resetDefaults() {
        val defaults = AppSettings()
        _settings.value = defaults
        prefs.edit {
            putString(KEY_THEME_MODE, defaults.themeMode.name)
            putString(KEY_BUFFER_OPTION, defaults.bufferOption.name)
            putBoolean(KEY_VERIFY_AFTER_WRITE, defaults.verifyAfterWrite)
            putBoolean(KEY_AUTO_EJECT_AFTER_WRITE, defaults.autoEjectAfterWrite)
            putBoolean(KEY_BYPASS_TPM, defaults.windowsBypassOptions.bypassTpm)
            putBoolean(KEY_BYPASS_SECURE_BOOT, defaults.windowsBypassOptions.bypassSecureBoot)
            putBoolean(KEY_BYPASS_RAM, defaults.windowsBypassOptions.bypassRam)
            putBoolean(KEY_BYPASS_CPU, defaults.windowsBypassOptions.bypassCpu)
        }
    }

    private fun loadSettings(): AppSettings {
        val theme = ThemeMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        } ?: ThemeMode.SYSTEM
        val buffer = WriteBufferOption.fromName(prefs.getString(KEY_BUFFER_OPTION, WriteBufferOption.AUTO.name))
        val verify = prefs.getBoolean(KEY_VERIFY_AFTER_WRITE, true)
        val autoEject = prefs.getBoolean(KEY_AUTO_EJECT_AFTER_WRITE, false)
        val bypass = WindowsBypassOptions(
            bypassTpm = prefs.getBoolean(KEY_BYPASS_TPM, false),
            bypassSecureBoot = prefs.getBoolean(KEY_BYPASS_SECURE_BOOT, false),
            bypassRam = prefs.getBoolean(KEY_BYPASS_RAM, false),
            bypassCpu = prefs.getBoolean(KEY_BYPASS_CPU, false)
        )
        return AppSettings(
            themeMode = theme,
            bufferOption = buffer,
            verifyAfterWrite = verify,
            autoEjectAfterWrite = autoEject,
            windowsBypassOptions = bypass
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
        prefs.edit { putString(KEY_HISTORY_JSON, arr.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "usb_bootable_creator_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BUFFER_OPTION = "buffer_option"
        private const val KEY_VERIFY_AFTER_WRITE = "verify_after_write"
        private const val KEY_AUTO_EJECT_AFTER_WRITE = "auto_eject_after_write"
        private const val KEY_BYPASS_TPM = "win_bypass_tpm"
        private const val KEY_BYPASS_SECURE_BOOT = "win_bypass_secure_boot"
        private const val KEY_BYPASS_RAM = "win_bypass_ram"
        private const val KEY_BYPASS_CPU = "win_bypass_cpu"
        private const val KEY_HISTORY_JSON = "operation_history_json"
        private const val MAX_HISTORY_ITEMS = 50
    }
}
