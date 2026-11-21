package com.usbbootable.creator.core.image

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 64-bit random-access image source supporting files > 4 GiB (e.g., Windows 10/11 ISOs).
 */
interface ImageSource : Closeable {
    val sizeBytes: Long

    @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int

    @Throws(IOException::class)
    fun readExact(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        var total = 0
        while (total < length) {
            val n = readAt(position + total, buffer, offset + total, length - total)
            if (n <= 0) {
                throw EOFException(
                    "Unexpected EOF reading image at pos=${position + total}, requested=$length, got=$total"
                )
            }
            total += n
        }
    }
}

class FileChannelImageSource(file: File) : ImageSource {
    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    override val sizeBytes: Long = channel.size()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= sizeBytes) return -1
        val toRead = minOf(length.toLong(), sizeBytes - position).toInt()
        if (toRead <= 0) return 0
        val bb = ByteBuffer.wrap(buffer, offset, toRead)
        return channel.read(bb, position)
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            raf.close()
        }
    }
}

class ByteArrayImageSource(private val data: ByteArray) : ImageSource {
    override val sizeBytes: Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= sizeBytes) return -1
        val toRead = minOf(length.toLong(), sizeBytes - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, toRead)
        return toRead
    }

    override fun close() = Unit
}

/**
 * Storage Access Framework (SAF) backed random-access [ImageSource].
 * Uses [ParcelFileDescriptor] + [FileChannel.read(ByteBuffer, Long)] for safe 64-bit random access
 * without needing legacy external storage permissions.
 */
class SafImageSource(
    context: Context,
    uri: Uri,
    knownSizeBytes: Long = -1L
) : ImageSource {

    private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Cannot open selected ISO file. Please re-select the file.")

    private val fis = FileInputStream(pfd.fileDescriptor)
    private val channel: FileChannel = fis.channel

    override val sizeBytes: Long

    init {
        val detectedSize = try {
            val chSize = channel.size()
            if (chSize > 0L) chSize else pfd.statSize
        } catch (_: Exception) {
            pfd.statSize
        }
        sizeBytes = if (detectedSize > 0L) detectedSize else knownSizeBytes
        if (sizeBytes <= 0L) {
            close()
            throw IOException(
                "Unable to determine ISO file size or file is empty. Ensure the ISO is stored locally on device storage."
            )
        }
        // Verify the descriptor supports positional reads (rejects non-seekable virtual cloud streams)
        try {
            val probe = ByteBuffer.allocate(1)
            channel.read(probe, 0L)
        } catch (e: Exception) {
            close()
            throw IOException(
                "Selected file is not directly seekable. Copy the ISO to local internal storage (e.g., Downloads) first.",
                e
            )
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= sizeBytes) return -1
        val toRead = minOf(length.toLong(), sizeBytes - position).toInt()
        if (toRead <= 0) return 0
        val bb = ByteBuffer.wrap(buffer, offset, toRead)
        return channel.read(bb, position)
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: Exception) {
        }
        try {
            fis.close()
        } catch (_: Exception) {
        }
        try {
            pfd.close()
        } catch (_: Exception) {
        }
    }
}
