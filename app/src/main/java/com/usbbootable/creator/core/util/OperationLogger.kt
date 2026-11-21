package com.usbbootable.creator.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

class OperationLogger(private val maxEntries: Int = 400) {

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun info(tag: String, message: String) = append(LogLevel.INFO, tag, message)
    fun success(tag: String, message: String) = append(LogLevel.SUCCESS, tag, message)
    fun warn(tag: String, message: String) = append(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = append(LogLevel.ERROR, tag, message)

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }

    private fun append(level: LogLevel, tag: String, message: String) {
        val entry = synchronized(lock) {
            val ts = timeFormat.format(Date())
            val next = LogEntry(ts, level, tag, message)
            val current = _entries.value
            val updated = if (current.size >= maxEntries) {
                current.drop(current.size - maxEntries + 1) + next
            } else {
                current + next
            }
            _entries.value = updated
            next
        }
        try {
            when (level) {
                LogLevel.INFO, LogLevel.SUCCESS -> android.util.Log.i("UsbBoot/$tag", entry.message)
                LogLevel.WARN -> android.util.Log.w("UsbBoot/$tag", entry.message)
                LogLevel.ERROR -> android.util.Log.e("UsbBoot/$tag", entry.message)
            }
        } catch (_: Throwable) {
            // Ignored when running inside pure JVM unit tests without Android Log stubs
        }
    }
}
