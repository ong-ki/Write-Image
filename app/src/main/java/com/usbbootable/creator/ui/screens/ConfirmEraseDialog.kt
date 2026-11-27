package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
    // Saveable so the confirmation state survives rotation.
    var eraseConfirmed by rememberSaveable(iso.uriString, drive.deviceId) { mutableStateOf(false) }
    var nonBootableConfirmed by rememberSaveable(iso.uriString, drive.deviceId, requiresNonBootableAck) {
        mutableStateOf(!requiresNonBootableAck)
    }
    var submitted by rememberSaveable(iso.uriString, drive.deviceId) { mutableStateOf(false) }

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
                text = "Erase USB drive?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AppBanner(
                    message = "All data on ${drive.displayName} will be erased. This cannot be undone.",
                    tone = BannerTone.ERROR,
                    icon = Icons.Rounded.Warning,
                    fontWeight = FontWeight.Bold,
                    rounded = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Target USB drive (external OTG only)",
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

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Source image and write method",
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
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = iso.bootSupport.technicalExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                AckRow(
                    checked = eraseConfirmed,
                    onCheckedChange = { eraseConfirmed = it },
                    text = "I understand all data on ${drive.displayName} will be permanently erased."
                )

                if (requiresNonBootableAck) {
                    AckRow(
                        checked = nonBootableConfirmed,
                        onCheckedChange = { nonBootableConfirmed = it },
                        text = "I understand this image is NOT guaranteed to boot from USB on standard PCs and still want to write it raw.",
                        isWarning = true
                    )
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
                shape = HomeControlShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("ERASE & WRITE", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("CANCEL")
            }
        }
    )
}

/** Whole-row toggle with a 48dp minimum touch target and checkbox semantics. */
@Composable
private fun AckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    isWarning: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
