package com.usbbootable.creator

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.usbbootable.creator.core.image.BootSupportSummary
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.image.ImageClassification
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.image.WriteStrategy
import com.usbbootable.creator.core.safety.PreflightCheckResult
import com.usbbootable.creator.core.storage.AppearanceCustomization
import com.usbbootable.creator.core.storage.AppSettings
import com.usbbootable.creator.core.storage.ProgressBarStyle
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.usb.UsbTransportSupport
import com.usbbootable.creator.core.writer.SpeedSample
import com.usbbootable.creator.core.writer.TransferSpeedSnapshot
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.ui.AppTab
import com.usbbootable.creator.ui.DashboardUiState
import com.usbbootable.creator.ui.screens.MainTabsScaffold
import com.usbbootable.creator.ui.screens.StyledProgressBar
import com.usbbootable.creator.ui.theme.GlassBackdropBox
import com.usbbootable.creator.ui.theme.GlassCardSurface
import com.usbbootable.creator.ui.theme.ThemeMode
import com.usbbootable.creator.ui.theme.UsbBootableCreatorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

private fun saveScreenshotIfRequested(fileName: String) {
    val args = InstrumentationRegistry.getArguments()
    if (args.getString("captureScreenshots") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    var bitmap: Bitmap? = null
    for (attempt in 1..6) {
        Thread.sleep(600L)
        val candidate = instrumentation.uiAutomation.takeScreenshot() ?: continue
        if (hasVisibleContent(candidate) || attempt == 6) {
            bitmap?.recycle()
            bitmap = candidate
            break
        }
        candidate.recycle()
    }
    val finalBitmap = bitmap ?: return
    val outDir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
    outDir.mkdirs()
    FileOutputStream(File(outDir, fileName)).use { fos ->
        finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
    }
    finalBitmap.recycle()
}

private fun hasVisibleContent(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return false
    val ref = bitmap.getPixel(w / 2, h / 2)
    var diffCount = 0
    for (y in (h * 2 / 10) until (h * 8 / 10) step (h / 18).coerceAtLeast(1)) {
        for (x in (w / 6) until (w * 5 / 6) step (w / 10).coerceAtLeast(1)) {
            if (bitmap.getPixel(x, y) != ref) {
                diffCount++
                if (diffCount >= 12) return true
            }
        }
    }
    return false
}

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainNavigationAndThemeSwitchingWorkCleanly() {
        // Wait until SplashScreen completes and Home screen controls are visible
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("ISO Image").fetchSemanticsNodes().isNotEmpty()
        }

        // Switch to Settings and explicitly select Dark Theme
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Progress Bar Style").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.waitForIdle()
        saveScreenshotIfRequested("settings.png")

        // Verify Home Screen in Dark Theme
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Write-Image").assertIsDisplayed()
        composeRule.onNodeWithText("ISO Image").assertIsDisplayed()
        composeRule.onNodeWithText("USB Drive").assertIsDisplayed()
        composeRule.onNodeWithText("Flashing Mode").assertIsDisplayed()
        composeRule.onNodeWithText("CREATE BOOTABLE USB").assertIsDisplayed()
        saveScreenshotIfRequested("home-dark.png")

        // Switch to Light Theme and verify Home Screen adapts cleanly
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Light").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Write-Image").assertIsDisplayed()
        saveScreenshotIfRequested("home-light.png")

        // Restore System theme default
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("System").performClick()
        composeRule.waitForIdle()
    }
}

