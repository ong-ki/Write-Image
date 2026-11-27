package com.usbbootable.creator.service

import android.content.Context
import com.usbbootable.creator.R
import com.usbbootable.creator.core.usb.UsbClaimInterfaceException
import com.usbbootable.creator.core.usb.UsbDisconnectedException
import com.usbbootable.creator.core.usb.UsbStorageException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UsbEjectPhase {
    IDLE,
    EJECTING,
    SUCCEEDED,
    COMPLETED_WARNING,
    FAILED,
    DISCONNECTED_WARNING
}

data class UsbEjectState(
    val phase: UsbEjectPhase = UsbEjectPhase.IDLE,
    val targetDeviceId: Int? = null,
    val targetDeviceName: String = "",
    val errorMessage: String? = null,
    val detailLog: String? = null
) {
    val isEjecting: Boolean
        get() = phase == UsbEjectPhase.EJECTING

    val isEjectedOrWarning: Boolean
        get() = phase == UsbEjectPhase.SUCCEEDED || phase == UsbEjectPhase.COMPLETED_WARNING
}

/**
 * Process-scoped shared state for safe USB ejection across [UsbWriteService] (auto-eject)
 * and UI ViewModels (manual eject, retry, and unplug-before-eject warnings).
 */
class UsbSafeEjectStore {

    private val lock = Any()
    private val _state = MutableStateFlow(UsbEjectState())
    val state: StateFlow<UsbEjectState> = _state.asStateFlow()

    fun beginEject(deviceId: Int, deviceName: String) {
        synchronized(lock) {
            _state.value = UsbEjectState(
                phase = UsbEjectPhase.EJECTING,
                targetDeviceId = deviceId,
                targetDeviceName = deviceName,
                errorMessage = null,
                detailLog = null
            )
        }
    }

    fun markSucceeded(deviceId: Int, deviceName: String, detailLog: String? = null) {
        synchronized(lock) {
            // If the drive already unplugged mid-eject, preserve the unplug warning instead of
            // overwriting it with success.
            val current = _state.value
            if (current.phase == UsbEjectPhase.DISCONNECTED_WARNING && current.targetDeviceId == deviceId) {
                return
            }
            _state.value = UsbEjectState(
                phase = UsbEjectPhase.SUCCEEDED,
                targetDeviceId = deviceId,
                targetDeviceName = deviceName,
                errorMessage = null,
                detailLog = detailLog
            )
        }
    }

    fun markCompletedWithWarning(
        deviceId: Int,
        deviceName: String,
        warningMessage: String,
        detailLog: String? = null
    ) {
        synchronized(lock) {
            val current = _state.value
            if (current.phase == UsbEjectPhase.DISCONNECTED_WARNING && current.targetDeviceId == deviceId) {
                return
            }
            _state.value = UsbEjectState(
                phase = UsbEjectPhase.COMPLETED_WARNING,
                targetDeviceId = deviceId,
                targetDeviceName = deviceName,
                errorMessage = warningMessage,
                detailLog = detailLog
            )
        }
    }

    fun markFailed(
        deviceId: Int?,
        deviceName: String,
        errorMessage: String,
        detailLog: String? = null
    ) {
        synchronized(lock) {
            val current = _state.value
            if (current.phase == UsbEjectPhase.DISCONNECTED_WARNING &&
                (deviceId == null || current.targetDeviceId == deviceId)
            ) {
                return
            }
            _state.value = UsbEjectState(
                phase = UsbEjectPhase.FAILED,
                targetDeviceId = deviceId,
                targetDeviceName = deviceName,
                errorMessage = errorMessage,
                detailLog = detailLog
            )
        }
    }

    fun markDisconnectedWarning(
        deviceId: Int?,
        deviceName: String,
        warningMessage: String,
        detailLog: String? = null
    ) {
        synchronized(lock) {
            _state.value = UsbEjectState(
                phase = UsbEjectPhase.DISCONNECTED_WARNING,
                targetDeviceId = deviceId,
                targetDeviceName = deviceName,
                errorMessage = warningMessage,
                detailLog = detailLog
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            _state.value = UsbEjectState()
        }
    }
}

fun resolveEjectErrorMessage(context: Context, error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        error is UsbDisconnectedException -> context.getString(R.string.eject_banner_unplugged_warning)
        error is UsbClaimInterfaceException -> context.getString(R.string.eject_error_claim_interface)
        raw.contains("permission", ignoreCase = true) ->
            context.getString(R.string.eject_error_no_permission)
        raw.contains("Bulk-Only Transport", ignoreCase = true) ||
            (raw.contains("BOT", ignoreCase = false) && raw.contains("interface", ignoreCase = true)) ->
            context.getString(R.string.eject_error_unsupported_transport)
        raw.contains("busy", ignoreCase = true) ->
            context.getString(R.string.eject_error_busy)
        raw.contains("open USB device connection", ignoreCase = true) ->
            context.getString(R.string.eject_error_open_connection)
        else -> context.getString(R.string.eject_banner_failed_generic)
    }
}

fun buildEjectExceptionDetailLog(error: Throwable): String {
    val sb = StringBuilder()
    var cur: Throwable? = error
    var depth = 0
    while (cur != null && depth < 5) {
        if (depth > 0) sb.append("\nCaused by: ")
        sb.append(cur::class.simpleName ?: "Exception")
        val msg = cur.message?.trim()
        if (!msg.isNullOrBlank()) {
            sb.append(": ").append(msg)
        }
        cur = cur.cause
        depth++
    }
    return sb.toString()
}
