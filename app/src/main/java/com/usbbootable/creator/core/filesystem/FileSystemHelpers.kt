package com.usbbootable.creator.core.filesystem

import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.util.ByteUtils
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Shared validation, block-streaming, and verification helpers used across FAT32, exFAT, and NTFS
 * filesystem writers so limits and error checks are enforced uniformly without duplicate loops.
 */
internal object FileSystemIoHelpers {

    private val ILLEGAL_WINDOWS_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    fun splitAndValidatePath(normalizedPath: String): List<String> {
        val parts = normalizedPath.split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Empty file path" }
        for (part in parts) {
            validateComponentName(part)
        }
        return parts
    }

    fun validateComponentName(name: String) {
        if (name.isEmpty() || name.length > 255) {
            throw IOException("Invalid entry name length (${name.length}): '$name' (must be 1..255 chars)")
        }
        if (name == "." || name == "..") {
            throw IOException("Illegal reserved directory component name: '$name'")
        }
        for (ch in name) {
            if (ch.code < 0x20 || ch in ILLEGAL_WINDOWS_CHARS) {
                throw IOException("Illegal character 0x%04X in entry name '$name'".format(ch.code))
            }
        }
    }

    fun ensureNoCaseCollision(
        parentPathHint: String,
        newName: String,
        existingDirKeys: Set<String>,
        existingFileNames: Iterable<String>,
        expectDirectory: Boolean
    ) {
        val key = newName.lowercase(Locale.ROOT)
        if (!expectDirectory && key in existingDirKeys) {
            throw IOException("Path collision in '$parentPathHint': '$newName' conflicts with existing directory")
        }
        for (existing in existingFileNames) {
            if (existing.lowercase(Locale.ROOT) == key) {
                throw IOException("Path collision in '$parentPathHint': '$newName' conflicts with existing file '$existing'")
            }
        }
    }

    @Throws(IOException::class)
    fun streamToContiguousClusters(
        device: BlockDevice,
        normalizedPath: String,
        fsLabel: String,
        startLba: Long,
        expectedSizeBytes: Long,
        blockSize: Int,
        clusterSizeBytes: Int,
        batchBytes: Int,
        writeBuf: ByteArray,
        digest: MessageDigest,
        isCancelled: () -> Boolean,
        streamer: (onChunk: (ByteArray, Int) -> Unit) -> Unit,
        onBytesWritten: (Long) -> Unit
    ): String {
        var currentLba = startLba
        var bufFill = 0
        var streamedTotal = 0L

        streamer { data, len ->
            if (isCancelled()) throw CancellationException("$fsLabel write cancelled.")
            if (len < 0 || len > data.size) {
                throw IOException("Invalid chunk length $len (buffer size ${data.size}) for '$normalizedPath'")
            }
            if (len == 0) return@streamer
            if (len.toLong() > expectedSizeBytes - streamedTotal) {
                throw IOException(
                    "$fsLabel stream overrun for '$normalizedPath': expected=$expectedSizeBytes, " +
                        "incoming=${streamedTotal + len.toLong()}"
                )
            }
            streamedTotal += len.toLong()
            digest.update(data, 0, len)

            var srcPos = 0
            var rem = len
            while (rem > 0) {
                val space = batchBytes - bufFill
                val toCopy = minOf(rem, space)
                System.arraycopy(data, srcPos, writeBuf, bufFill, toCopy)
                bufFill += toCopy
                srcPos += toCopy
                rem -= toCopy

                if (bufFill == batchBytes) {
                    if (isCancelled()) throw CancellationException("$fsLabel write cancelled.")
                    val sectorCount = batchBytes / blockSize
                    device.writeBlocks(currentLba, sectorCount, writeBuf, 0)
                    currentLba += sectorCount
                    onBytesWritten(batchBytes.toLong())
                    bufFill = 0
                }
            }
        }

        if (bufFill > 0) {
            if (isCancelled()) throw CancellationException("$fsLabel write cancelled.")
            val paddedClusters = (bufFill + clusterSizeBytes - 1) / clusterSizeBytes
            val paddedBytes = paddedClusters * clusterSizeBytes
            writeBuf.fill(0, bufFill, paddedBytes)
            val sectorCount = paddedBytes / blockSize
            device.writeBlocks(currentLba, sectorCount, writeBuf, 0)
            onBytesWritten(bufFill.toLong())
        }

        if (streamedTotal != expectedSizeBytes) {
            throw IOException(
                "$fsLabel stream size mismatch for '$normalizedPath': expected=$expectedSizeBytes, got=$streamedTotal"
            )
        }

        return ByteUtils.toHex(digest.digest())
    }

    @Throws(IOException::class)
    fun verifyFromContiguousClusters(
        device: BlockDevice,
        fsLabel: String,
        startLba: Long,
        sizeBytes: Long,
        clusterCount: Long,
        blockSize: Int,
        clusterSizeBytes: Int,
        sectorsPerCluster: Int,
        batchClusters: Int,
        readBuf: ByteArray,
        digest: MessageDigest,
        expectedSha256Hex: String,
        isCancelled: () -> Boolean,
        onBytesVerified: (Long) -> Unit
    ): Boolean {
        digest.reset()
        if (sizeBytes == 0L) {
            return ByteUtils.toHex(digest.digest()) == expectedSha256Hex
        }

        var currentLba = startLba
        var remainingBytes = sizeBytes
        var remainingClusters = clusterCount

        while (remainingClusters > 0L) {
            if (isCancelled()) throw CancellationException("$fsLabel verification cancelled.")
            val clustersToRead = minOf(remainingClusters, batchClusters.toLong()).toInt()
            val sectorsToRead = clustersToRead * sectorsPerCluster
            device.readBlocks(currentLba, sectorsToRead, readBuf, 0)

            val validBytes = minOf(remainingBytes, (clustersToRead * clusterSizeBytes).toLong()).toInt()
            digest.update(readBuf, 0, validBytes)
            onBytesVerified(validBytes.toLong())

            currentLba += sectorsToRead
            remainingClusters -= clustersToRead
            remainingBytes -= validBytes
        }

        return ByteUtils.toHex(digest.digest()) == expectedSha256Hex
    }
}
