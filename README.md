# USB Bootable Creator

A native Android application written in **Kotlin** and **Jetpack Compose (Material 3)** that creates real, verified bootable USB flash drives directly from an Android phone using **USB OTG**, Android's **USB Host API**, and the **Storage Access Framework (SAF)** — with **no root required** for standard USB flash drives.

---

## Architecture Overview

```
app/src/main/java/com/usbbootable/creator/
├── core/
│   ├── usb/          # Android USB Host API -> Bulk-Only Transport (BOT 1.0) -> SCSI BlockDevice
│   ├── image/        # SAF 64-bit ImageSource, ECMA-167 UDF 1.02–2.01 Reader, ISO-9660/Joliet Reader, Analyzer
│   ├── filesystem/   # MBR Partitioner, UEFI GPT Partitioner (CRC32), FAT32 + VFAT LFN Writer, exFAT Writer
│   ├── safety/       # Pre-flight validation (capacity, write-protect, protocol, bootability)
│   └── writer/       # RawImageWriter, WindowsUefiWriter, SpeedTracker (MB/s & ETA), Orchestrator
├── service/          # Foreground Service (dataSync + WakeLock + cancelable progress notification)
└── ui/               # Material 3 Light/Dark Dashboard, Drive Picker, Confirm Dialog, Logs, Tech Guide
```

### How Real USB Writing Works Without Root

Standard Android blocks direct `/dev/block/*` access via Linux DAC permissions and SELinux. Rather than simulating writes or requiring root privileges:

1. **USB Mass Storage Discovery (`UsbDeviceMonitor`)**: Enumerates external USB OTG devices via `UsbManager` matching USB Mass Storage Class (`0x08`), SCSI Transparent (`0x06`) / SFF-8070i (`0x05`), Bulk-Only Transport (`0x50`).
2. **Exclusive Claim (`UsbDeviceSession`)**: Requests runtime USB Host permission, calls `claimInterface(intf, force = true)` to detach the kernel `usb-storage` driver cleanly, and opens Bulk IN/OUT pipes.
3. **USB BOT 1.0 + SCSI Block Engine (`UsbBotTransport`, `ScsiBlockDevice`)**:
   - Sends 31-byte Command Block Wrappers (CBW), transfers data in USBFS-safe chunks (`<= 16 KiB` on Android 8.0–8.1 API 26–27 to avoid `MAX_USBFS_BUFFER_SIZE` truncation; `64 KiB` on Android 9+), and validates 13-byte Command Status Wrappers (CSW) with automatic `CLEAR_FEATURE(ENDPOINT_HALT)` and BOT Reset Recovery.
   - Executes real SCSI commands: `INQUIRY`, `TEST UNIT READY`, `REQUEST SENSE`, `MODE SENSE (6)` (write-protect detection), `READ CAPACITY (10/16)`, `READ (10/16)`, `WRITE (10/16)`, and `SYNCHRONIZE CACHE (10)`.
4. **Internal Storage Protection**: Because `UsbManager` strictly enumerates external USB OTG Host peripherals, the app cannot access or overwrite Android's internal UFS/eMMC storage.

### Android USB Host API Limitations & Root Notes

- **Root is NOT required** for standard USB OTG flash drives using Bulk-Only Transport (BOT).
- **USB OTG Hardware Required**: The phone must support USB Host mode (`FEATURE_USB_HOST`). Some OEMs (Oppo, Realme, OnePlus, Vivo) require enabling **OTG Connection** in Android Settings.
- **Auto-Mount (`vold`) Contention**: On certain OEM Android builds, Android's storage daemon may lock a mounted USB drive. If claiming the interface fails, open **Android Settings → Storage**, unmount/eject the USB drive (leaving it plugged in), and tap **Retry SCSI Probe**.
- **UASP-Only Enclosures**: High-speed USB 3.x NVMe/SSD enclosures that expose only UASP (`protocol 0x62`) without a BOT (`0x50`) fallback interface cannot be driven via user-space BOT without kernel/root support. The app detects and reports this clearly.

---

## Windows vs. Linux ISO Compatibility

| Image Type | Detection | Write Strategy | Boot Compatibility |
| :--- | :--- | :--- | :--- |
| **Linux Hybrid ISO** (Ubuntu, Fedora, Debian, Arch, Mint, Kali) | ISO-9660 + MBR and/or GPT (`isohybrid`) | Direct Raw Sector Write (LBA 0..N) + full SCSI read-back SHA-256 | BIOS and/or UEFI (matches distro ISO) |
| **Raw Disk Image** (`.img`, Raspberry Pi OS, FreeBSD, OpenWrt) | Valid MBR and/or GPT partition table | Direct Raw Sector Write (LBA 0..N) + full SCSI read-back SHA-256 | Matches original disk image |
| **Windows ISO (all files <= 4 GiB)** (e.g., ESD-compressed Windows 10/11, WinPE) | UDF / ISO-9660 with `/efi/boot/boot*.efi` + `sources/boot.wim` | MBR + single active **FAT32** partition + UDF extraction + per-file SHA-256 read-back | **UEFI Only** (Secure Boot compatible; Legacy BIOS not supported) |
| **Windows 10/11 ISO (`install.wim` > 4 GiB)** | UDF / ISO-9660 with file > `4,294,967,295` bytes | **GPT Dual-Partition**: Partition 1 = **FAT32 EFI System Partition** (`efi/`, `boot/`, `sources/boot.wim`), Partition 2 = **exFAT Basic Data** (full installer + `sources/install.wim`) + full SHA-256 read-back | **UEFI Only** (Windows 10 1703+ / Windows 11, Secure Boot compatible; Legacy BIOS not supported) |
| **Non-Hybrid Optical ISO** | ISO-9660/UDF without MBR/GPT and without `/efi/boot/` | Raw write only with explicit user acknowledgment | **NOT guaranteed bootable** — explicitly warned |

---

## Building From Source

### Requirements
- Android Studio (Meerkat / Narwhal or newer) or JDK 17–21 + Android SDK Platform 36
- Minimum Android version: **Android 8.0 (API 26)**

### Command Line Build
```bash
# Run unit & filesystem integrity tests (includes fsck.fat and parted validation on Linux)
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

APKs are output to:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

---

## Installation Instructions

### Option A: Install via ADB (USB Debugging)
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Option B: Install Directly on Your Android Phone
1. Copy `app-debug.apk` (or `app-release.apk`) to your Android phone's **Downloads** folder.
2. Open your phone's **Files** app and tap the APK file.
3. If prompted, allow **Install unknown apps** for your Files app.
4. Tap **Install**, then open **USB Bootable Creator**.
5. Connect a USB flash drive via a USB OTG adapter, tap **SELECT ISO**, tap **SELECT USB DRIVE** (grant USB permission when prompted), and tap **CREATE BOOTABLE USB**.
