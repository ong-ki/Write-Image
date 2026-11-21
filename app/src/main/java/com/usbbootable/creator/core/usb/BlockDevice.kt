package com.usbbootable.creator.core.usb

import java.io.Closeable
import java.io.IOException

/**
 * Raw sector-level block storage abstraction.
 *
 * On Android hardware this is backed by [ScsiBlockDevice] communicating directly over the
 * USB Host API (Mass Storage Bulk-Only Transport + SCSI Transparent Command Set) with no root
 * privileges required. In unit tests it can be backed by an in-memory or temp-file device.
 */
interface BlockDevice : Closeable {
    val blockSize: Int
    val totalBlocks: Long
    val isReadOnly: Boolean

    val capacityBytes: Long
        get() = totalBlocks * blockSize.toLong()

    @Throws(IOException::class)
    fun readBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int = 0)

    @Throws(IOException::class)
    fun writeBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int = 0)

    @Throws(IOException::class)
    fun syncCache()
}

/**
 * Bounds-checked partition slice over a parent [BlockDevice].
 * Ensures filesystem formatters and writers cannot escape their assigned LBA range.
 */
class PartitionBlockDevice(
    private val parent: BlockDevice,
    val startLba: Long,
    override val totalBlocks: Long
) : BlockDevice {

    init {
        require(startLba >= 0L) { "Partition startLba must be non-negative: $startLba" }
        require(totalBlocks > 0L) { "Partition totalBlocks must be positive: $totalBlocks" }
        require(startLba + totalBlocks <= parent.totalBlocks) {
            "Partition [$startLba, ${startLba + totalBlocks}) exceeds parent blocks ${parent.totalBlocks}"
        }
    }

    override val blockSize: Int
        get() = parent.blockSize

    override val isReadOnly: Boolean
        get() = parent.isReadOnly

    override fun readBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        checkBounds(startLba, blockCount)
        parent.readBlocks(this.startLba + startLba, blockCount, buffer, offset)
    }

    override fun writeBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        checkBounds(startLba, blockCount)
        parent.writeBlocks(this.startLba + startLba, blockCount, buffer, offset)
    }

    override fun syncCache() {
        parent.syncCache()
    }

    override fun close() {
        // Parent owns the underlying USB session lifecycle
    }

    private fun checkBounds(lba: Long, count: Int) {
        if (count < 0) throw IllegalArgumentException("Negative blockCount: $count")
        if (lba < 0L || lba + count > totalBlocks) {
            throw IOException(
                "Partition I/O out of bounds: lba=$lba, count=$count, partitionBlocks=$totalBlocks"
            )
        }
    }
}
