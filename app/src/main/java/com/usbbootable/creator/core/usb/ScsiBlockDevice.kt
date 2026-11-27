package com.usbbootable.creator.core.usb

import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.OperationLogger
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Genuine SCSI Transparent Command Set [BlockDevice] operating over [UsbBotTransport].
 *
 * Performs INQUIRY, TEST UNIT READY (with UNIT ATTENTION recovery), MODE SENSE (6) write-protect
 * inspection, READ CAPACITY (10/16), READ (10/16), WRITE (10/16), and SYNCHRONIZE CACHE (10).
 *
 * Includes post-reset SCSI communication state reinitialization (TEST UNIT READY + REQUEST SENSE),
 * adaptive transfer size step-down on controller stalls, and strict circuit-breaking when
 * USB communication becomes unreliable.
 */
class ScsiBlockDevice(
    private val transport: UsbBotTransport,
    val lun: Int = 0,
    private val logger: OperationLogger? = null,
    maxTransferBytes: Int = DEFAULT_MAX_TRANSFER_BYTES,
    private val isCancelled: () -> Boolean = { false },
    private val sleepMs: (Long) -> Unit = { ms -> if (ms > 0L) Thread.sleep(ms) }
) : BlockDevice {

    lateinit var inquiry: ScsiInquiryData
        private set

    override var blockSize: Int = 512
        private set

    override var totalBlocks: Long = 0L
        private set

    override var isReadOnly: Boolean = false
        private set

    // Adaptive SCSI transfer size: starts at 64 KiB (128 blocks, Linux usb-storage BOT standard)
    // and steps down automatically (64 KiB -> 32 KiB -> 16 KiB) if a controller stalls on larger CDBs.
    var activeMaxTransferBytes: Int = maxTransferBytes.coerceIn(MIN_TRANSFER_BYTES, MAX_ALLOWED_TRANSFER_BYTES)
        private set

    private var initialized = false
    private var closed = false

    // Pre-allocated CDB scratch buffers eliminate per-transfer heap allocations in hot I/O loops
    private val cdb10 = ByteArray(10)
    private val cdb16 = ByteArray(16)

    @Throws(IOException::class)
    fun initialize() {
        if (initialized) return

        // 1. INQUIRY (36 bytes)
        val inqBuf = ByteArray(36)
        executeControlOrMetaCommand(
            cdb = ScsiCommandSet.inquiry(36),
            direction = BotDirection.IN,
            buffer = inqBuf,
            offset = 0,
            length = 36,
            operationName = "INQUIRY(0x12)"
        )
        inquiry = ScsiCommandSet.parseInquiry(inqBuf)
        logger?.info(
            TAG,
            "SCSI INQUIRY: vendor='${inquiry.vendor}' product='${inquiry.product}' " +
                "rev='${inquiry.revision}' type=0x%02X removable=${inquiry.isRemovable}".format(
                    inquiry.peripheralDeviceType
                )
        )
        if (inquiry.peripheralDeviceType != 0x00 && inquiry.peripheralDeviceType != 0x0E) {
            throw UsbStorageException(
                "Unsupported SCSI peripheral type 0x%02X (expected direct-access block device).".format(
                    inquiry.peripheralDeviceType
                )
            )
        }

        // 2. TEST UNIT READY (wait for flash controller initialization / clear UNIT ATTENTION)
        waitUntilReady()

        // 3. MODE SENSE (6) 4-byte header to detect hardware write-protect switch
        isReadOnly = isReadOnly || probeWriteProtect()

        // 4. READ CAPACITY (10), upgrading to READ CAPACITY (16) for >2 TiB drives
        val cap10Buf = ByteArray(8)
        executeControlOrMetaCommand(
            cdb = ScsiCommandSet.readCapacity10(),
            direction = BotDirection.IN,
            buffer = cap10Buf,
            offset = 0,
            length = 8,
            operationName = "READ_CAPACITY_10(0x25)"
        )
        val (blocks10, blkSize10) = ScsiCommandSet.parseReadCapacity10(cap10Buf)
        if (blocks10 == -1L) {
            val cap16Buf = ByteArray(32)
            executeControlOrMetaCommand(
                cdb = ScsiCommandSet.readCapacity16(32),
                direction = BotDirection.IN,
                buffer = cap16Buf,
                offset = 0,
                length = 32,
                operationName = "READ_CAPACITY_16(0x9E)"
            )
            val (blocks16, blkSize16) = ScsiCommandSet.parseReadCapacity16(cap16Buf)
            totalBlocks = blocks16
            blockSize = blkSize16
        } else {
            totalBlocks = blocks10
            blockSize = blkSize10
        }

        if (blockSize <= 0 || (blockSize and (blockSize - 1)) != 0 || blockSize < 512 || blockSize > 65536) {
            throw UsbStorageException("Unsupported logical block size reported by USB drive: $blockSize bytes")
        }
        if (totalBlocks <= 0L || totalBlocks > Long.MAX_VALUE / blockSize.toLong()) {
            throw UsbStorageException(
                "USB drive reported invalid block geometry (blocks=$totalBlocks, blockSize=$blockSize)."
            )
        }

        initialized = true
        logger?.success(
            TAG,
            "Drive ready: ${inquiry.displayModel} | blocks=$totalBlocks | " +
                "blockSize=${blockSize}B | capacity=${ByteUtils.formatDecimalCapacity(capacityBytes)} | " +
                "writeProtected=$isReadOnly | scsiChunk=${activeMaxTransferBytes}B"
        )
    }

    private fun waitUntilReady(maxAttempts: Int = 12): ScsiSenseData? {
        var lastSense: ScsiSenseData? = null
        for (attempt in 1..maxAttempts) {
            if (isCancelled()) throw CancellationException("USB initialization cancelled.")
            val result = transport.executeCommand(
                lun = lun,
                cdb = ScsiCommandSet.testUnitReady(),
                direction = BotDirection.NONE,
                commandName = "TEST_UNIT_READY(0x00)"
            )
            if (result.isSuccess) return null
            if (result.isCommandFailed) {
                val sense = requestSense()
                lastSense = sense
                logger?.info(TAG, "TEST UNIT READY attempt $attempt/$maxAttempts: ${sense.describe()}")
                if (sense.isMediumNotPresent) {
                    throw UsbStorageException("No storage media detected inside USB adapter/reader.")
                }
                if (sense.isDataProtect) {
                    isReadOnly = true
                }
                if (sense.isUnitAttention || sense.isNotReady) {
                    sleepMs(150L)
                    continue
                }
                throw UsbStorageException("USB drive not ready: ${sense.describe()}")
            }
            sleepMs(150L)
        }
        throw UsbStorageException(
            "USB drive did not become ready in time: ${lastSense?.describe() ?: "timeout"}"
        )
    }

    private fun probeWriteProtect(): Boolean {
        return try {
            // Request only the 4-byte Mode Parameter Header (byte 2 bit 7 = WP) so controllers
            // that do not implement full mode pages do not stall or leave residual bytes.
            val buf = ByteArray(4)
            val result = transport.executeCommand(
                lun = lun,
                cdb = ScsiCommandSet.modeSense6(pageCode = 0x3F, allocationLength = 4),
                direction = BotDirection.IN,
                dataBuffer = buf,
                dataOffset = 0,
                dataLength = 4,
                commandName = "MODE_SENSE_6(0x1A)"
            )
            if (result.isSuccess && result.bytesTransferred >= 3) {
                val wp = (buf[2].toInt() and 0x80) != 0
                logger?.info(TAG, "MODE SENSE (6) write-protect bit = $wp")
                wp
            } else {
                if (result.isCommandFailed) requestSense()
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbDisconnectedException) {
            throw e
        } catch (_: Exception) {
            // If a controller stalled MODE SENSE (6), ensure the LUN is ready before continuing
            try {
                waitUntilReady(maxAttempts = 4)
            } catch (ce: CancellationException) {
                throw ce
            } catch (de: UsbDisconnectedException) {
                throw de
            } catch (_: Exception) {
            }
            false
        }
    }

    override fun readBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        ensureOpenAndInitialized()
        validateTransferArgs(startLba, blockCount, buffer, offset)
        if (blockCount == 0) return

        var currentLba = startLba
        var remaining = blockCount
        var currentOffset = offset

        while (remaining > 0) {
            if (isCancelled()) throw CancellationException("USB read cancelled.")
            val maxBlocksPerCmd = (activeMaxTransferBytes / blockSize).coerceAtLeast(1)
            val count = minOf(remaining, maxBlocksPerCmd)
            val byteLen = count * blockSize

            executeBlockTransferWithRecovery(
                isWrite = false,
                lba = currentLba,
                blockCount = count,
                buffer = buffer,
                offset = currentOffset,
                byteLen = byteLen
            )
            currentLba += count
            remaining -= count
            currentOffset += byteLen
        }
    }

    override fun writeBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        ensureOpenAndInitialized()
        if (isReadOnly) throw UsbWriteProtectedException()
        validateTransferArgs(startLba, blockCount, buffer, offset)
        if (blockCount == 0) return

        var currentLba = startLba
        var remaining = blockCount
        var currentOffset = offset

        while (remaining > 0) {
            if (isCancelled()) throw CancellationException("USB write cancelled.")
            // Re-evaluate maxBlocksPerCmd on every iteration so an adaptive step-down
            // immediately applies to all subsequent commands.
            val maxBlocksPerCmd = (activeMaxTransferBytes / blockSize).coerceAtLeast(1)
            val count = minOf(remaining, maxBlocksPerCmd)
            val byteLen = count * blockSize

            executeBlockTransferWithRecovery(
                isWrite = true,
                lba = currentLba,
                blockCount = count,
                buffer = buffer,
                offset = currentOffset,
                byteLen = byteLen
            )
            currentLba += count
            remaining -= count
            currentOffset += byteLen
        }
    }

    /**
     * Executes a single LBA-addressed READ or WRITE command with safe USB BOT Reset Recovery,
     * post-reset SCSI communication reinitialization (TEST UNIT READY + REQUEST SENSE),
     * and adaptive transfer size step-down on retry.
     *
     * Never blindly retries a write unless the SCSI target is confirmed ready after recovery.
     */
    private fun executeBlockTransferWithRecovery(
        isWrite: Boolean,
        lba: Long,
        blockCount: Int,
        buffer: ByteArray,
        offset: Int,
        byteLen: Int
    ) {
        val direction = if (isWrite) BotDirection.OUT else BotDirection.IN
        val timeout = if (isWrite) UsbBotTransport.WRITE_CSW_TIMEOUT_MS else UsbBotTransport.DEFAULT_TIMEOUT_MS

        var lastError: Exception? = null
        var recoveryLog: MutableList<String>? = null
        var opLabelCache: String? = null
        fun opLabel(): String = opLabelCache ?: (if (isWrite) {
            "WRITE(LBA=$lba, blocks=$blockCount, bytes=$byteLen)"
        } else {
            "READ(LBA=$lba, blocks=$blockCount, bytes=$byteLen)"
        }).also { opLabelCache = it }

        for (attempt in 1..MAX_BLOCK_RECOVERY_ATTEMPTS) {
            if (isCancelled()) throw CancellationException("USB transfer cancelled.")
            try {
                // If a previous attempt triggered reset recovery and stepped down activeMaxTransferBytes
                // below this command's byteLen, split this retry across the smaller safe sub-blocks!
                val currentMaxBlocks = (activeMaxTransferBytes / blockSize).coerceAtLeast(1)
                if (blockCount > currentMaxBlocks) {
                    var subLba = lba
                    var subRem = blockCount
                    var subOff = offset
                    while (subRem > 0) {
                        if (isCancelled()) throw CancellationException("USB transfer cancelled.")
                        val subCount = minOf(subRem, currentMaxBlocks)
                        val subBytes = subCount * blockSize
                        dispatchSingleBlockCdb(
                            isWrite = isWrite,
                            lba = subLba,
                            blockCount = subCount,
                            direction = direction,
                            buffer = buffer,
                            offset = subOff,
                            byteLen = subBytes,
                            timeoutMs = timeout
                        )
                        subLba += subCount
                        subRem -= subCount
                        subOff += subBytes
                    }
                    return
                } else {
                    dispatchSingleBlockCdb(
                        isWrite = isWrite,
                        lba = lba,
                        blockCount = blockCount,
                        direction = direction,
                        buffer = buffer,
                        offset = offset,
                        byteLen = byteLen,
                        timeoutMs = timeout
                    )
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UsbDisconnectedException) {
                throw e
            } catch (e: UsbWriteProtectedException) {
                throw e
            } catch (e: UsbStorageException) {
                lastError = e
                if (isCancelled()) throw CancellationException("USB transfer cancelled.")
                val op = opLabel()
                val logList = recoveryLog ?: mutableListOf<String>().also { recoveryLog = it }

                val resetAlreadyDone = (e as? UsbBotCommandException)?.resetPerformed == true
                if (!resetAlreadyDone) {
                    transport.resetRecovery()
                }

                // Step down transfer size if above minimum (e.g. 64 KiB -> 32 KiB -> 16 KiB)
                if (activeMaxTransferBytes > MIN_TRANSFER_BYTES) {
                    val prev = activeMaxTransferBytes
                    activeMaxTransferBytes = (activeMaxTransferBytes / 2).coerceAtLeast(MIN_TRANSFER_BYTES)
                    logger?.warn(
                        TAG,
                        "Stepping down SCSI transfer size from ${prev}B to ${activeMaxTransferBytes}B after $op failure"
                    )
                }

                if (attempt == MAX_BLOCK_RECOVERY_ATTEMPTS) {
                    logList.add("Attempt $attempt/$MAX_BLOCK_RECOVERY_ATTEMPTS exhausted (${e.message})")
                    break
                }

                // Reinitialize SCSI communication state and verify drive readiness before retrying!
                val reinitStatus = reinitializeAfterReset(op)
                logList.add("Attempt $attempt: ${e.message} -> Reinit: $reinitStatus")
                if (!reinitStatus.startsWith("READY")) {
                    // Do NOT blindly retry writes when communication cannot be re-established cleanly!
                    throw UsbStorageException(
                        "Unrecoverable USB error during $op | " +
                            "Endpoints: ${transport.endpointSummary} | " +
                            "Aborting to prevent data corruption because post-reset drive reinitialization failed ($reinitStatus). " +
                            "History: ${logList.joinToString(" ; ")}",
                        e
                    )
                }
                logger?.info(TAG, "SCSI target reinitialized ($reinitStatus); safely retrying $op (attempt ${attempt + 1})")
            }
        }

        throw UsbStorageException(
            "USB command failed after $MAX_BLOCK_RECOVERY_ATTEMPTS recovery attempts: ${opLabel()} | " +
                "Endpoints: ${transport.endpointSummary} | " +
                "Details: ${recoveryLog.orEmpty().joinToString(" ; ")}",
            lastError
        )
    }

    private fun dispatchSingleBlockCdb(
        isWrite: Boolean,
        lba: Long,
        blockCount: Int,
        direction: BotDirection,
        buffer: ByteArray,
        offset: Int,
        byteLen: Int,
        timeoutMs: Int
    ) {
        val use10 = lba <= 0xFFFFFFFFL &&
            blockCount <= 0xFFFF &&
            (lba + blockCount.toLong() - 1L) <= 0xFFFFFFFFL
        val cdb = if (use10) {
            fillReadWrite10Cdb(
                if (isWrite) ScsiCommandSet.OP_WRITE_10 else ScsiCommandSet.OP_READ_10,
                lba,
                blockCount
            )
        } else {
            fillReadWrite16Cdb(
                if (isWrite) ScsiCommandSet.OP_WRITE_16 else ScsiCommandSet.OP_READ_16,
                lba,
                blockCount
            )
        }
        fun cmdDetail(): String {
            val opHex = when {
                isWrite && use10 -> "0x2A"
                isWrite -> "0x8A"
                use10 -> "0x28"
                else -> "0x88"
            }
            return "${if (isWrite) "WRITE" else "READ"}($opHex, LBA=$lba, blocks=$blockCount)"
        }

        val result = transport.executeCommand(
            lun = lun,
            cdb = cdb,
            direction = direction,
            dataBuffer = buffer,
            dataOffset = offset,
            dataLength = byteLen,
            commandName = if (isWrite) "WRITE" else "READ",
            timeoutMs = timeoutMs
        )

        if (result.isSuccess) {
            if (result.bytesTransferred < byteLen || result.dataResidue != 0) {
                throw UsbBotCommandException(
                    commandName = cmdDetail(),
                    endpointInfo = transport.endpointSummary,
                    transferredBytes = result.bytesTransferred,
                    expectedBytes = byteLen,
                    usbErrorCode = result.dataResidue,
                    recoveryStatus = "Incomplete block transfer (transferred=${result.bytesTransferred}/$byteLen, residue=${result.dataResidue})",
                    resetPerformed = false
                )
            }
            return
        }

        if (result.isCommandFailed) {
            val cmdName = cmdDetail()
            val sense = requestSense()
            if (sense.isDataProtect) {
                isReadOnly = true
                throw UsbWriteProtectedException("USB drive refused write on $cmdName: ${sense.describe()}")
            }
            throw UsbBotCommandException(
                commandName = cmdName,
                endpointInfo = transport.endpointSummary,
                transferredBytes = result.bytesTransferred,
                expectedBytes = byteLen,
                usbErrorCode = sense.senseKey,
                recoveryStatus = "SCSI CHECK CONDITION: ${sense.describe()}",
                resetPerformed = false
            )
        }

        throw UsbBotCommandException(
            commandName = cmdDetail(),
            endpointInfo = transport.endpointSummary,
            transferredBytes = result.bytesTransferred,
            expectedBytes = byteLen,
            usbErrorCode = result.status,
            recoveryStatus = "Unexpected CSW status 0x%02X".format(result.status),
            resetPerformed = false
        )
    }

    /**
     * Reinitializes the SCSI logical unit state after a USB BOT Reset Recovery by polling
     * TEST UNIT READY and REQUEST SENSE to clear post-reset UNIT ATTENTION (0x06) or wait out
     * BECOMING READY (0x02/0x04). Returns a status string starting with "READY" only if the
     * device responds cleanly.
     */
    private fun reinitializeAfterReset(failedOperation: String): String {
        sleepMs(120L)
        var lastDetail = "no response"
        for (poll in 1..6) {
            if (isCancelled()) throw CancellationException("Cancelled during USB reset recovery.")
            try {
                val tur = transport.executeCommand(
                    lun = lun,
                    cdb = ScsiCommandSet.testUnitReady(),
                    direction = BotDirection.NONE,
                    commandName = "POST_RESET_TUR#$poll",
                    timeoutMs = 4_000
                )
                if (tur.isSuccess) {
                    return "READY(after $poll TUR poll${if (poll == 1) "" else "s"})"
                }
                if (tur.isCommandFailed) {
                    val sense = requestSense()
                    lastDetail = sense.describe()
                    logger?.info(TAG, "Post-reset TUR #$poll after $failedOperation: $lastDetail")
                    if (sense.isDataProtect) {
                        isReadOnly = true
                        throw UsbWriteProtectedException("Drive reported write protection after reset: $lastDetail")
                    }
                    if (sense.isMediumNotPresent) {
                        return "FAILED($lastDetail)"
                    }
                    // UNIT ATTENTION (0x06) is expected immediately after a Mass Storage Reset;
                    // reading sense clears it, and the next TUR confirms READY.
                    sleepMs(120L)
                    continue
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UsbDisconnectedException) {
                throw e
            } catch (e: UsbWriteProtectedException) {
                throw e
            } catch (e: Exception) {
                lastDetail = e.message ?: "TUR exception"
                sleepMs(150L)
            }
        }
        return "FAILED($lastDetail)"
    }

    private fun fillReadWrite10Cdb(opcode: Byte, lba: Long, blockCount: Int): ByteArray {
        cdb10.fill(0)
        cdb10[0] = opcode
        ByteUtils.writeI32BE(cdb10, 2, lba.toInt())
        ByteUtils.writeU16BE(cdb10, 7, blockCount)
        return cdb10
    }

    private fun fillReadWrite16Cdb(opcode: Byte, lba: Long, blockCount: Int): ByteArray {
        cdb16.fill(0)
        cdb16[0] = opcode
        ByteUtils.writeI64BE(cdb16, 2, lba)
        ByteUtils.writeI32BE(cdb16, 10, blockCount)
        return cdb16
    }

    override fun syncCache() {
        if (!initialized || closed) return
        try {
            val result = transport.executeCommand(
                lun = lun,
                cdb = ScsiCommandSet.synchronizeCache10(),
                direction = BotDirection.NONE,
                commandName = "SYNCHRONIZE_CACHE_10(0x35)",
                timeoutMs = UsbBotTransport.WRITE_CSW_TIMEOUT_MS
            )
            if (result.isSuccess) {
                logger?.info(TAG, "SYNCHRONIZE CACHE (10) flushed drive write cache")
            } else if (result.isCommandFailed) {
                val sense = requestSense()
                if (sense.isIllegalRequest) {
                    logger?.info(TAG, "SYNCHRONIZE CACHE not required by controller (${sense.describe()})")
                } else {
                    logger?.warn(TAG, "SYNCHRONIZE CACHE warning: ${sense.describe()}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbDisconnectedException) {
            throw e
        } catch (e: Exception) {
            // A failed cache flush means the drive may not have committed the write. Do not
            // let the operation report COMPLETED after a transport-level flush failure.
            logger?.warn(TAG, "SYNCHRONIZE CACHE failed: ${e.message}")
            throw UsbStorageException(
                "Failed to flush USB drive write cache: ${e.message ?: "unknown I/O error"}",
                e
            )
        }
    }

    fun hasBadBotState(): Boolean = transport.isBotStateBad

    /**
     * Best-effort safe eject sequence:
     * 1. If BOT state is bad (prior CSW error), performs BOT Reset Recovery before the first command.
     * 2. SYNCHRONIZE CACHE (10) (try once) -> on failure, clears halt on both bulk endpoints,
     *    sends REQUEST SENSE (ignoring result), logs diagnostics, and continues.
     * 3. TEST UNIT READY -> logs diagnostics on failure and continues.
     * 4. START STOP UNIT (LOEJ=1, START=0, try once) -> logs diagnostics on failure and continues.
     *
     * Never fails eject due to SCSI/USB command failures; only [CancellationException] and
     * [UsbDisconnectedException] propagate.
     */
    @Throws(CancellationException::class, UsbDisconnectedException::class)
    fun ejectUnit(
        forceInitialBotReset: Boolean = false,
        preLogLines: List<String> = emptyList()
    ): UsbEjectResult {
        if (closed) throw UsbStorageException("USB block device is closed.")
        if (isCancelled()) throw CancellationException("USB eject cancelled.")

        val logLines = preLogLines.toMutableList()
        val diagnostics = mutableListOf<ScsiCommandDiagnostic>()

        if (transport.isBotStateBad || forceInitialBotReset) {
            logger?.warn(TAG, "BOT state is bad (CSW error); running BOT Reset Recovery before eject")
            try {
                val rec = transport.resetRecoveryForBadState()
                val msg = "Pre-eject ${rec.describe()}"
                logLines += msg
                logger?.info(TAG, msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UsbDisconnectedException) {
                throw e
            } catch (e: Exception) {
                val msg = "Pre-eject BOT Reset Recovery error (ignored): ${e.message ?: e::class.simpleName}"
                logLines += msg
                logger?.warn(TAG, msg)
            }
        }

        val syncSucceeded = executeEjectSyncCacheOnce(logLines, diagnostics)
        val turSucceeded = executeEjectTestUnitReady(logLines, diagnostics)
        val startStopSucceeded = executeEjectStartStopOnce(logLines, diagnostics)

        return UsbEjectResult(
            syncCacheSucceeded = syncSucceeded,
            testUnitReadySucceeded = turSucceeded,
            startStopUnitSucceeded = startStopSucceeded,
            diagnostics = diagnostics.toList(),
            logText = logLines.joinToString("\n")
        )
    }

    private fun executeEjectSyncCacheOnce(
        logLines: MutableList<String>,
        diagnostics: MutableList<ScsiCommandDiagnostic>
    ): Boolean {
        if (isCancelled()) throw CancellationException("USB eject cancelled.")
        return try {
            val result = transport.executeCommand(
                lun = lun,
                cdb = ScsiCommandSet.synchronizeCache10(),
                direction = BotDirection.NONE,
                commandName = "SYNCHRONIZE_CACHE_10(0x35)",
                timeoutMs = EJECT_COMMAND_TIMEOUT_MS
            )
            if (result.isSuccess) {
                val msg = "SYNCHRONIZE_CACHE_10(0x35): PASSED (csw=0x00)"
                logLines += msg
                logger?.info(TAG, msg)
                diagnostics += ScsiCommandDiagnostic(
                    commandName = "SYNCHRONIZE_CACHE_10",
                    opcode = 0x35,
                    succeeded = true,
                    cswStatus = result.status
                )
                true
            } else {
                val (clearInRc, clearOutRc) = transport.clearBothEndpointHalts()
                val sense = requestSenseForEject(logLines)
                val diag = ScsiCommandDiagnostic(
                    commandName = "SYNCHRONIZE_CACHE_10",
                    opcode = 0x35,
                    succeeded = false,
                    cswStatus = result.status,
                    senseData = sense,
                    clearInRc = clearInRc,
                    clearOutRc = clearOutRc
                )
                diagnostics += diag
                val msg = "Eject command failed (ignored): ${diag.formatLogLine()}"
                logLines += msg
                logger?.warn(TAG, msg)
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbDisconnectedException) {
            throw e
        } catch (e: Exception) {
            recordEjectException("SYNCHRONIZE_CACHE_10", 0x35, e, logLines, diagnostics)
            false
        }
    }

    private fun executeEjectTestUnitReady(
        logLines: MutableList<String>,
        diagnostics: MutableList<ScsiCommandDiagnostic>
    ): Boolean {
        for (attempt in 1..2) {
            if (isCancelled()) throw CancellationException("USB eject cancelled.")
            try {
                val result = transport.executeCommand(
                    lun = lun,
                    cdb = ScsiCommandSet.testUnitReady(),
                    direction = BotDirection.NONE,
                    commandName = "TEST_UNIT_READY(0x00)",
                    timeoutMs = EJECT_COMMAND_TIMEOUT_MS
                )
                if (result.isSuccess) {
                    val msg = "TEST_UNIT_READY(0x00): PASSED (csw=0x00)"
                    logLines += msg
                    logger?.info(TAG, msg)
                    diagnostics += ScsiCommandDiagnostic(
                        commandName = "TEST_UNIT_READY",
                        opcode = 0x00,
                        succeeded = true,
                        cswStatus = result.status
                    )
                    return true
                }
                val sense = requestSenseForEject(logLines)
                if (sense != null && sense.isUnitAttention && attempt == 1) {
                    val uaDiag = ScsiCommandDiagnostic(
                        commandName = "TEST_UNIT_READY",
                        opcode = 0x00,
                        succeeded = false,
                        cswStatus = result.status,
                        senseData = sense
                    )
                    val msg = "TEST_UNIT_READY(0x00) cleared UNIT ATTENTION (${uaDiag.formatLogLine()}); retrying once"
                    logLines += msg
                    logger?.info(TAG, msg)
                    sleepMs(100L)
                    continue
                }
                val (clearInRc, clearOutRc) = transport.clearBothEndpointHalts()
                val diag = ScsiCommandDiagnostic(
                    commandName = "TEST_UNIT_READY",
                    opcode = 0x00,
                    succeeded = false,
                    cswStatus = result.status,
                    senseData = sense,
                    clearInRc = clearInRc,
                    clearOutRc = clearOutRc
                )
                diagnostics += diag
                val msg = "Eject command failed (ignored): ${diag.formatLogLine()}"
                logLines += msg
                logger?.warn(TAG, msg)
                return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: UsbDisconnectedException) {
                throw e
            } catch (e: Exception) {
                recordEjectException("TEST_UNIT_READY", 0x00, e, logLines, diagnostics)
                return false
            }
        }
        return false
    }

    private fun executeEjectStartStopOnce(
        logLines: MutableList<String>,
        diagnostics: MutableList<ScsiCommandDiagnostic>
    ): Boolean {
        if (isCancelled()) throw CancellationException("USB eject cancelled.")
        return try {
            val result = transport.executeCommand(
                lun = lun,
                cdb = ScsiCommandSet.startStopUnit(start = false, loadEject = true),
                direction = BotDirection.NONE,
                commandName = "START_STOP_UNIT(0x1B,eject)",
                timeoutMs = EJECT_COMMAND_TIMEOUT_MS
            )
            if (result.isSuccess) {
                val msg = "START_STOP_UNIT(0x1B, LOEJ=1, START=0): PASSED (csw=0x00)"
                logLines += msg
                logger?.info(TAG, msg)
                diagnostics += ScsiCommandDiagnostic(
                    commandName = "START_STOP_UNIT",
                    opcode = 0x1B,
                    succeeded = true,
                    cswStatus = result.status
                )
                true
            } else {
                val sense = requestSenseForEject(logLines)
                val (clearInRc, clearOutRc) = transport.clearBothEndpointHalts()
                val alreadyEjected = sense?.isMediumNotPresent == true
                val diag = ScsiCommandDiagnostic(
                    commandName = "START_STOP_UNIT",
                    opcode = 0x1B,
                    succeeded = alreadyEjected,
                    cswStatus = result.status,
                    senseData = sense,
                    clearInRc = clearInRc,
                    clearOutRc = clearOutRc
                )
                diagnostics += diag
                val msg = if (alreadyEjected) {
                    "START_STOP_UNIT(0x1B) reports medium already ejected: ${diag.formatLogLine()}"
                } else {
                    "Eject command failed (ignored): ${diag.formatLogLine()}"
                }
                logLines += msg
                if (alreadyEjected) logger?.info(TAG, msg) else logger?.warn(TAG, msg)
                alreadyEjected
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbDisconnectedException) {
            throw e
        } catch (e: Exception) {
            recordEjectException("START_STOP_UNIT", 0x1B, e, logLines, diagnostics)
            false
        }
    }

    private fun recordEjectException(
        commandName: String,
        opcode: Int,
        e: Exception,
        logLines: MutableList<String>,
        diagnostics: MutableList<ScsiCommandDiagnostic>
    ) {
        val (clearInRc, clearOutRc) = transport.clearBothEndpointHalts()
        val sense = requestSenseForEject(logLines)
        val usbCode = (e as? UsbBotCommandException)?.usbErrorCode
        val diag = ScsiCommandDiagnostic(
            commandName = commandName,
            opcode = opcode,
            succeeded = false,
            usbErrorCode = usbCode,
            senseData = sense,
            clearInRc = clearInRc,
            clearOutRc = clearOutRc,
            detail = e.message ?: e::class.simpleName.orEmpty()
        )
        diagnostics += diag
        val msg = "Eject command failed (ignored): ${diag.formatLogLine()}"
        logLines += msg
        logger?.warn(TAG, msg)
    }

    private fun requestSenseForEject(logLines: MutableList<String>): ScsiSenseData? {
        val senseBuf = ByteArray(18)
        return try {
            val res = transport.executeCommand(
                lun = lun,
                cdb = ScsiCommandSet.requestSense(18),
                direction = BotDirection.IN,
                dataBuffer = senseBuf,
                dataOffset = 0,
                dataLength = 18,
                commandName = "REQUEST_SENSE(0x03)",
                timeoutMs = EJECT_COMMAND_TIMEOUT_MS
            )
            if (res.isSuccess && res.bytesTransferred >= 4) {
                ScsiCommandSet.parseSense(senseBuf, res.bytesTransferred)
            } else {
                val msg = "Eject REQUEST_SENSE(0x03) ignored: opcode=0x03 | cswStatus=0x%02X | transferred=${res.bytesTransferred}/18".format(
                    res.status and 0xFF
                )
                logLines += msg
                logger?.warn(TAG, msg)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbDisconnectedException) {
            throw e
        } catch (e: Exception) {
            val usbCode = (e as? UsbBotCommandException)?.usbErrorCode
            val msg = "Eject REQUEST_SENSE(0x03) failed (ignored): opcode=0x03 | usbCode=${usbCode ?: "N/A"} | ${e.message ?: e::class.simpleName}"
            logLines += msg
            logger?.warn(TAG, msg)
            null
        }
    }

    override fun close() {
        closed = true
    }

    private fun executeControlOrMetaCommand(
        cdb: ByteArray,
        direction: BotDirection,
        buffer: ByteArray?,
        offset: Int,
        length: Int,
        operationName: String,
        maxRetries: Int = 3
    ) {
        var lastError: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val result = transport.executeCommand(
                    lun = lun,
                    cdb = cdb,
                    direction = direction,
                    dataBuffer = buffer,
                    dataOffset = offset,
                    dataLength = length,
                    commandName = operationName
                )
                if (result.isSuccess) {
                    if (length > 0 && result.bytesTransferred < length) {
                        throw UsbStorageException(
                            "$operationName short transfer: transferred=${result.bytesTransferred}/$length residue=${result.dataResidue}"
                        )
                    }
                    return
                }
                if (result.isCommandFailed) {
                    val sense = requestSense()
                    if (sense.isDataProtect) {
                        isReadOnly = true
                        throw UsbWriteProtectedException("USB drive refused command: ${sense.describe()}")
                    }
                    if (sense.isUnitAttention || sense.isBecomingReady) {
                        logger?.warn(TAG, "$operationName retry $attempt after ${sense.describe()}")
                        sleepMs(150L)
                        continue
                    }
                    throw UsbStorageException("$operationName failed: ${sense.describe()}")
                }
                throw UsbStorageException("$operationName failed with CSW status 0x%02X".format(result.status))
            } catch (e: CancellationException) {
                throw e
            } catch (e: UsbDisconnectedException) {
                throw e
            } catch (e: UsbWriteProtectedException) {
                throw e
            } catch (e: UsbStorageException) {
                lastError = e
                if (isCancelled()) throw CancellationException("USB transfer cancelled.")
                if (attempt == maxRetries) throw e
                logger?.warn(TAG, "$operationName attempt $attempt failed (${e.message}); retrying")
                sleepMs(150L)
            }
        }
        throw lastError ?: UsbStorageException("$operationName failed after $maxRetries attempts")
    }

    private fun requestSense(): ScsiSenseData {
        val senseBuf = ByteArray(18)
        return try {
            val res = transport.executeCommand(
                lun = lun,
                cdb = ScsiCommandSet.requestSense(18),
                direction = BotDirection.IN,
                dataBuffer = senseBuf,
                dataOffset = 0,
                dataLength = 18,
                commandName = "REQUEST_SENSE(0x03)",
                timeoutMs = 4_000
            )
            if (res.isSuccess && res.bytesTransferred >= 4) {
                ScsiCommandSet.parseSense(senseBuf, res.bytesTransferred)
            } else {
                ScsiSenseData(0, 0x04, 0, 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbDisconnectedException) {
            throw e
        } catch (_: Exception) {
            ScsiSenseData(0, 0x04, 0, 0)
        }
    }

    private fun ensureOpenAndInitialized() {
        if (closed) throw UsbStorageException("USB block device is closed.")
        if (!initialized) throw UsbStorageException("USB block device is not initialized.")
    }

    private fun validateTransferArgs(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        if (startLba < 0L || blockCount < 0 || startLba > totalBlocks || blockCount.toLong() > totalBlocks - startLba) {
            throw IOException(
                "Block I/O out of range: lba=$startLba count=$blockCount totalBlocks=$totalBlocks"
            )
        }
        val neededBytes = blockCount.toLong() * blockSize.toLong()
        if (offset < 0 || neededBytes > (buffer.size - offset).toLong()) {
            throw IllegalArgumentException(
                "Buffer too small: offset=$offset needed=$neededBytes bufferSize=${buffer.size}"
            )
        }
    }

    companion object {
        private const val TAG = "ScsiBlock"
        // 64 KiB (128 sectors of 512B) matches Linux kernel usb-storage max_sectors=128 standard
        const val DEFAULT_MAX_TRANSFER_BYTES = 64 * 1024
        const val MIN_TRANSFER_BYTES = 4 * 1024
        const val MAX_ALLOWED_TRANSFER_BYTES = 128 * 1024
        private const val MAX_BLOCK_RECOVERY_ATTEMPTS = 3
        private const val EJECT_COMMAND_TIMEOUT_MS = 5_000
    }
}
