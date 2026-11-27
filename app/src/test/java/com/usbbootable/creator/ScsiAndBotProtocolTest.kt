package com.usbbootable.creator

import com.usbbootable.creator.core.usb.BotDirection
import com.usbbootable.creator.core.usb.ScsiBlockDevice
import com.usbbootable.creator.core.usb.ScsiCommandSet
import com.usbbootable.creator.core.usb.UsbBotTransport
import com.usbbootable.creator.core.usb.UsbBulkIo
import com.usbbootable.creator.core.usb.UsbDisconnectedException
import com.usbbootable.creator.core.usb.UsbStorageException
import com.usbbootable.creator.core.usb.UsbWriteProtectedException
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.OperationLogger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

class ScsiAndBotProtocolTest {

    @Test
    fun cbwAndCswEncodingMatchesUsbBotSpec() {
        val cbw = ByteArray(UsbBotTransport.CBW_SIZE)
        val cdb = ScsiCommandSet.read10(lba = 0x00010203L, blockCount = 16)
        UsbBotTransport.encodeCbw(
            out = cbw,
            tag = 42,
            dataTransferLength = 8192,
            direction = BotDirection.IN,
            lun = 0,
            cdb = cdb
        )

        assertEquals(UsbBotTransport.CBW_SIGNATURE, ByteUtils.readI32LE(cbw, 0))
        assertEquals(42, ByteUtils.readI32LE(cbw, 4))
        assertEquals(8192, ByteUtils.readI32LE(cbw, 8))
        assertEquals(0x80.toByte(), cbw[12])
        assertEquals(0.toByte(), cbw[13])
        assertEquals(10.toByte(), cbw[14])
        assertEquals(ScsiCommandSet.OP_READ_10, cbw[15])
        assertEquals(0x00010203, ByteUtils.readI32BE(cbw, 17))
        assertEquals(16, ByteUtils.readU16BE(cbw, 22))
    }

    @Test
    fun scsiBlockDeviceEndToEndOverMockBotUsb() {
        val mockUsb = VirtualUsbScsiFlashDrive(totalBlocks = 512, blockSize = 512, unitAttentionOnce = true)
        val transport = UsbBotTransport(io = mockUsb, sleepMs = {})
        assertEquals(0, transport.getMaxLun())

        val dev = ScsiBlockDevice(transport = transport, lun = 0, sleepMs = {})
        dev.initialize()

        assertEquals("SanDisk", dev.inquiry.vendor)
        assertEquals("Ultra USB 3.0", dev.inquiry.product)
        assertEquals(512, dev.blockSize)
        assertEquals(512L, dev.totalBlocks)
        assertFalse(dev.isReadOnly)

        val payload = Random(12345).nextBytes(128 * 512) // 64 KiB
        dev.writeBlocks(startLba = 10L, blockCount = 128, buffer = payload)
        dev.syncCache()

        val readBack = ByteArray(128 * 512)
        dev.readBlocks(startLba = 10L, blockCount = 128, buffer = readBack)
        assertArrayEquals(payload, readBack)
        assertTrue("Expected bounded Android USBFS transfer calls", mockUsb.maxObservedBulkChunk <= 16_384)
    }

    @Test
    fun recoversFromCswRead1FailureWithFullBotResetReinitAndAdaptiveStepDown() {
        // Reproduces the exact reported failure:
        // During a 64 KiB (128-block) WRITE(10), the device stalls/returns 1 byte on CSW read
        // even after Bulk-IN halt clear ("read=1").
        // Expected recovery sequence:
        // 1. UsbBotTransport performs Mass Storage Reset (0xFF) + Clear Halt IN (0x81) + Clear Halt OUT (0x02)
        // 2. ScsiBlockDevice steps down activeMaxTransferBytes from 64 KiB to 32 KiB
        // 3. ScsiBlockDevice polls post-reset TEST UNIT READY + REQUEST SENSE to clear UNIT ATTENTION (0x06/0x29)
        // 4. ScsiBlockDevice safely retries the 64 KiB write as two 32 KiB (64-block) WRITE(10) commands
        // 5. Written data matches source SHA-256 100%!
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 512,
            blockSize = 512,
            failCswWithRead1OnFirst64KWrite = true
        )
        val transport = UsbBotTransport(io = mockUsb, sleepMs = {})
        val dev = ScsiBlockDevice(
            transport = transport,
            lun = 0,
            maxTransferBytes = 64 * 1024,
            sleepMs = {}
        )
        dev.initialize()

