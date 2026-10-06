/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds where the bytes of an uncompressed zip entry are, so update_engine can read
 * payload.bin straight out of the package.
 *
 * The position comes from the entry's own local header, located through the central directory
 * (zip64 included). Summing header and data sizes from the start of the file instead goes wrong
 * as soon as a local header's extra field differs from the central one (alignment padding,
 * zip64 fields) or an entry has a data descriptor.
 */
object ZipEntryLocator {
    data class StoredEntry(val dataOffset: Long, val size: Long)

    private const val EOCD_SIG = 0x06054b50
    private const val EOCD_SIZE = 22
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val CEN_SIG = 0x02014b50
    private const val CEN_SIZE = 46
    private const val LOC_SIG = 0x04034b50
    private const val LOC_SIZE = 30
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val METHOD_STORED = 0
    private const val MAX_COMMENT = 0xFFFF
    private const val MAX_CENTRAL_DIRECTORY = 16L * 1024 * 1024

    @JvmStatic
    @Throws(IOException::class)
    fun locateStored(file: File, name: String): StoredEntry =
        RandomAccessFile(file, "r").use { raf -> locateStored(raf, name) }

    private fun locateStored(raf: RandomAccessFile, name: String): StoredEntry {
        val length = raf.length()
        val (cdOffset, cdSize) = findCentralDirectory(raf, length)
        if (cdSize > MAX_CENTRAL_DIRECTORY || cdOffset + cdSize > length) {
            throw IOException("Central directory out of range")
        }
        val cd = read(raf, cdOffset, cdSize.toInt())

        while (cd.remaining() >= CEN_SIZE) {
            val start = cd.position()
            if (cd.getInt(start) != CEN_SIG) {
                throw IOException("Bad central directory entry at ${cdOffset + start}")
            }
            val method = cd.getShort(start + 10).toInt() and 0xFFFF
            var compressedSize = cd.getInt(start + 20).toLong() and 0xFFFFFFFFL
            var size = cd.getInt(start + 24).toLong() and 0xFFFFFFFFL
            val nameLength = cd.getShort(start + 28).toInt() and 0xFFFF
            val extraLength = cd.getShort(start + 30).toInt() and 0xFFFF
            val commentLength = cd.getShort(start + 32).toInt() and 0xFFFF
            var localOffset = cd.getInt(start + 42).toLong() and 0xFFFFFFFFL
            val next = start + CEN_SIZE + nameLength + extraLength + commentLength
            if (next > cd.limit()) {
                throw IOException("Truncated central directory")
            }

            val entryName = String(cd.array(), start + CEN_SIZE, nameLength, Charsets.UTF_8)
            if (entryName != name) {
                cd.position(next)
                continue
            }

            // Values that don't fit 32 bits live in the zip64 extra field, in this order
            if (size == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL ||
                    localOffset == 0xFFFFFFFFL) {
                val zip64 = findExtra(cd, start + CEN_SIZE + nameLength, extraLength,
                        ZIP64_EXTRA_ID) ?: throw IOException("$name has no zip64 field")
                if (size == 0xFFFFFFFFL) size = zip64.getLong()
                if (compressedSize == 0xFFFFFFFFL) compressedSize = zip64.getLong()
                if (localOffset == 0xFFFFFFFFL) localOffset = zip64.getLong()
            }
            if (method != METHOD_STORED || compressedSize != size) {
                throw IOException("$name is compressed (method $method)")
            }

            val local = read(raf, localOffset, LOC_SIZE)
            if (local.getInt(0) != LOC_SIG) {
                throw IOException("Bad local header for $name at $localOffset")
            }
            val dataOffset = localOffset + LOC_SIZE +
                    (local.getShort(26).toInt() and 0xFFFF) +
                    (local.getShort(28).toInt() and 0xFFFF)
            if (dataOffset + size > length) {
                throw IOException("$name extends past the end of the file")
            }
            return StoredEntry(dataOffset, size)
        }
        throw IOException("$name not found")
    }

    /** Returns the offset and size of the central directory. */
    private fun findCentralDirectory(raf: RandomAccessFile, length: Long): Pair<Long, Long> {
        val tailSize = minOf(length, (EOCD_SIZE + MAX_COMMENT).toLong()).toInt()
        if (tailSize < EOCD_SIZE) {
            throw IOException("Not a zip file")
        }
        val tailStart = length - tailSize
        val tail = read(raf, tailStart, tailSize)
        var eocd = tailSize - EOCD_SIZE
        while (eocd >= 0 && tail.getInt(eocd) != EOCD_SIG) {
            eocd--
        }
        if (eocd < 0) {
            throw IOException("No end of central directory")
        }

        val cdSize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (cdSize != 0xFFFFFFFFL && cdOffset != 0xFFFFFFFFL) {
            return cdOffset to cdSize
        }

        val locatorPosition = tailStart + eocd - ZIP64_LOCATOR_SIZE
        val locator = read(raf, locatorPosition, ZIP64_LOCATOR_SIZE)
        if (locator.getInt(0) != ZIP64_LOCATOR_SIG) {
            throw IOException("No zip64 end of central directory locator")
        }
        val zip64 = read(raf, locator.getLong(8), 56)
        if (zip64.getInt(0) != ZIP64_EOCD_SIG) {
            throw IOException("Bad zip64 end of central directory")
        }
        return zip64.getLong(48) to zip64.getLong(40)
    }

    /** Positions a buffer on the data of the extra field [id], or returns null. */
    private fun findExtra(buffer: ByteBuffer, offset: Int, length: Int, id: Int): ByteBuffer? {
        var position = offset
        val end = offset + length
        while (position + 4 <= end) {
            val headerId = buffer.getShort(position).toInt() and 0xFFFF
            val dataSize = buffer.getShort(position + 2).toInt() and 0xFFFF
            if (headerId == id) {
                return ByteBuffer.wrap(buffer.array(), position + 4, dataSize)
                    .order(ByteOrder.LITTLE_ENDIAN)
            }
            position += 4 + dataSize
        }
        return null
    }

    private fun read(raf: RandomAccessFile, offset: Long, size: Int): ByteBuffer {
        if (offset < 0 || size < 0 || offset + size > raf.length()) {
            throw IOException("Read of $size bytes at $offset is out of range")
        }
        val bytes = ByteArray(size)
        raf.seek(offset)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }
}
