package com.usbbootable.creator.core.image

import java.io.IOException

/**
 * Represents a directory or regular file inside a parsed UDF or ISO-9660 image.
 * Paths are normalized using forward slashes without a leading slash (e.g., "efi/boot/bootx64.efi").
 */
data class ImageEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    internal val opaqueHandle: Any
) {
    val normalizedPathLower: String
        get() = path.lowercase()
}

interface ImageFileSystem {
    val formatName: String
    val volumeLabel: String
    val entries: List<ImageEntry>

    /**
     * Streams the full contents of [entry] sequentially in chunks into [consumer].
     * Supports files larger than 4 GiB without buffering the file in memory.
     */
    @Throws(IOException::class)
    fun streamFile(
        entry: ImageEntry,
        buffer: ByteArray,
        consumer: (data: ByteArray, length: Int) -> Unit
    )
}
