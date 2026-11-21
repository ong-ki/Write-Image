package com.usbbootable.creator

import com.usbbootable.creator.core.usb.BlockDevice
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class InMemoryBlockDevice(
    override val totalBlocks: Long,
    override val blockSize: Int = 512,
    override var isReadOnly: Boolean = false
) : BlockDevice {

    val data = ByteArray((totalBlocks * blockSize).toInt())
    var syncCount: Int = 0
        private set

    override fun readBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        checkRange(startLba, blockCount)
        System.arraycopy(data, (startLba * blockSize).toInt(), buffer, offset, blockCount * blockSize)
    }

    override fun writeBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        if (isReadOnly) throw IOException("Device is read-only")
        checkRange(startLba, blockCount)
        System.arraycopy(buffer, offset, data, (startLba * blockSize).toInt(), blockCount * blockSize)
    }

    override fun syncCache() {
        syncCount++
    }

    override fun close() = Unit

    private fun checkRange(startLba: Long, blockCount: Int) {
        if (startLba < 0 || blockCount < 0 || startLba + blockCount > totalBlocks) {
            throw IOException("Out of bounds: lba=$startLba count=$blockCount total=$totalBlocks")
        }
    }
}

class FileBackedBlockDevice(
    val file: File,
    override val totalBlocks: Long,
    override val blockSize: Int = 512,
    override var isReadOnly: Boolean = false
) : BlockDevice {

    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(totalBlocks * blockSize.toLong())
    }
    private val channel = raf.channel
    var syncCount: Int = 0
        private set

    override fun readBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        if (startLba < 0 || blockCount < 0 || startLba + blockCount > totalBlocks) {
            throw IOException("Read out of bounds")
        }
        val bb = ByteBuffer.wrap(buffer, offset, blockCount * blockSize)
        var pos = startLba * blockSize.toLong()
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) throw IOException("Unexpected EOF")
            pos += n
        }
    }

    override fun writeBlocks(startLba: Long, blockCount: Int, buffer: ByteArray, offset: Int) {
        if (isReadOnly) throw IOException("Device is read-only")
        if (startLba < 0 || blockCount < 0 || startLba + blockCount > totalBlocks) {
            throw IOException("Write out of bounds")
        }
        val bb = ByteBuffer.wrap(buffer, offset, blockCount * blockSize)
        var pos = startLba * blockSize.toLong()
        while (bb.hasRemaining()) {
            val n = channel.write(bb, pos)
            pos += n
        }
    }

    override fun syncCache() {
        channel.force(true)
        syncCount++
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            raf.close()
        }
    }
}