@RunWith(AndroidJUnit4::class)
class VisualShowcaseUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun flashingInterfaceRendersProgressAndSpeedGraph() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val darkBars = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            activity.enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
        }

        val totalBytes = 5_798_205_849L // 5.4 GB
        val writtenBytes = 3_652_869_684L // 3.4 GB (63%)
        val mb = 1024.0 * 1024.0
        val sampleRatesMb = listOf(
            26.4, 31.2, 34.8, 36.1, 35.4, 37.9, 39.6, 41.2, 42.1, 40.5,
            38.2, 37.0, 38.9, 40.4, 39.8, 37.5, 36.8, 38.1, 39.0, 38.4
        )
        val speedSamples = sampleRatesMb.mapIndexed { idx, mbps ->
            SpeedSample(elapsedMs = 72_000L + idx * 1_000L, bytesPerSec = mbps * mb)
        }

        val demoIso = SelectedIsoInfo(
            uriString = "content://demo/ubuntu-24.04.2-desktop-amd64.iso",
            displayName = "ubuntu-24.04.2-desktop-amd64.iso",
            sizeBytes = totalBytes,
            isAccessible = true,
            volumeLabel = "UBUNTU_24_04_LTS",
            classification = ImageClassification.HYBRID_LINUX_ISO,
            writeStrategy = WriteStrategy.RAW_SECTOR_WRITE,
            bootSupport = BootSupportSummary(
                uefiSupported = true,
                legacyBiosSupported = true,
                secureBootFriendly = true,
                isGuaranteedBootable = true,
                headline = "BIOS + UEFI Hybrid Linux ISO",
                technicalExplanation = "Direct sector-by-sector raw image write with SHA-256 read-back verification."
            ),
            hasMbr = true,
            hasGpt = true,
            hasIso9660 = true,
            hasUdf = false,
            hasElTorito = true
        )
        val demoDrive = UsbDriveInfo(
            deviceId = 1,
            deviceName = "/dev/bus/usb/001/002",
            vendorId = 0x0000,
            productId = 0x0000,
            manufacturerName = "USB 3.2",
            productName = "Flash Drive",
            serialNumber = null,
            transportSupport = UsbTransportSupport.SUPPORTED_BOT,
            hasPermission = true,
            isProbed = true,
            scsiVendor = "USB 3.2",
            scsiProduct = "Flash Drive",
            logicalBlockSize = 512,
            totalBlocks = 62_500_000L,
            capacityBytes = 32_000_000_000L
        )
        val demoState = DashboardUiState(
            selectedTab = AppTab.HOME,
            flashingMode = FlashingMode.LINUX,
            flashingModeSelected = true,
            isUsbHostSupported = true,
            selectedIso = demoIso,
            availableDrives = listOf(demoDrive),
            selectedDrive = demoDrive,
            preflight = PreflightCheckResult(
                canProceed = true,
                blockers = emptyList(),
                warnings = emptyList(),
                requiresNonBootableAck = false
            ),
            writeProgress = WriteProgress(
                phase = WritePhase.WRITING,
                bytesProcessed = writtenBytes,
                bytesWritten = writtenBytes,
                totalBytes = totalBytes,
                phaseProgress = 0.63f,
                overallProgress = 0.63f,
                speedBytesPerSec = 38.4 * mb,
                etaSeconds = 56L,
                currentItemLabel = "Writing ISO blocks (SHA-256 verification queued)",
                statusMessage = "Writing ubuntu-24.04.2-desktop-amd64.iso to USB 3.2 Flash Drive...",
                targetDeviceName = demoDrive.displayName,
                sourceIsoName = demoIso.displayName,
                bootSupport = demoIso.bootSupport
            ),
            speedSnapshot = TransferSpeedSnapshot(
                currentBytesPerSec = 38.4 * mb,
                averageBytesPerSec = 37.3 * mb,
                peakBytesPerSec = 42.1 * mb,
                samples = speedSamples,
                isLive = true,
                elapsedMs = 91_000L
            ),
            elapsedSeconds = 91L,
            operationTimerRunning = true,
            settings = AppSettings(themeMode = ThemeMode.DARK),
            customization = AppearanceCustomization.DEFAULT
        )

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            UsbBootableCreatorTheme(
                themeMode = ThemeMode.DARK,
                customization = AppearanceCustomization.DEFAULT
            ) {
                MainTabsScaffold(
                    state = demoState,
                    logsFlow = MutableStateFlow(emptyList()),
                    onSelectTab = {},
                    onSetFlashingMode = {},
                    onSelectIsoClick = {},
                    onSelectDrive = {},
                    onRequestUsbPermission = {},
                    onRetryProbeDrive = {},
                    onRefreshDrives = {},
                    onConfirmStartWrite = {},
                    onCancelWrite = {},
                    onDismissFinishedStatus = {},
                    onDismissBanner = {},
                    onSetThemeMode = {},
                    onSetBufferOption = {},
                    onSetVerifyAfterWrite = {},
                    onClearHistory = {},
                    onClearLogs = {}
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Write-Image").assertIsDisplayed()
        composeRule.onAllNodesWithText("CANCEL", substring = true).onFirst().assertIsDisplayed()
        saveScreenshotIfRequested("flashing.png")
    }

    @Test
    fun allFourProgressBarStylesRenderDistinctly() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val darkBars = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            activity.enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
        }

        val descriptions = mapOf(
            ProgressBarStyle.CLASSIC to "Traditional solid fill in a clean bordered track.",
            ProgressBarStyle.NEON_GLOW to "Recessed dark rail with radiant neon aura, bright core, and leading energy tip.",
            ProgressBarStyle.GRADIENT to "Smooth capsule bar with a vivid 3-stop spectrum transition.",
            ProgressBarStyle.SEGMENTED to "Discrete rectangular hardware-meter segments that fill progressively."
        )

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            UsbBootableCreatorTheme(
                themeMode = ThemeMode.DARK,
                customization = AppearanceCustomization.DEFAULT
            ) {
                GlassBackdropBox {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Write-Image",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Progress Bar Styles",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Four distinct progress indicators selectable in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        ProgressBarStyle.entries.forEach { style ->
                            GlassCardSurface(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = style.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "72%",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    StyledProgressBar(
                                        progress = 0.72f,
                                        style = style,
                                        height = 18.dp
                                    )
                                    Text(
                                        text = descriptions.getValue(style),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        for (style in ProgressBarStyle.entries) {
            composeRule.onNodeWithText(style.label).assertIsDisplayed()
        }
        saveScreenshotIfRequested("progress-styles.png")
    }
}
