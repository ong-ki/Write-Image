package com.usbbootable.creator

import com.usbbootable.creator.core.image.ByteArrayImageSource
import com.usbbootable.creator.core.image.FileChannelImageSource
import com.usbbootable.creator.core.image.IsoImageAnalyzer
import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.OperationLogger
import com.usbbootable.creator.core.writer.BootableUsbOrchestrator
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.service.UsbWriteStateStore
import com.usbbootable.creator.ui.AppTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class PerformanceAndCancellationTest {

    @Test
    fun benchmarkBufferSizesOnRealFileBackedSequentialWrite() = runBlocking {
        val imageSizeBytes = 16 * 1024 * 1024 // 16 MiB benchmark payload
        val srcFile = File.createTempFile("usb_bench_src_", ".iso")
        val dstFile = File.createTempFile("usb_bench_dst_", ".img")

        try {
            // Generate realistic 16 MiB hybrid Linux image on disk
            val seedBlock = Random(2026).nextBytes(1024 * 1024)
            seedBlock[446] = 0x80.toByte()
            seedBlock[446 + 4] = 0x17
            ByteUtils.writeI32LE(seedBlock, 446 + 12, imageSizeBytes / 512)
            seedBlock[510] = 0x55
            seedBlock[511] = 0xAA.toByte()
            val pvd = 16 * 2048
            seedBlock[pvd] = 1
            ByteUtils.writeAscii(seedBlock, pvd + 1, "CD001", 5)

            FileOutputStream(srcFile).use { fos ->
                repeat(16) { fos.write(seedBlock) }
            }

            val bufferCandidates = listOf(
                64 * 1024,
                128 * 1024,
                256 * 1024,
                512 * 1024,
                1024 * 1024
            )
            val totalBlocks = (imageSizeBytes / 512L) + 128L
            val results = LinkedHashMap<Int, Double>()

            FileChannelImageSource(srcFile).use { source ->
                val isoInfo = IsoImageAnalyzer.analyze(source, "file://bench.iso", "bench.iso")
                val orchestrator = BootableUsbOrchestrator(OperationLogger())

                for (bufBytes in bufferCandidates) {
                    FileBackedBlockDevice(dstFile, totalBlocks = totalBlocks, blockSize = 512).use { dev ->
                        val startNs = System.nanoTime()
                        val res = orchestrator.execute(
                            source = source,
                            device = dev,
                            isoInfo = isoInfo,
                            targetDeviceName = "BenchDevice",
                            bufferSizeBytes = bufBytes,
                            verifyAfterWrite = true,
                            isCancelled = { false },
                            isDisconnected = { false },
                            onProgress = {}
                        )
                        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                        assertEquals(WritePhase.COMPLETED, res.phase)
                        assertTrue(res.verificationReport!!.verified)
                        // Write + Verify reads/writes 2 * imageSizeBytes sequentially
                        val mbPerSec = (imageSizeBytes.toDouble() / (1024.0 * 1024.0)) / elapsedSec
                        results[bufBytes] = mbPerSec
                        println(
                            "BENCHMARK buffer=${bufBytes / 1024}KiB | elapsed=${"%.3f".format(elapsedSec)}s | " +
                                "effectiveEndToEnd(Write+Verify)=${"%.1f".format(mbPerSec)} MB/s"
                        )
                    }
                }
            }

            // Confirm all buffer configurations completed cleanly with positive throughput
            for ((buf, speed) in results) {
                assertTrue("Expected positive throughput for ${buf / 1024}KiB", speed > 0.0)
            }
        } finally {
            srcFile.delete()
            dstFile.delete()
        }
    }

    @Test
    fun instantCancellationStopsPromptlyPreventsFurtherWritesAndUpdatesStateImmediately() = runBlocking {
        // 8 MiB synthetic image written to a simulated 20 MB/s USB drive (3 ms per 64 KiB block write)
        val imageSize = 8 * 1024 * 1024
        val img = ByteArray(imageSize)
        img[446] = 0x80.toByte()
        img[446 + 4] = 0x17
        ByteUtils.writeI32LE(img, 446 + 12, imageSize / 512)
        img[510] = 0x55
        img[511] = 0xAA.toByte()

        val source = ByteArrayImageSource(img)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/cancel.iso", "cancel.iso")
        val stateStore = UsbWriteStateStore()
        val writeCalls = AtomicInteger(0)
        val writesAfterCancel = AtomicInteger(0)
        val orchestrator = BootableUsbOrchestrator(OperationLogger())

        val slowDevice = object : BlockDevice {
            override val blockSize: Int = 512
            override val totalBlocks: Long = (imageSize / 512L) + 128L
            override val isReadOnly: Boolean = false

            override fun readBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) = Unit

            override fun writeBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
                if (stateStore.cancelRequested.get()) {
                    writesAfterCancel.incrementAndGet()
                    throw CancellationException("Blocked write after cancel")
                }
                writeCalls.incrementAndGet()
                Thread.sleep(4L) // Simulate realistic USB bulk write packet latency
            }

            override fun syncCache() = Unit
            override fun close() = Unit
        }

        var caughtCancellation = false
        val workerJob = launch(Dispatchers.IO) {
            try {
                orchestrator.execute(
                    source = source,
                    device = slowDevice,
                    isoInfo = isoInfo,
                    targetDeviceName = "Simulated USB",
                    bufferSizeBytes = 64 * 1024,
                    isCancelled = { stateStore.cancelRequested.get() },
                    isDisconnected = { false },
                    onProgress = { stateStore.updateProgress(it) }
                )
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }

        // Wait until a couple chunks have actually written
        while (writeCalls.get() < 3) {
            delay(5L)
        }

        // Trigger instant cancel just like the UI Cancel button does
        val cancelStartNs = System.nanoTime()
        stateStore.requestCancel()
        workerJob.cancel(CancellationException("User pressed Cancel"))

        // 1. UI state MUST reflect CANCELLED immediately (0 ms wait!)
        assertEquals(WritePhase.CANCELLED, stateStore.progress.value.phase)

        // 2. Worker coroutine must stop promptly without finishing the remaining ~8 MiB!
        workerJob.join()
        val stopLatencyMs = (System.nanoTime() - cancelStartNs) / 1_000_000L
        println("CANCEL_BENCHMARK stopLatency=${stopLatencyMs}ms | totalWriteCalls=${writeCalls.get()} | writesAfterCancel=${writesAfterCancel.get()}")

        assertTrue("Expected CancellationException propagation", caughtCancellation)
        assertEquals(WritePhase.CANCELLED, stateStore.progress.value.phase)
        assertTrue("Worker should stop within 250ms, took ${stopLatencyMs}ms", stopLatencyMs < 250L)
        assertTrue("Should abort far before all 128 chunks complete (actual=${writeCalls.get()})", writeCalls.get() < 40)
        assertEquals("Zero additional writes allowed after cancel", 0, writesAfterCancel.get())
    }

    @Test
    fun bottomNavigationContainsOnlyThreeTabsAndKeepScreenOnFollowsLifecycleStates() {
        // 1. Verify bottom tab bar has strictly Home, History, Settings
        val tabs = AppTab.entries
        assertEquals(3, tabs.size)
        assertEquals(listOf("Home", "History", "Settings"), tabs.map { it.label })

        // 2. Verify keep-screen-on is active throughout preparing, partitioning, writing, flushing, verifying
        assertTrue(MainActivity.shouldKeepScreenOn(WritePhase.PREPARING))
        assertTrue(MainActivity.shouldKeepScreenOn(WritePhase.PARTITIONING))
        assertTrue(MainActivity.shouldKeepScreenOn(WritePhase.WRITING))
        assertTrue(MainActivity.shouldKeepScreenOn(WritePhase.FLUSHING_CACHE))
        assertTrue(MainActivity.shouldKeepScreenOn(WritePhase.VERIFYING))

        // 3. Verify keep-screen-on is released on idle, completed, failed, and cancelled
        assertFalse(MainActivity.shouldKeepScreenOn(WritePhase.IDLE))
        assertFalse(MainActivity.shouldKeepScreenOn(WritePhase.COMPLETED))
        assertFalse(MainActivity.shouldKeepScreenOn(WritePhase.FAILED))
        assertFalse(MainActivity.shouldKeepScreenOn(WritePhase.CANCELLED))

        // 4. Verify stateStore transition from WRITING -> CANCELLED immediately releases keep-screen-on
        val store = UsbWriteStateStore()
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING))
        assertTrue(MainActivity.shouldKeepScreenOn(store.progress.value.phase))

        store.requestCancel()
        assertEquals(WritePhase.CANCELLED, store.progress.value.phase)
        assertFalse(MainActivity.shouldKeepScreenOn(store.progress.value.phase))
    }
}