        val payload = Random(98765).nextBytes(128 * 512) // 64 KiB
        val expectedSha256 = ByteUtils.toHex(MessageDigest.getInstance("SHA-256").digest(payload))

        dev.writeBlocks(startLba = 20L, blockCount = 128, buffer = payload)

        // Verify full BOT Reset Recovery sequence was executed
        assertEquals("Expected 1 BOT Mass Storage Reset (0xFF)", 1, mockUsb.massStorageResetCount)
        assertTrue("Expected Bulk-IN halt clear", mockUsb.clearHaltInCount >= 2) // 1 during CSW retry + 1 during Reset Recovery
        assertTrue("Expected Bulk-OUT halt clear", mockUsb.clearHaltOutCount >= 1)
        assertEquals("Expected adaptive step-down from 64 KiB to 32 KiB", 32 * 1024, dev.activeMaxTransferBytes)

        // Verify SHA-256 of written sectors on drive matches source payload
        val readBack = ByteArray(128 * 512)
        dev.readBlocks(startLba = 20L, blockCount = 128, buffer = readBack)
        val actualSha256 = ByteUtils.toHex(MessageDigest.getInstance("SHA-256").digest(readBack))
        assertEquals(expectedSha256, actualSha256)
    }

    @Test
    fun handlesPartialBulkOutSlicesWithoutPrematureCswRead() {
        // Simulates a kernel/host controller that accepts at most 4096 bytes per bulkOut call
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 256,
            blockSize = 512,
            maxAcceptedBulkOutSlice = 4096
        )
        val transport = UsbBotTransport(io = mockUsb, sleepMs = {})
        val dev = ScsiBlockDevice(transport = transport, lun = 0, sleepMs = {})
        dev.initialize()

        val payload = Random(555).nextBytes(64 * 512) // 32 KiB
        dev.writeBlocks(startLba = 0L, blockCount = 64, buffer = payload)

        val readBack = ByteArray(64 * 512)
        dev.readBlocks(startLba = 0L, blockCount = 64, buffer = readBack)
        assertArrayEquals(payload, readBack)
        assertEquals(0, mockUsb.massStorageResetCount)
    }

    @Test
    fun drains1ByteStrayResidualPacketBeforeValid13ByteCsw() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 256,
            blockSize = 512,
            injectStray1ByteBeforeCswOnce = true
        )
        val transport = UsbBotTransport(io = mockUsb, sleepMs = {})
        val dev = ScsiBlockDevice(transport = transport, lun = 0, sleepMs = {})
        dev.initialize()

        val payload = Random(333).nextBytes(16 * 512)
        dev.writeBlocks(startLba = 4L, blockCount = 16, buffer = payload)

        val readBack = ByteArray(16 * 512)
        dev.readBlocks(startLba = 4L, blockCount = 16, buffer = readBack)
        assertArrayEquals(payload, readBack)
        assertEquals(0, mockUsb.massStorageResetCount)
    }

    @Test
    fun abortsWriteWithDetailedDiagnosticMessageWhenPostResetReinitFails() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 256,
            blockSize = 512,
            permanentCswFailureOnWrites = true
        )
        val transport = UsbBotTransport(io = mockUsb, sleepMs = {})
        val dev = ScsiBlockDevice(transport = transport, lun = 0, sleepMs = {})
        dev.initialize()

        try {
            dev.writeBlocks(startLba = 8L, blockCount = 16, buffer = ByteArray(16 * 512))
            fail("Expected UsbStorageException when post-reset reinit fails")
        } catch (e: UsbStorageException) {
            val msg = e.message ?: ""
            // Verify Requirement 5 error fields: command, endpoints, transfer length, error code, recovery status
            assertTrue("Missing command info in: $msg", msg.contains("WRITE"))
            assertTrue("Missing endpoint info in: $msg", msg.contains("IN=0x81") && msg.contains("OUT=0x02"))
            assertTrue("Missing byte length in: $msg", msg.contains("8192"))
            assertTrue("Missing recovery status in: $msg", msg.contains("BOT Reset Recovery"))
            // Must abort after first failed post-reset reinit rather than blindly writing 3 times
            assertEquals(1, mockUsb.writeCdbCount)
        }
    }

    @Test
    fun scsiBlockDeviceDetectsHardwareWriteProtect() {
        val mockUsb = VirtualUsbScsiFlashDrive(totalBlocks = 64, blockSize = 512, writeProtected = true)
        val dev = ScsiBlockDevice(transport = UsbBotTransport(io = mockUsb, sleepMs = {}), sleepMs = {})
        dev.initialize()
        assertTrue(dev.isReadOnly)

        try {
            dev.writeBlocks(0L, 1, ByteArray(512))
            fail("Expected UsbWriteProtectedException")
        } catch (_: UsbWriteProtectedException) {
            // Expected
        }
    }

    @Test
    fun scsiBlockDeviceAbortsOnDisconnect() {
        val mockUsb = VirtualUsbScsiFlashDrive(totalBlocks = 64, blockSize = 512)
        val dev = ScsiBlockDevice(transport = UsbBotTransport(io = mockUsb, sleepMs = {}), sleepMs = {})
        dev.initialize()

        mockUsb.connected = false
        try {
            dev.writeBlocks(0L, 1, ByteArray(512))
            fail("Expected UsbDisconnectedException")
        } catch (_: UsbDisconnectedException) {
            // Expected
        }
    }

    @Test
    fun syncCachePropagatesTransportFailureInsteadOfReportingSuccess() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 64,
            blockSize = 512,
            failSyncCacheWithIoErrorOnce = true
        )
        val dev = ScsiBlockDevice(
            transport = UsbBotTransport(io = mockUsb, sleepMs = {}),
            sleepMs = {}
        )
        dev.initialize()

        try {
            dev.syncCache()
            fail("Expected UsbStorageException when the cache flush transport fails")
        } catch (e: UsbStorageException) {
            assertTrue(
                "Expected a cache-flush diagnostic, got: ${e.message}",
                e.message?.contains("flush", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun startStopUnitCdbEncodingMatchesScsiSpec() {
        val ejectCdb = ScsiCommandSet.startStopUnit(start = false, loadEject = true)
        assertEquals(6, ejectCdb.size)
        assertEquals(ScsiCommandSet.OP_START_STOP_UNIT, ejectCdb[0])
        assertEquals(0x00.toByte(), ejectCdb[1])
        assertEquals(0x02.toByte(), ejectCdb[4])

        val stopCdb = ScsiCommandSet.startStopUnit(start = false, loadEject = false)
        assertEquals(6, stopCdb.size)
        assertEquals(ScsiCommandSet.OP_START_STOP_UNIT, stopCdb[0])
        assertEquals(0x00.toByte(), stopCdb[4])
    }

    @Test
    fun ejectUnitSendsSynchronizeCacheThenTestUnitReadyThenStartStopUnit() {
        val mockUsb = VirtualUsbScsiFlashDrive(totalBlocks = 64, blockSize = 512)
        val dev = ScsiBlockDevice(transport = UsbBotTransport(io = mockUsb, sleepMs = {}), sleepMs = {})
        dev.initialize()
        mockUsb.ejectOpcodeTrace.clear()

        val result = dev.ejectUnit()
        assertTrue(result.syncCacheSucceeded)
        assertTrue(result.testUnitReadySucceeded)
        assertTrue(result.startStopUnitSucceeded)
        assertFalse(result.allCommandsFailed)
        assertEquals(1, mockUsb.syncCacheCount)
        assertEquals(1, mockUsb.startStopUnitCount)
        assertEquals(listOf(0x35, 0x00, 0x1B), mockUsb.ejectOpcodeTrace)
        assertEquals(listOf(0x02), mockUsb.startStopUnitFlags)
    }

    @Test
    fun ejectUnitToleratesUnsupportedStartStopUnitAndLogsDiagnostics() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 64,
            blockSize = 512,
            startStopUnitIllegalOpcode = true
        )
        val logger = OperationLogger()
        val dev = ScsiBlockDevice(
            transport = UsbBotTransport(io = mockUsb, logger = logger, sleepMs = {}),
            logger = logger,
            sleepMs = {}
        )
        dev.initialize()
        mockUsb.ejectOpcodeTrace.clear()

        val result = dev.ejectUnit()
        assertTrue(result.syncCacheSucceeded)
        assertTrue(result.testUnitReadySucceeded)
        assertFalse(result.startStopUnitSucceeded)
        assertTrue(result.anyCommandSucceeded)
        assertFalse(result.allCommandsFailed)
        assertEquals(1, mockUsb.syncCacheCount)
        assertEquals(1, mockUsb.startStopUnitCount)
        assertEquals(listOf(0x02), mockUsb.startStopUnitFlags)

        val logDump = logger.entries.value.joinToString("\n") { it.message }
        assertTrue("Expected opcode 0x1B in log: $logDump", logDump.contains("0x1B"))
        assertTrue("Expected senseKey=0x05 in log: $logDump", logDump.contains("senseKey=0x05"))
        assertTrue("Expected ASC=0x20 in log: $logDump", logDump.contains("ASC=0x20"))
    }

    @Test
    fun ejectUnitTriesStartStopUnitOnceWithLoej1AndStart0WithoutFallback() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 64,
            blockSize = 512,
            startStopUnitLoejUnsupportedOnly = true
        )
        val dev = ScsiBlockDevice(transport = UsbBotTransport(io = mockUsb, sleepMs = {}), sleepMs = {})
        dev.initialize()
        mockUsb.ejectOpcodeTrace.clear()

        val result = dev.ejectUnit()
        assertTrue(result.syncCacheSucceeded)
        assertTrue(result.testUnitReadySucceeded)
        assertFalse(result.startStopUnitSucceeded)
        assertTrue(result.anyCommandSucceeded)
        assertEquals(1, mockUsb.syncCacheCount)
        assertEquals(1, mockUsb.startStopUnitCount)
        assertEquals(listOf(0x02), mockUsb.startStopUnitFlags)
        assertEquals(listOf(0x35, 0x00, 0x1B), mockUsb.ejectOpcodeTrace)
    }

    @Test
    fun ejectUnitRecoversFromSynchronizeCacheCswRetryMinus1AndContinues() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 64,
            blockSize = 512,
            failSyncCacheCswWithMinus1 = true
        )
        val logger = OperationLogger()
        val transport = UsbBotTransport(io = mockUsb, logger = logger, sleepMs = {})
        val dev = ScsiBlockDevice(transport = transport, logger = logger, sleepMs = {})
        dev.initialize()
        mockUsb.ejectOpcodeTrace.clear()
        mockUsb.clearHaltInCount = 0
        mockUsb.clearHaltOutCount = 0
        mockUsb.requestSenseCount = 0

        val result = dev.ejectUnit()
        assertFalse(result.syncCacheSucceeded)
        assertTrue(result.testUnitReadySucceeded)
        assertTrue(result.startStopUnitSucceeded)
        assertTrue(result.anyCommandSucceeded)
        assertFalse(result.allCommandsFailed)

        // Must clear halt on both bulk endpoints, send REQUEST SENSE, and continue to TUR -> START STOP UNIT
        assertTrue("Expected Bulk-IN halt clear", mockUsb.clearHaltInCount >= 1)
        assertTrue("Expected Bulk-OUT halt clear", mockUsb.clearHaltOutCount >= 1)
        assertTrue("Expected REQUEST SENSE after SYNCHRONIZE CACHE failure", mockUsb.requestSenseCount >= 1)
        assertEquals(1, mockUsb.syncCacheCount)
        assertEquals(1, mockUsb.startStopUnitCount)
        // TUR runs twice if BOT reset raised UNIT ATTENTION (0x06/0x29), then START STOP UNIT
        assertEquals(0x35, mockUsb.ejectOpcodeTrace.first())
        assertTrue(0x00 in mockUsb.ejectOpcodeTrace)
        assertEquals(0x1B, mockUsb.ejectOpcodeTrace.last())

        val logDump = logger.entries.value.joinToString("\n") { it.message }
        assertTrue("Expected opcode 0x35 in log: $logDump", logDump.contains("0x35"))
        assertTrue("Expected usbCode=-1 in log: $logDump", logDump.contains("usbCode=-1"))
    }

    @Test
    fun ejectUnitReportsAllCommandsFailedWithoutThrowingWhenAllScsiCommandsFail() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 64,
            blockSize = 512
        )
        val logger = OperationLogger()
        val dev = ScsiBlockDevice(
            transport = UsbBotTransport(io = mockUsb, logger = logger, sleepMs = {}),
            logger = logger,
            sleepMs = {}
        )
        dev.initialize()
        mockUsb.failAllEjectCommandsWithCswMinus1 = true
        mockUsb.ejectOpcodeTrace.clear()

        val result = dev.ejectUnit()
        assertFalse(result.syncCacheSucceeded)
        assertFalse(result.testUnitReadySucceeded)
        assertFalse(result.startStopUnitSucceeded)
        assertTrue(result.allCommandsFailed)
        assertFalse(result.anyCommandSucceeded)
        assertEquals(listOf(0x35, 0x00, 0x1B), mockUsb.ejectOpcodeTrace)
        assertEquals(3, result.failingDiagnostics.size)

        val logDump = logger.entries.value.joinToString("\n") { it.message }
        assertTrue(logDump.contains("0x35"))
        assertTrue(logDump.contains("0x00"))
        assertTrue(logDump.contains("0x1B"))
        assertTrue(logDump.contains("usbCode=-1"))
    }

    @Test
    fun ejectUnitPerformsBotResetRecoveryBeforeFirstCommandWhenBotStateIsBad() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 256,
            blockSize = 512,
            failCswWithRead1OnFirst64KWrite = true
        )
        val transport = UsbBotTransport(io = mockUsb, sleepMs = {})
        val dev = ScsiBlockDevice(transport = transport, sleepMs = {})
        dev.initialize()

        // Put transport into bad BOT state via CSW failure on a raw command
        try {
            transport.executeCommand(
                lun = 0,
                cdb = ScsiCommandSet.read10(0L, 128),
                direction = BotDirection.IN,
                dataBuffer = ByteArray(128 * 512),
                dataLength = 128 * 512,
                commandName = "READ_TEST"
            )
        } catch (_: UsbStorageException) {
        }
        assertTrue(transport.isBotStateBad)
        val resetsBeforeEject = mockUsb.massStorageResetCount
        mockUsb.ejectEventTrace.clear()

        val result = dev.ejectUnit()
        assertTrue(result.anyCommandSucceeded)
        assertTrue(mockUsb.massStorageResetCount > resetsBeforeEject)
        assertEquals("RESET", mockUsb.ejectEventTrace.firstOrNull())
    }

    @Test
    fun ejectUnitThrowsDisconnectedWhenUnpluggedDuringStartStopUnit() {
        val mockUsb = VirtualUsbScsiFlashDrive(
            totalBlocks = 64,
            blockSize = 512,
            disconnectOnStartStopUnit = true
        )
        val dev = ScsiBlockDevice(transport = UsbBotTransport(io = mockUsb, sleepMs = {}), sleepMs = {})
        dev.initialize()

        try {
            dev.ejectUnit()
            fail("Expected UsbDisconnectedException when unplugged during eject")
        } catch (_: UsbDisconnectedException) {
            assertEquals(1, mockUsb.syncCacheCount)
        }
    }

    /**
     * Spec-accurate virtual USB BOT + SCSI flash drive emulator supporting fault injection
     * for CSW `read=1` / stall conditions, partial bulkOut transfers, and BOT Reset Recovery.
     */
    private class VirtualUsbScsiFlashDrive(
        val totalBlocks: Int,
        val blockSize: Int,
        var writeProtected: Boolean = false,
        private var unitAttentionOnce: Boolean = false,
        private var failCswWithRead1OnFirst64KWrite: Boolean = false,
        private val maxAcceptedBulkOutSlice: Int = Int.MAX_VALUE,
        private var injectStray1ByteBeforeCswOnce: Boolean = false,
        private val permanentCswFailureOnWrites: Boolean = false,
        private var failSyncCacheWithIoErrorOnce: Boolean = false,
        private var failSyncCacheCswWithMinus1: Boolean = false,
        var failAllEjectCommandsWithCswMinus1: Boolean = false,
        private val startStopUnitIllegalOpcode: Boolean = false,
        private val startStopUnitLoejUnsupportedOnly: Boolean = false,
        private val disconnectOnStartStopUnit: Boolean = false
    ) : UsbBulkIo {

        override val interfaceId: Int = 0
        override val bulkInAddress: Int = 0x81
        override val bulkOutAddress: Int = 0x02
        override val maxBulkChunkBytes: Int = 16_384

        var connected: Boolean = true
        var maxObservedBulkChunk: Int = 0
        var massStorageResetCount: Int = 0
        var clearHaltInCount: Int = 0
        var clearHaltOutCount: Int = 0
        var writeCdbCount: Int = 0
        var syncCacheCount: Int = 0
        var requestSenseCount: Int = 0
        var startStopUnitCount: Int = 0
        val ejectOpcodeTrace = mutableListOf<Int>()
        val ejectEventTrace = mutableListOf<String>()
        val startStopUnitFlags = mutableListOf<Int>()

        private val storage = ByteArray(totalBlocks * blockSize)

        private var pendingTag: Int = 0
        private var pendingStatus: Int = 0
        private var pendingInData: ByteArray = ByteArray(0)
        private var pendingInOffset: Int = 0
        private var pendingOutLba: Long = -1L
        private var pendingOutExpected: Int = 0
        private var pendingOutReceived: Int = 0
        private var pendingOutBuf: ByteArray = ByteArray(0)
        private var lastSenseKey: Int = 0
        private var lastAsc: Int = 0

        private var corruptNextCswWithRead1: Boolean = false
        private var failNextCswWithMinus1: Boolean = false
        private var lockedUntilTurFailure: Boolean = false

        override fun isConnected(): Boolean = connected

        override fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray?,
            length: Int,
            timeoutMs: Int
        ): Int {
            if (!connected) return -1
            if (requestType == 0xA1 && request == 0xFE && buffer != null && length >= 1) {
                buffer[0] = 0
                return 1
            }
            if (requestType == 0x21 && request == 0xFF) {
                // BULK_ONLY_MASS_STORAGE_RESET
                massStorageResetCount++
                ejectEventTrace.add("RESET")
                pendingOutExpected = 0
                pendingOutReceived = 0
                pendingInData = ByteArray(0)
                pendingInOffset = 0
                corruptNextCswWithRead1 = false
                failNextCswWithMinus1 = false
                // Per SCSI spec, bus reset raises UNIT ATTENTION (0x06 / 0x29) on next TEST UNIT READY
                unitAttentionOnce = true
                return 0
            }
            if (requestType == 0x02 && request == 0x01) {
                // CLEAR_FEATURE(ENDPOINT_HALT)
                if (index == bulkInAddress) clearHaltInCount++
                if (index == bulkOutAddress) clearHaltOutCount++
                return 0
            }
            return 0
        }

        override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            if (!connected) return -1
            if (length > maxObservedBulkChunk) maxObservedBulkChunk = length

            // Data-OUT phase for WRITE(10)
            if (pendingOutExpected > pendingOutReceived) {
                val allowed = minOf(length, maxAcceptedBulkOutSlice)
                val toCopy = minOf(allowed, pendingOutExpected - pendingOutReceived)
                System.arraycopy(buffer, offset, pendingOutBuf, pendingOutReceived, toCopy)
                pendingOutReceived += toCopy
                if (pendingOutReceived == pendingOutExpected) {
                    if (!corruptNextCswWithRead1 && !permanentCswFailureOnWrites) {
                        System.arraycopy(
                            pendingOutBuf,
                            0,
                            storage,
                            (pendingOutLba * blockSize).toInt(),
                            pendingOutExpected
                        )
                    }
                    pendingOutExpected = 0
                    pendingOutReceived = 0
                    pendingStatus = UsbBotTransport.CSW_STATUS_PASSED
                }
                return toCopy
            }

            // 31-byte CBW
            require(length == UsbBotTransport.CBW_SIZE)
            pendingTag = ByteUtils.readI32LE(buffer, 4)
            val xferLen = ByteUtils.readI32LE(buffer, 8)
            val opcode = buffer[15]
            pendingInData = ByteArray(0)
            pendingInOffset = 0
            pendingStatus = UsbBotTransport.CSW_STATUS_PASSED

            if (opcode == ScsiCommandSet.OP_SYNCHRONIZE_CACHE_10 && failSyncCacheWithIoErrorOnce) {
                failSyncCacheWithIoErrorOnce = false
                throw IOException("Simulated SYNCHRONIZE CACHE transport failure")
            }

            when (opcode) {
                ScsiCommandSet.OP_INQUIRY -> {
                    val resp = ByteArray(36)
                    resp[0] = 0x00
                    resp[1] = 0x80.toByte()
                    ByteUtils.writeAscii(resp, 8, "SanDisk", 8)
                    ByteUtils.writeAscii(resp, 16, "Ultra USB 3.0", 16)
                    ByteUtils.writeAscii(resp, 32, "1.00", 4)
                    pendingInData = resp
                }

                ScsiCommandSet.OP_TEST_UNIT_READY -> {
                    ejectOpcodeTrace.add(0x00)
                    ejectEventTrace.add("TUR")
                    if (failAllEjectCommandsWithCswMinus1) {
                        failNextCswWithMinus1 = true
                    } else if (lockedUntilTurFailure) {
                        // Simulate dead controller after write failure
                        corruptNextCswWithRead1 = true
                    } else if (unitAttentionOnce) {
                        unitAttentionOnce = false
                        pendingStatus = UsbBotTransport.CSW_STATUS_FAILED
                        lastSenseKey = 0x06 // UNIT ATTENTION
                        lastAsc = 0x29      // Power-on or bus reset occurred
                    }
                }

                ScsiCommandSet.OP_REQUEST_SENSE -> {
                    requestSenseCount++
                    val sense = ByteArray(18)
                    sense[0] = 0x70
                    sense[2] = lastSenseKey.toByte()
                    sense[12] = lastAsc.toByte()
                    pendingInData = sense
                    lastSenseKey = 0
                    lastAsc = 0
                }

                ScsiCommandSet.OP_MODE_SENSE_6 -> {
                    val mode = ByteArray(xferLen.coerceAtLeast(4))
                    mode[0] = 3
                    mode[2] = if (writeProtected) 0x80.toByte() else 0x00
                    pendingInData = mode.copyOf(xferLen)
                }

                ScsiCommandSet.OP_READ_CAPACITY_10 -> {
                    val cap = ByteArray(8)
                    ByteUtils.writeI32BE(cap, 0, totalBlocks - 1)
                    ByteUtils.writeI32BE(cap, 4, blockSize)
                    pendingInData = cap
                }

                ScsiCommandSet.OP_READ_10 -> {
                    val lba = ByteUtils.readU32BE(buffer, 17)
                    val count = ByteUtils.readU16BE(buffer, 22)
                    val byteLen = count * blockSize
                    if (failCswWithRead1OnFirst64KWrite && count >= 128) {
                        failCswWithRead1OnFirst64KWrite = false
                        corruptNextCswWithRead1 = true
                    }
                    pendingInData = storage.copyOfRange((lba * blockSize).toInt(), (lba * blockSize).toInt() + byteLen)
                }

                ScsiCommandSet.OP_WRITE_10 -> {
                    writeCdbCount++
                    val lba = ByteUtils.readU32BE(buffer, 17)
                    val count = ByteUtils.readU16BE(buffer, 22)
                    pendingOutLba = lba
                    pendingOutExpected = count * blockSize
                    pendingOutReceived = 0
                    pendingOutBuf = ByteArray(pendingOutExpected)

                    if (failCswWithRead1OnFirst64KWrite && count >= 128) {
                        failCswWithRead1OnFirst64KWrite = false
                        corruptNextCswWithRead1 = true
                    }
                    if (permanentCswFailureOnWrites) {
                        corruptNextCswWithRead1 = true
                        lockedUntilTurFailure = true
                    }
                }

                ScsiCommandSet.OP_SYNCHRONIZE_CACHE_10 -> {
                    syncCacheCount++
                    ejectOpcodeTrace.add(0x35)
                    ejectEventTrace.add("SYNC")
                    if (failAllEjectCommandsWithCswMinus1) {
                        failNextCswWithMinus1 = true
                    } else if (failSyncCacheCswWithMinus1) {
                        failSyncCacheCswWithMinus1 = false
                        failNextCswWithMinus1 = true
                    } else {
                        pendingStatus = UsbBotTransport.CSW_STATUS_PASSED
                    }
                }

                ScsiCommandSet.OP_START_STOP_UNIT -> {
                    startStopUnitCount++
                    ejectOpcodeTrace.add(0x1B)
                    ejectEventTrace.add("START_STOP")
                    val flags = buffer[19].toInt() and 0xFF
                    startStopUnitFlags.add(flags)
                    if (disconnectOnStartStopUnit) {
                        connected = false
                        return -1
                    }
                    when {
                        failAllEjectCommandsWithCswMinus1 -> {
                            failNextCswWithMinus1 = true
                        }
                        startStopUnitIllegalOpcode -> {
                            pendingStatus = UsbBotTransport.CSW_STATUS_FAILED
                            lastSenseKey = 0x05 // ILLEGAL REQUEST
                            lastAsc = 0x20      // Invalid command operation code
                        }
                        startStopUnitLoejUnsupportedOnly && (flags and 0x02) != 0 -> {
                            pendingStatus = UsbBotTransport.CSW_STATUS_FAILED
                            lastSenseKey = 0x05 // ILLEGAL REQUEST
                            lastAsc = 0x24      // Invalid field in CDB
                        }
                        else -> {
                            pendingStatus = UsbBotTransport.CSW_STATUS_PASSED
                        }
                    }
                }
            }
            return length
        }

        override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            if (!connected) return -1
            if (length > maxObservedBulkChunk) maxObservedBulkChunk = length

            if (pendingInOffset < pendingInData.size) {
                val toCopy = minOf(length, pendingInData.size - pendingInOffset)
                System.arraycopy(pendingInData, pendingInOffset, buffer, offset, toCopy)
                pendingInOffset += toCopy
                return toCopy
            }

            if (failNextCswWithMinus1) {
                return -1
            }

            if (corruptNextCswWithRead1) {
                // Simulate controller returning 1 garbage byte ("read=1") instead of 13-byte CSW
                buffer[offset] = 0x00
                return 1
            }

            if (injectStray1ByteBeforeCswOnce) {
                injectStray1ByteBeforeCswOnce = false
                buffer[offset] = 0x7F
                return 1
            }

            require(length == UsbBotTransport.CSW_SIZE)
            ByteUtils.writeI32LE(buffer, offset, UsbBotTransport.CSW_SIGNATURE)
            ByteUtils.writeI32LE(buffer, offset + 4, pendingTag)
            ByteUtils.writeI32LE(buffer, offset + 8, 0)
            buffer[offset + 12] = pendingStatus.toByte()
            return UsbBotTransport.CSW_SIZE
        }
    }
}
