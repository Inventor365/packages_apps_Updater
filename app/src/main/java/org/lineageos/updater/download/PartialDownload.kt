/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.BitSet
import java.util.zip.CRC32

/**
 * Files of a download that isn't finished. The data is written to `<destination>.part`, in
 * blocks downloaded in any order, and `<destination>.part.map` records which blocks are
 * complete. The destination itself only appears once the whole package is there, so its
 * existence and size keep meaning "downloaded".
 */
object PartialDownload {
    const val BLOCK_SIZE = 4L * 1024 * 1024

    private const val MAP_MAGIC = 0x4c55504d // "LUPM"
    private const val MAP_VERSION = 1

    @JvmStatic
    fun dataFile(destination: File) = File(destination.path + ".part")

    @JvmStatic
    fun mapFile(destination: File) = File(destination.path + ".part.map")

    /** Whether any of the package is on disk, finished or not. */
    @JvmStatic
    fun exists(destination: File) = destination.exists() || dataFile(destination).exists()

    /** Bytes of the package that are on disk and won't be downloaded again. */
    @JvmStatic
    fun downloadedBytes(destination: File): Long {
        if (destination.exists()) {
            // Finished, or the prefix written by an older, sequential download
            return destination.length()
        }
        val map = readMap(mapFile(destination)) ?: return 0
        return map.doneBytes()
    }

    @JvmStatic
    fun delete(destination: File) {
        for (file in listOf(destination, dataFile(destination), mapFile(destination))) {
            if (file.exists() && !file.delete()) {
                throw IOException("Could not delete $file")
            }
        }
    }

    @JvmStatic
    fun deleteQuietly(destination: File) {
        runCatching { delete(destination) }
    }

    /** Which blocks of a package of [total] bytes are complete. */
    class BlockMap(val total: Long, val blockSize: Long, val done: BitSet) {
        val blockCount: Int = ((total + blockSize - 1) / blockSize).toInt()

        fun blockStart(block: Int) = block * blockSize

        fun blockEnd(block: Int) = minOf((block + 1) * blockSize, total)

        fun isComplete() = done.cardinality() == blockCount

        fun doneBytes(): Long {
            var bytes = 0L
            var block = done.nextSetBit(0)
            while (block in 0 until blockCount) {
                bytes += blockEnd(block) - blockStart(block)
                block = done.nextSetBit(block + 1)
            }
            return bytes
        }
    }

    @JvmStatic
    fun readMap(file: File): BlockMap? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < 8) return null
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 8) }.value
        DataInputStream(bytes.inputStream()).use { input ->
            if (input.readInt() != MAP_MAGIC || input.readInt() != MAP_VERSION) return null
            val total = input.readLong()
            val blockSize = input.readLong()
            val bits = ByteArray(input.readInt()).also { input.readFully(it) }
            if (input.readLong() != crc || total <= 0 || blockSize <= 0) return null
            BlockMap(total, blockSize, BitSet.valueOf(bits))
        }
    }.getOrNull()

    /** Replaces the map atomically, so a crash leaves either the old or the new one. */
    @JvmStatic
    @Throws(IOException::class)
    fun writeMap(file: File, map: BlockMap) {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(MAP_MAGIC)
            out.writeInt(MAP_VERSION)
            out.writeLong(map.total)
            out.writeLong(map.blockSize)
            val bits = map.done.toByteArray()
            out.writeInt(bits.size)
            out.write(bits)
        }
        val bytes = body.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            DataOutputStream(out).writeLong(crc)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not replace $file")
        }
    }
}
