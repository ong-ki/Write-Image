package com.usbbootable.creator

import com.usbbootable.creator.core.filesystem.Fat32FileSystem
import com.usbbootable.creator.core.filesystem.MbrPartitioner
import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.usb.IntegrityResult
import com.usbbootable.creator.core.usb.PartitionBlockDevice
import com.usbbootable.creator.core.usb.UsbHealthChecker
import com.usbbootable.creator.core.usb.UsbHealthStatus
import com.usbbootable.creator.core.util.OperationLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class UsbHealthCheckerTest {

    private fun createFat32DriveWithUserFiles(
        tmpFile: File,
        totalBlocks: Long = 163_840L // 80 MiB
    ): Pair<FileBackedBlockDevice, Pair<Fat32FileSystem, List<com.usbbootable.creator.core.filesystem.WrittenFileAllocation>>> {
        val dev = FileBackedBlockDevice(tmpFile, totalBlocks = totalBlocks, blockSize = 512)
        val partRange = MbrPartitioner.writeSingleActivePartition(dev, MbrPartitioner.TYPE_FAT32_LBA)
        val partDev = PartitionBlockDevice(dev, partRange.startLba, partRange.sectorCount)
        val fat32 = Fat32FileSystem(partDev, hiddenSectors = partRange.startLba, volumeLabel = "USER_USB")
        val rng = Random(2026)
        val userDoc = rng.nextBytes(65_536)
        val userPhoto = rng.nextBytes(131_072)
        fat32.writeFile("docs/important.txt", userDoc.size.toLong(), { it(userDoc, userDoc.size) })
        fat32.writeFile("photos/vacation.jpg", userPhoto.size.toLong(), { it(userPhoto, userPhoto.size) })
        fat32.finalizeFileSystem()
        dev.syncCache()
        return dev to (fat32 to fat32.getWrittenFiles())
    }

    @Test
    fun healthyFat32DrivePassesAllTestsPreservesUserDataAndCleansTempFile() = runBlocking {
        val tmpDisk = File.createTempFile("usb_health_ok_", ".img")
        try {
            val (dev, fatInfo) = createFat32DriveWithUserFiles(tmpDisk)
            val (fat32, userRecords) = fatInfo
            dev.use {
                val checker = UsbHealthChecker(OperationLogger())
                val report = checker.runDiagnostics(
                    device = dev,
                    driveName = "Virtual FAT32 USB",
                    sampleSizeBytes = 512L * 1024L,
                    isCancelled = { false },
                    isDisconnected = { false },
                    onProgress = { _, _, _ -> }
                )

                assertEquals(UsbHealthStatus.HEALTHY, report.status)
                assertTrue(report.capacityVerified)
                assertEquals(dev.capacityBytes, report.reportedCapacityBytes)
                assertTrue(report.sequentialReadBytesPerSec > 0.0)
                assertNotNull(report.sequentialWriteBytesPerSec)
                assertTrue(report.sequentialWriteBytesPerSec!! > 0.0)
                assertEquals(IntegrityResult.PASSED, report.integrityResult)
                assertEquals(0, report.ioErrorCount)
                assertTrue(report.connectionStable)
                assertFalse(report.disconnectedMidTest)
                assertTrue(report.tempFileCleanedUp)

                // Every pre-existing user file must remain 100% byte-for-byte intact
                for (rec in userRecords) {
                    assertTrue("Existing user file ${rec.path} was altered!", fat32.verifyFile(rec))
                }

                // Temp filename must not remain in root directory
                val raw = String(tmpDisk.readBytes(), Charsets.ISO_8859_1)
                assertFalse("Temporary file entry should be cleaned up", raw.contains("USBHCHK TMP"))
            }
        } finally {
            tmpDisk.delete()
        }
    }

    @Test
    fun corruptedReadBackFailsSha256IntegrityAndStillCleansTempFile() = runBlocking {
        val tmpDisk = File.createTempFile("usb_health_corrupt_", ".img")
        try {
            val (baseDev, fatInfo) = createFat32DriveWithUserFiles(tmpDisk)
            val (fat32, userRecords) = fatInfo
            baseDev.use {
                val writeStarted = AtomicBoolean(false)
                val corruptingDev = object : BlockDevice by baseDev {
                    override fun writeBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
                        if (blockCount > 1) writeStarted.set(true)
                        baseDev.writeBlocks(startLba, blockCount, buffer, offset)
                    }

                    override fun readBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
                        baseDev.readBlocks(startLba, blockCount, buffer, offset)
                        // Flip one byte on multi-block read-back after payload write (simulates bad/fake flash)
                        if (writeStarted.get() && blockCount > 1) {
                            buffer[offset] = (buffer[offset].toInt() xor 0xFF).toByte()
                        }
                    }
                }

                val report = UsbHealthChecker(OperationLogger()).runDiagnostics(
                    device = corruptingDev,
                    driveName = "Corrupt Flash USB",
                    sampleSizeBytes = 512L * 1024L,
                    isCancelled = { false },
                    isDisconnected = { false },
                    onProgress = { _, _, _ -> }
                )

                assertEquals(UsbHealthStatus.FAILED, report.status)
                assertEquals(IntegrityResult.FAILED, report.integrityResult)
                assertTrue(report.tempFileCleanedUp)
                for (rec in userRecords) {
                    assertTrue(fat32.verifyFile(rec))
                }
            }
        } finally {
            tmpDisk.delete()
        }
    }

    @Test
    fun nonFat32DriveRunsSafeReadOnlyModeWithZeroWrites() = runBlocking {
        val dev = InMemoryBlockDevice(totalBlocks = 4096L, blockSize = 512)
        val rng = Random(99)
        rng.nextBytes(dev.data)
        val originalSnapshot = dev.data.copyOf()
        val writeCalls = AtomicInteger(0)

        val guardedDev = object : BlockDevice by dev {
            override fun writeBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
                writeCalls.incrementAndGet()
                dev.writeBlocks(startLba, blockCount, buffer, offset)
            }
        }

        val report = UsbHealthChecker(OperationLogger()).runDiagnostics(
            device = guardedDev,
            driveName = "Non-FAT32 USB",
            sampleSizeBytes = 512L * 1024L,
            isCancelled = { false },
            isDisconnected = { false },
            onProgress = { _, _, _ -> }
        )

        assertEquals("Non-FAT32 drive must never be written to in safe mode", 0, writeCalls.get())
        assertArrayEquals(originalSnapshot, dev.data)
        assertEquals(UsbHealthStatus.WARNING, report.status)
        assertTrue(report.capacityVerified)
        assertTrue(report.sequentialReadBytesPerSec > 0.0)
        assertNull(report.sequentialWriteBytesPerSec)
        assertEquals(IntegrityResult.SKIPPED_READ_ONLY, report.integrityResult)
        assertEquals(0, report.ioErrorCount)
    }

    @Test
    fun ioErrorOnBoundaryCapacityProbeIsDetectedAndReportedAsFailed() = runBlocking {
        val baseDev = InMemoryBlockDevice(totalBlocks = 4096L, blockSize = 512)
        val faultyDev = object : BlockDevice by baseDev {
            override fun readBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
                if (startLba >= totalBlocks - 1L) {
                    throw IOException("SCSI MEDIUM ERROR at final LBA")
                }
                baseDev.readBlocks(startLba, blockCount, buffer, offset)
            }
        }

        val report = UsbHealthChecker(OperationLogger()).runDiagnostics(
            device = faultyDev,
            driveName = "Faulty Capacity USB",
            sampleSizeBytes = 256L * 1024L,
            isCancelled = { false },
            isDisconnected = { false },
            onProgress = { _, _, _ -> }
        )

        assertEquals(UsbHealthStatus.FAILED, report.status)
        assertFalse(report.capacityVerified)
        assertTrue(report.ioErrorCount >= 1)
    }

    @Test
    fun usbDisconnectMidTestIsHandledSafelyWithoutCrash() = runBlocking {
        val dev = InMemoryBlockDevice(totalBlocks = 4096L, blockSize = 512)
        val callCount = AtomicInteger(0)

        val report = UsbHealthChecker(OperationLogger()).runDiagnostics(
            device = dev,
            driveName = "Disconnected USB",
            sampleSizeBytes = 256L * 1024L,
            isCancelled = { false },
            isDisconnected = { callCount.incrementAndGet() >= 4 },
            onProgress = { _, _, _ -> }
        )

        assertEquals(UsbHealthStatus.FAILED, report.status)
        assertTrue(report.disconnectedMidTest)
        assertFalse(report.connectionStable)
        assertTrue(report.ioErrorCount >= 1)
    }

    @Test
    fun cancellationStopsPromptlyAndCleansUpTempFile() = runBlocking {
        val tmpDisk = File.createTempFile("usb_health_cancel_", ".img")
        try {
            val (dev, fatInfo) = createFat32DriveWithUserFiles(tmpDisk)
            val (fat32, userRecords) = fatInfo
            dev.use {
                val cancelFlag = AtomicBoolean(false)
                var threwCancel = false
                try {
                    UsbHealthChecker(OperationLogger()).runDiagnostics(
                        device = dev,
                        driveName = "Cancelled USB",
                        sampleSizeBytes = 1024L * 1024L,
                        isCancelled = { cancelFlag.get() },
                        isDisconnected = { false },
                        onProgress = { frac, _, _ ->
                            if (frac >= 0.40f) cancelFlag.set(true)
                        }
                    )
                } catch (_: CancellationException) {
                    threwCancel = true
                }

                assertTrue("Expected CancellationException on cancel", threwCancel)
                for (rec in userRecords) {
                    assertTrue("Existing user file must remain intact after cancel", fat32.verifyFile(rec))
                }
                val raw = String(tmpDisk.readBytes(), Charsets.ISO_8859_1)
                assertFalse("Temporary file must be cleaned up on cancel", raw.contains("USBHCHK TMP"))
            }
        } finally {
            tmpDisk.delete()
        }
    }
}
