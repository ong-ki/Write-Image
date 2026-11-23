package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.ui.theme.GlassDialogShape
import com.usbbootable.creator.ui.theme.glassDialogContainerColor
import com.usbbootable.creator.ui.theme.glassDialogEnter

@Composable
fun ConfirmEraseDialog(
    iso: SelectedIsoInfo,
    drive: UsbDriveInfo,
    requiresNonBootableAck: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var eraseConfirmed by remember(iso.uriString, drive.deviceId) { mutableStateOf(false) }
    var nonBootableConfirmed by remember(iso.uriString, drive.deviceId, requiresNonBootableAck) {
        mutableStateOf(!requiresNonBootableAck)
    }
    var submitted by remember(iso.uriString, drive.deviceId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GlassDialogShape,
        containerColor = glassDialogContainerColor(),
        modifier = Modifier.glassDialogEnter(),
        icon = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = "Confirm Permanent USB Erase",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ALL EXISTING DATA, PARTITIONS, AND FILES ON THE TARGET USB DRIVE WILL BE PERMANENTLY DESTROYED.",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Target USB Drive (External OTG Only)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = drive.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${ByteUtils.formatDecimalCapacity(drive.capacityBytes)} • VID:PID ${drive.vidPidHex}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!drive.serialNumber.isNullOrBlank()) {
                    Text(
                        text = "Serial: ${drive.serialNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Source Image & Write Method",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${iso.displayName} (${ByteUtils.formatBytes(iso.sizeBytes)})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = iso.bootSupport.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = iso.bootSupport.technicalExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = eraseConfirmed,
                        onCheckedChange = { eraseConfirmed = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "I understand all data on ${drive.displayName} will be permanently erased.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (requiresNonBootableAck) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = nonBootableConfirmed,
                            onCheckedChange = { nonBootableConfirmed = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "I understand this image is NOT guaranteed to boot from USB on standard PCs and still want to write it raw.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!submitted) {
                        submitted = true
                        onConfirm()
                    }
                },
                enabled = !submitted && eraseConfirmed && nonBootableConfirmed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("ERASE & WRITE")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}
