package com.usbbootable.creator.core.util

import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

object ByteUtils {

    fun readU16LE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun readU16BE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or
            (buf[offset + 1].toInt() and 0xFF)
    }

    fun readI32LE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun readU32LE(buf: ByteArray, offset: Int): Long {
        return readI32LE(buf, offset).toLong() and 0xFFFFFFFFL
    }

    fun readI32BE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    fun readU32BE(buf: ByteArray, offset: Int): Long {
        return readI32BE(buf, offset).toLong() and 0xFFFFFFFFL
    }

    fun readI64LE(buf: ByteArray, offset: Int): Long {
        val lo = readU32LE(buf, offset)
        val hi = readU32LE(buf, offset + 4)
        return lo or (hi shl 32)
    }

    fun readI64BE(buf: ByteArray, offset: Int): Long {
        val hi = readU32BE(buf, offset)
        val lo = readU32BE(buf, offset + 4)
        return (hi shl 32) or lo
    }

    fun writeU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun writeU16BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun writeI32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun writeI32BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    fun writeI64LE(buf: ByteArray, offset: Int, value: Long) {
        writeI32LE(buf, offset, (value and 0xFFFFFFFFL).toInt())
        writeI32LE(buf, offset + 4, ((value ushr 32) and 0xFFFFFFFFL).toInt())
    }

    fun writeI64BE(buf: ByteArray, offset: Int, value: Long) {
        writeI32BE(buf, offset, ((value ushr 32) and 0xFFFFFFFFL).toInt())
        writeI32BE(buf, offset + 4, (value and 0xFFFFFFFFL).toInt())
    }

    fun writeAscii(buf: ByteArray, offset: Int, text: String, length: Int, padByte: Byte = 0x20) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val copyLen = minOf(bytes.size, length)
        System.arraycopy(bytes, 0, buf, offset, copyLen)
        for (i in copyLen until length) {
            buf[offset + i] = padByte
        }
    }

    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    /**
     * Encodes a standard UUID into the mixed-endian 16-byte GUID format required by UEFI GPT:
     * time_low (4B LE), time_mid (2B LE), time_hi_and_version (2B LE),
     * clock_seq + node (8B BE).
     */
    fun uuidToGptBytes(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits

        val timeLow = ((msb ushr 32) and 0xFFFFFFFFL).toInt()
        val timeMid = ((msb ushr 16) and 0xFFFFL).toInt()
        val timeHi = (msb and 0xFFFFL).toInt()

        writeI32LE(out, 0, timeLow)
        writeU16LE(out, 4, timeMid)
        writeU16LE(out, 6, timeHi)
        writeI64BE(out, 8, lsb)
        return out
    }

    fun gptBytesToUuid(buf: ByteArray, offset: Int): UUID {
        val timeLow = readU32LE(buf, offset)
        val timeMid = readU16LE(buf, offset + 4).toLong() and 0xFFFFL
        val timeHi = readU16LE(buf, offset + 6).toLong() and 0xFFFFL
        val msb = (timeLow shl 32) or (timeMid shl 16) or timeHi
        val lsb = readI64BE(buf, offset + 8)
        return UUID(msb, lsb)
    }

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "Unknown"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KiB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MiB", mb)
        val gb = mb / 1024.0
        if (gb < 1024.0) return String.format(Locale.US, "%.2f GiB", gb)
        val tb = gb / 1024.0
        return String.format(Locale.US, "%.2f TiB", tb)
    }

    /**
     * Formats confirmed written bytes against total operation size using a shared unit derived
     * from [totalBytes] (e.g. "0.0 GB / 5.4 GB", "256.0 MB / 700.0 MB").
     */
    fun formatWrittenTotal(writtenBytes: Long, totalBytes: Long): String {
        if (totalBytes <= 0L) return "-- / --"
        val clampedWritten = writtenBytes.coerceIn(0L, totalBytes)
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val tb = gb * 1024.0
        return when {
            totalBytes >= 1024L * 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.1f TB / %.1f TB", clampedWritten / tb, totalBytes / tb)
            totalBytes >= 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.1f GB / %.1f GB", clampedWritten / gb, totalBytes / gb)
            totalBytes >= 1024L * 1024L ->
                String.format(Locale.US, "%.1f MB / %.1f MB", clampedWritten / mb, totalBytes / mb)
            totalBytes >= 1024L ->
                String.format(Locale.US, "%.1f KB / %.1f KB", clampedWritten / kb, totalBytes / kb)
            else -> "$clampedWritten B / $totalBytes B"
        }
    }

    fun formatDecimalCapacity(bytes: Long): String {
        if (bytes <= 0L) return "Unknown"
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1000.0) {
            String.format(Locale.US, "%.2f TB (%s)", gb / 1000.0, formatBytes(bytes))
        } else if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB (%s)", gb, formatBytes(bytes))
        } else {
            formatBytes(bytes)
        }
    }

    fun formatSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0.0 || bytesPerSecond.isNaN() || bytesPerSecond.isInfinite()) {
            return "-- MB/s"
        }
        val mbps = bytesPerSecond / (1024.0 * 1024.0)
        return if (mbps >= 1.0) {
            String.format(Locale.US, "%.2f MB/s", mbps)
        } else {
            String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        }
    }

    fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds < 0L) return "--:--"
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02dm %02ds", minutes, seconds)
        }
    }

    fun formatElapsedClock(totalSeconds: Long): String {
        val clamped = totalSeconds.coerceAtLeast(0L)
        val hours = clamped / 3600L
        val minutes = (clamped % 3600L) / 60L
        val seconds = clamped % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
