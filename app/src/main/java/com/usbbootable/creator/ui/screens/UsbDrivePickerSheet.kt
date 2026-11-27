package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.usb.UsbTransportSupport
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.ui.theme.GlassSheetShape
import com.usbbootable.creator.ui.theme.glassDialogContainerColor
import com.usbbootable.creator.ui.theme.glassScrimColor
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDrivePickerSheet(
    drives: List<UsbDriveInfo>,
    selectedDriveId: Int?,
    isUsbHostSupported: Boolean,
    onSelectDrive: (Int) -> Unit,
    onRequestPermission: (Int) -> Unit,
    onRetryProbe: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = GlassSheetShape,
        containerColor = glassDialogContainerColor(),
        scrimColor = glassScrimColor()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Select Target USB Drive",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Only external USB OTG drives are listed. Internal storage is never exposed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh USB drives"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isUsbHostSupported) {
                AppBanner(
                    message = "This device does not report Android USB Host (OTG) hardware support.",
                    tone = BannerTone.ERROR,
                    icon = Icons.Rounded.Warning,
                    rounded = true
                )
            } else if (drives.isEmpty()) {
                AppEmptyState(
                    icon = Icons.Rounded.UsbOff,
                    title = "No USB Flash Drive Detected",
                    message = "1. Connect a USB flash drive via a USB-C / Micro-USB OTG adapter.\n" +
                        "2. Enable \"OTG Connection\" in phone Settings if your OEM requires it (Oppo, Realme, OnePlus, Vivo).\n" +
                        "3. Tap Refresh if the drive was connected before opening the app.",
                    compact = false,
                    actionLabel = "SCAN USB OTG",
                    onAction = onRefresh
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(drives, key = { it.deviceId }) { drive ->
                        val isSelected = drive.deviceId == selectedDriveId
                        val isSupported = drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT

                        AppCard(
                            modifier = Modifier.clickable(enabled = isSupported) {
                                onSelectDrive(drive.deviceId)
                                if (drive.hasPermission && drive.isProbed) {
                                    onDismiss()
                                }
                            },
                            accentBorderColor = if (isSelected) MaterialTheme.colorScheme.primary else null,
                            borderWidth = if (isSelected) 2.dp else 1.dp,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                StatusIconBadge(
                                    icon = Icons.Rounded.Usb,
                                    tint = if (isSupported) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = drive.displayName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "VID:PID ${drive.vidPidHex} • ${drive.deviceName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = statusSuccessColor()
                                    )
                                }
                            }

                            when {
                                drive.transportSupport == UsbTransportSupport.UNSUPPORTED_UASP_ONLY -> {
                                    AppBanner(
                                        message = "Unsupported protocol: UASP-only enclosure (0x62) without Bulk-Only Transport fallback.",
                                        tone = BannerTone.ERROR,
                                        rounded = true
                                    )
                                }

                                !drive.hasPermission -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "USB Host permission required to read capacity",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = statusWarningColor()
                                        )
                                        AppTonalButton(
                                            text = "GRANT",
                                            onClick = { onRequestPermission(drive.deviceId) }
                                        )
                                    }
                                }

                                drive.probeError != null -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AppBanner(
                                            message = drive.probeError,
                                            tone = BannerTone.ERROR,
                                            rounded = true
                                        )
                                        AppOutlineButton(
                                            text = "RETRY SCSI PROBE",
                                            onClick = { onRetryProbe(drive.deviceId) },
                                            compactTextStyle = true
                                        )
                                    }
                                }

                                !drive.isProbed -> {
                                    Text(
                                        text = "Probing SCSI geometry & capacity...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                else -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = ByteUtils.formatDecimalCapacity(drive.capacityBytes),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = statusSuccessColor(),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${drive.logicalBlockSize} B sectors",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (drive.isWriteProtected) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Hardware write-protected (read-only)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
