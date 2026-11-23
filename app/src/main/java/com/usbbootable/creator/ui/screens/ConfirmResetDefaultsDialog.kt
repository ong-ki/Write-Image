package com.usbbootable.creator.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.usbbootable.creator.ui.theme.GlassDialogShape
import com.usbbootable.creator.ui.theme.glassDialogContainerColor
import com.usbbootable.creator.ui.theme.glassDialogEnter

/**
 * Safety confirmation for the bulk preference reset. The copy explicitly states what is and is
 * not affected so users do not expect files, USB data, or operation history to be removed.
 */
@Composable
fun ConfirmResetDefaultsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GlassDialogShape,
        containerColor = glassDialogContainerColor(),
        modifier = Modifier.glassDialogEnter(),
        icon = {
            Icon(
                imageVector = Icons.Rounded.Restore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Restore Default Settings?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = "All customized application settings will be restored to their original defaults, " +
                    "including theme mode, progress bar style, neon colors, glow effects, buffer size, " +
                    "verification, and Windows bypass options.\n\n" +
                    "Your ISO files, USB drives, personal files, operation history, and Recent Operations " +
                    "will not be changed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = "RESET", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}
