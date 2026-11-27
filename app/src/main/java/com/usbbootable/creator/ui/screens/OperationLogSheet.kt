package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.core.util.LogEntry
import com.usbbootable.creator.core.util.LogLevel
import com.usbbootable.creator.ui.theme.GlassCardShape20
import com.usbbootable.creator.ui.theme.GlassSheetShape
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.glassDialogContainerColor
import com.usbbootable.creator.ui.theme.glassScrimColor
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationLogSheet(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val appColors = LocalAppColors.current

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
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "USB / SCSI Diagnostic Log",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "${logs.size} event${if (logs.size == 1) "" else "s"} recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("CLEAR", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (logs.isEmpty()) {
                AppEmptyState(
                    icon = Icons.Rounded.Terminal,
                    message = "No events recorded yet.",
                    compact = true
                )
            } else {
                Surface(
                    color = appColors.surfaceInner,
                    shape = GlassCardShape20,
                    border = BorderStroke(1.dp, appColors.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 420.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(logs.asReversed(), key = { it.id }) { entry ->
                            val tint = when (entry.level) {
                                LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                                LogLevel.SUCCESS -> statusSuccessColor()
                                LogLevel.WARN -> statusWarningColor()
                                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            }
                            Text(
                                text = "[${entry.timestamp}] [${entry.tag}] ${entry.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = tint
                            )
                        }
                    }
                }
            }
        }
    }
}
