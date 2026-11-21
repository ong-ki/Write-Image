package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TechnicalGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Technical Architecture & Compatibility",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GuideSection(
                    title = "1. No-Root USB Writing (USB Host API + SCSI BOT)",
                    body = "Standard Android blocks direct access to /dev/block/* via Linux permissions and SELinux. " +
                        "Instead of requiring root, USB Bootable Creator uses Android's official USB Host API " +
                        "(UsbManager + UsbDeviceConnection) to claim the USB Mass Storage Bulk-Only Transport " +
                        "(BOT, class 0x08 / subclass 0x06 / protocol 0x50) interface and issue real SCSI commands " +
                        "(INQUIRY, READ CAPACITY, READ(10/16), WRITE(10/16), SYNCHRONIZE CACHE) directly to the USB flash drive."
                )

                HorizontalDivider()

                GuideSection(
                    title = "2. Android USB Host Limitations & Root Notes",
                    body = "• Root is NOT required for standard USB OTG flash drives.\n" +
                        "• Hardware USB OTG Host support is required on the Android phone.\n" +
                        "• Some Android OEMs auto-mount drives via vold; if claiming the USB interface fails, " +
                        "eject/unmount the USB drive in Android Settings -> Storage (leaving it plugged in) and retry.\n" +
                        "• UASP-only USB SSD enclosures (protocol 0x62 without a BOT 0x50 fallback interface) cannot be " +
                        "accessed through user-space BOT without root/kernel drivers.\n" +
                        "• Internal Android storage is NEVER exposed through UsbManager, providing hardware-level protection against overwriting your phone."
                )

                HorizontalDivider()

                GuideSection(
                    title = "3. Linux Hybrid ISOs & Raw Images (.iso / .img)",
                    body = "Modern Linux ISOs (Ubuntu, Fedora, Debian, Arch, Mint, Kali, etc.) are isohybrid images " +
                        "containing MBR and/or GPT partition tables. They are written bit-for-bit starting at LBA 0 " +
                        "and verified via full SCSI read-back SHA-256. Bootable on BIOS and/or UEFI PCs depending on the distro."
                )

                HorizontalDivider()

                GuideSection(
                    title = "4. Microsoft Windows 10 / 11 ISOs (UEFI Support)",
                    body = "Official Windows ISOs use optical UDF filesystems without MBR/GPT headers — writing a Windows " +
                        "ISO raw (dd-style) will NOT boot. Instead, this app parses UDF directly and:\n" +
                        "• If all files are <= 4 GiB: creates MBR + active FAT32 and extracts all installer files (UEFI bootable).\n" +
                        "• If sources/install.wim exceeds 4 GiB: creates a GPT Dual-Partition layout — Partition 1 (FAT32 EFI System Partition) " +
                        "boots Microsoft's signed UEFI bootloader + sources/boot.wim (Secure Boot compatible), and Partition 2 (exFAT) " +
                        "holds the full installer including sources/install.wim.\n" +
                        "• Important: Target PC must boot in UEFI mode (Legacy BIOS / CSM is not supported for Windows extraction)."
                )

                HorizontalDivider()

                GuideSection(
                    title = "5. Non-Hybrid Optical ISOs",
                    body = "Optical-only ISOs without isohybrid MBR/GPT headers or /efi/boot/ bootloaders are flagged " +
                        "explicitly as NOT guaranteed bootable on USB. This app never claims an ISO is bootable unless " +
                        "its actual boot requirements are satisfied."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE")
            }
        }
    )
}

@Composable
private fun GuideSection(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
