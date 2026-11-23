package com.usbbootable.creator

import com.usbbootable.creator.core.image.ByteArrayImageSource
import com.usbbootable.creator.core.image.IsoImageAnalyzer
import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.OperationLogger
import com.usbbootable.creator.core.writer.BootableUsbOrchestrator
import com.usbbootable.creator.core.writer.WritePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class RawImageWriterTest {

    private fun buildSyntheticLinuxHybridImage(sizeBytes: Int = 512 * 1024 + 123): ByteArray {
        val img = Random(42).nextBytes(sizeBytes)
        img[446] = 0x80.toByte()
        img[446 + 4] = 0x17
        ByteUtils.writeI32LE(img, 446 + 12, sizeBytes / 512)
        img[510] = 0x55
        img[511] = 0xAA.toByte()
        val pvd = 16 * 2048
        img[pvd] = 1
        ByteUtils.writeAscii(img, pvd + 1, "CD001", 5)
        ByteUtils.writeAscii(img, pvd + 40, "FEDORA_LIVE", 32)
        return img
    }

    @Test
    fun rawWriteAndSha256VerificationSucceedsEndToEnd() = runBlocking {
        val img = buildSyntheticLinuxHybridImage()
        val source = ByteArrayImageSource(img)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/fedora.iso", "fedora.iso")

        val dev = InMemoryBlockDevice(totalBlocks = 4096, blockSize = 512)
        val orchestrator = BootableUsbOrchestrator(OperationLogger())

        val progressEvents = mutableListOf<com.usbbootable.creator.core.writer.WriteProgress>()
        val result = orchestrator.execute(
            source = source,
            device = dev,
            isoInfo = isoInfo,
            targetDeviceName = "Test USB",
            isCancelled = { false },
            isDisconnected = { false },
            onProgress = { progressEvents.add(it) }
        )

        val phases = progressEvents.map { it.phase }
        assertEquals(WritePhase.COMPLETED, result.phase)
        assertEquals(img.size.toLong(), result.bytesWritten)
        val report = result.verificationReport
        assertNotNull(report)
        assertTrue(report!!.verified)
        assertEquals(img.size.toLong(), report.bytesVerified)
        assertEquals(report.sourceSha256, report.driveSha256)
        assertTrue(dev.syncCount >= 1)
        assertTrue(phases.contains(WritePhase.WRITING))
        assertTrue(phases.contains(WritePhase.VERIFYING))

        // Verify confirmed bytesWritten is monotonically non-decreasing and stays at full size during VERIFYING
        var maxSeenWritten = 0L
        for (event in progressEvents) {
            assertTrue("bytesWritten must be monotonic", event.bytesWritten >= maxSeenWritten)
            maxSeenWritten = event.bytesWritten
            if (event.phase == WritePhase.VERIFYING) {
                assertEquals(img.size.toLong(), event.bytesWritten)
            }
        }
    }

    @Test
    fun rawVerificationDetectsCorruptedMedia() = runBlocking {
        val img = buildSyntheticLinuxHybridImage(sizeBytes = 300 * 1024)
        val source = ByteArrayImageSource(img)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/fedora.iso", "fedora.iso")

        val backing = InMemoryBlockDevice(totalBlocks = 2048, blockSize = 512)
        val corruptingDevice = object : BlockDevice by backing {
            override fun syncCache() {
                backing.syncCache()
                backing.data[100_000] = (backing.data[100_000].toInt() xor 0xFF).toByte()
            }
        }

        val orchestrator = BootableUsbOrchestrator(OperationLogger())
        val result = orchestrator.execute(
            source = source,
            device = corruptingDevice,
            isoInfo = isoInfo,
            targetDeviceName = "Faulty USB",
            isCancelled = { false },
            isDisconnected = { false },
            onProgress = {}
        )

        assertEquals(WritePhase.FAILED, result.phase)
        val report = result.verificationReport
        assertNotNull(report)
        assertFalse(report!!.verified)
    }

    @Test
    fun safeCancellationPropagatesCancellationExceptionWithoutSwallowing() = runBlocking {
        val img = buildSyntheticLinuxHybridImage(sizeBytes = 2 * 1024 * 1024)
        val source = ByteArrayImageSource(img)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/fedora.iso", "fedora.iso")
        val dev = InMemoryBlockDevice(totalBlocks = 8192, blockSize = 512)

        val cancelFlag = AtomicBoolean(false)
        val seenPhases = mutableListOf<WritePhase>()
        val orchestrator = BootableUsbOrchestrator(OperationLogger())

        try {
            orchestrator.execute(
                source = source,
                device = dev,
                isoInfo = isoInfo,
                targetDeviceName = "Test USB",
                bufferSizeBytes = 64 * 1024,
                isCancelled = { cancelFlag.get() },
                isDisconnected = { false },
                onProgress = { prog ->
                    seenPhases.add(prog.phase)
                    if (prog.phase == WritePhase.WRITING) {
                        cancelFlag.set(true)
                    }
                }
            )
            fail("Expected CancellationException to propagate without being swallowed")
        } catch (_: CancellationException) {
            // Expected — coroutine cancellation must NOT be swallowed
        }

        assertEquals(WritePhase.CANCELLED, seenPhases.last())
    }
}
