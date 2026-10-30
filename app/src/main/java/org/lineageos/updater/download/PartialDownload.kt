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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.zip.CRC32
import kotlin.math.min

/**
 * 1DM / IDM style segmented ("by parts") partial download manager.
 *
 * Each download segment/part is written to its own isolated part file `<destination>.part.<id>`,
 * while `<destination>.part.map` atomically records the block completion bitmap, per-block CRC32
 * checksums, and part range table (`[startBlock, endBlock)`).
 *
 * Because each part writes strictly block-aligned, CRC32-verified blocks into its own separate
 * part file and parts are only stitched/assembled into `<destination>` after all parts are
 * complete and CRC-verified, pausing and resuming (or mid-download crashes/network drops) can
 * never corrupt the final OTA package.
 */
object PartialDownload {
    const val BLOCK_SIZE = 4L * 1024 * 1024

    private const val MAP_MAGIC = 0x4c55504d // "LUPM"
    private const val MAP_VERSION_LEGACY = 1
    private const val MAP_VERSION_PARTS = 2

    /** Legacy single-stream / sparse data file path. */
    @JvmStatic
    fun dataFile(destination: File) = File(destination.path + ".part")

    /** Atomic block & part metadata manifest file. */
    @JvmStatic
    fun mapFile(destination: File) = File(destination.path + ".part.map")

    /** Dedicated file for an individual download part `<destination>.part.<id>`. */
    @JvmStatic
    fun partFile(destination: File, partId: Int) = File("${destination.path}.part.$partId")

    /** Staging file used while merging completed parts before atomic rename to [destination]. */
    @JvmStatic
    fun assembledFile(destination: File) = File(destination.path + ".part.assembled")

    /** Lists all `<destination>.part.<id>` files currently on disk for [destination]. */
    @JvmStatic
    fun listPartFiles(destination: File): List<File> {
        val parent = destination.parentFile ?: return emptyList()
        val prefix = destination.name + ".part."
        val files = parent.listFiles { _, name ->
            if (!name.startsWith(prefix)) return@listFiles false
            val suffix = name.removePrefix(prefix)
            suffix.isNotEmpty() && suffix.all { it.isDigit() }
        } ?: return emptyList()
        return files.sortedBy { it.name.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE }
    }

    /** Whether [candidate] is [destination] or one of its partial download artifacts. */
    @JvmStatic
    fun belongsTo(candidate: File, destination: File): Boolean {
        val destPath = destination.absolutePath
        val candPath = candidate.absolutePath
        return candPath == destPath ||
            candPath == "$destPath.part" ||
            candPath == "$destPath.part.map" ||
            candPath == "$destPath.part.map.tmp" ||
            candPath == "$destPath.part.assembled" ||
            candPath.startsWith("$destPath.part.")
    }

    /** Whether any of the package is on disk, finished or not. */
    @JvmStatic
    fun exists(destination: File): Boolean =
        destination.exists() ||
            dataFile(destination).exists() ||
            mapFile(destination).exists() ||
            listPartFiles(destination).isNotEmpty()

    /** Bytes of the package that are on disk, verified, and won't be downloaded again. */
    @JvmStatic
    fun downloadedBytes(destination: File): Long {
        if (destination.exists()) {
            return destination.length()
        }
        val map = readMap(mapFile(destination)) ?: return 0
        return map.doneBytes()
    }

    @JvmStatic
    fun delete(destination: File) {
        val targets = buildList {
            add(destination)
            add(dataFile(destination))
            add(mapFile(destination))
            add(File(mapFile(destination).path + ".tmp"))
            add(assembledFile(destination))
            addAll(listPartFiles(destination))
        }
        for (file in targets) {
            if (file.exists() && !file.delete()) {
                throw IOException("Could not delete $file")
            }
        }
    }

    @JvmStatic
    fun deleteQuietly(destination: File) {
        runCatching { delete(destination) }
    }

    /**
     * Metadata for one isolated part file `<destination>.part.<id>` covering blocks
     * `[startBlock, endBlock)`.
     */
    data class PartSpec(
        val id: Int,
        val startBlock: Int,
        val endBlock: Int,
    )

    /**
     * Tracks which blocks of a package of [total] bytes are complete, along with per-block
     * CRC32 checksums and the 1DM/IDM part table mapping block ranges to `.part.<id>` files.
     */
    class BlockMap @JvmOverloads constructor(
        val total: Long,
        val blockSize: Long,
        val done: BitSet,
        val blockCrcs: Map<Int, Int> = emptyMap(),
        val parts: List<PartSpec> = emptyList(),
    ) {
        val blockCount: Int = ((total + blockSize - 1) / blockSize).toInt()

        fun blockStart(block: Int): Long = block * blockSize

        fun blockEnd(block: Int): Long = minOf((block + 1) * blockSize, total)

        fun blockLength(block: Int): Int = (blockEnd(block) - blockStart(block)).toInt()

        fun isComplete(): Boolean = done.cardinality() == blockCount

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
    fun computeBlockCrc(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }

    @JvmStatic
    fun readMap(file: File): BlockMap? = runCatching {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < 8) return null
        val expectedCrc = CRC32().apply { update(bytes, 0, bytes.size - 8) }.value
        DataInputStream(bytes.inputStream()).use { input ->
            val magic = input.readInt()
            val version = input.readInt()
            if (magic != MAP_MAGIC || (version != MAP_VERSION_LEGACY && version != MAP_VERSION_PARTS)) {
                return null
            }
            val total = input.readLong()
            val blockSize = input.readLong()
            val bitsLen = input.readInt()
            if (bitsLen < 0 || bitsLen > bytes.size) return null
            val bits = ByteArray(bitsLen).also { input.readFully(it) }
            val blockCrcs = LinkedHashMap<Int, Int>()
            val parts = ArrayList<PartSpec>()
            if (version >= MAP_VERSION_PARTS) {
                val crcCount = input.readInt()
                if (crcCount < 0 || crcCount > 1_000_000) return null
                repeat(crcCount) {
                    val blockIdx = input.readInt()
                    val crc = input.readInt()
                    blockCrcs[blockIdx] = crc
                }
                val partCount = input.readInt()
                if (partCount < 0 || partCount > 100_000) return null
                repeat(partCount) {
                    val id = input.readInt()
                    val startBlock = input.readInt()
                    val endBlock = input.readInt()
                    if (startBlock in 0..endBlock) {
                        parts.add(PartSpec(id, startBlock, endBlock))
                    }
                }
            }
            val storedCrc = input.readLong()
            if (storedCrc != expectedCrc || total <= 0 || blockSize <= 0) return null
            BlockMap(total, blockSize, BitSet.valueOf(bits), blockCrcs, parts)
        }
    }.getOrNull()

    /** Replaces the map atomically, so a crash leaves either the old or the new one. */
    @JvmStatic
    @Throws(IOException::class)
    fun writeMap(file: File, map: BlockMap) {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(MAP_MAGIC)
            out.writeInt(MAP_VERSION_PARTS)
            out.writeLong(map.total)
            out.writeLong(map.blockSize)
            val bits = map.done.toByteArray()
            out.writeInt(bits.size)
            out.write(bits)
            out.writeInt(map.blockCrcs.size)
            for ((blockIdx, crc) in map.blockCrcs) {
                out.writeInt(blockIdx)
                out.writeInt(crc)
            }
            out.writeInt(map.parts.size)
            for (part in map.parts) {
                out.writeInt(part.id)
                out.writeInt(part.startBlock)
                out.writeInt(part.endBlock)
            }
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

    /**
     * Verifies on-disk part files against [map] before resuming a download:
     * - Migrates a legacy single `.part` file into `.part.0` if needed.
     * - For each part in [map], checks that every block marked done exists on disk and matches
     *   its recorded CRC32 checksum.
     * - Truncates any unverified trailing bytes or corrupted blocks from the part file so a
     *   resumed download starts cleanly on a verified block boundary.
     */
    @JvmStatic
    fun verifyAndSanitizeParts(destination: File, map: BlockMap): BlockMap {
        val done = map.done.clone() as BitSet
        val validCrcs = LinkedHashMap<Int, Int>()
        val legacyData = dataFile(destination)

        // If resuming from a legacy v1 single `.part` file without a part table, wrap it as part 0
        val effectiveParts: List<PartSpec> = if (map.parts.isNotEmpty()) {
            map.parts.sortedBy { it.startBlock }
        } else if (legacyData.exists()) {
            val part0 = partFile(destination, 0)
            if (!part0.exists()) {
                legacyData.renameTo(part0)
            }
            listOf(PartSpec(0, 0, map.blockCount))
        } else {
            emptyList()
        }

        if (effectiveParts.isEmpty()) {
            return BlockMap(map.total, map.blockSize, BitSet(), emptyMap(), emptyList())
        }

        // Clear any marked bits outside known parts
        val coveredBlocks = BitSet(map.blockCount)
        val sanitizedParts = ArrayList<PartSpec>(effectiveParts.size)
        val scratch = ByteArray(min(map.blockSize, 256L * 1024).toInt())

        for (part in effectiveParts) {
            if (part.startBlock < 0 || part.endBlock > map.blockCount ||
                    part.startBlock >= part.endBlock) {
                continue
            }
            sanitizedParts.add(part)
            val pFile = partFile(destination, part.id)
            if (!pFile.exists()) {
                done.clear(part.startBlock, part.endBlock)
                continue
            }

            var validBlocksInPart = 0
            RandomAccessFile(pFile, "rw").use { raf ->
                val fileLen = raf.length()
                for (b in part.startBlock until part.endBlock) {
                    if (!done.get(b)) break
                    val relOffset = (b - part.startBlock).toLong() * map.blockSize
                    val blockLen = map.blockLength(b)
                    if (fileLen < relOffset + blockLen) {
                        done.clear(b, part.endBlock)
                        break
                    }
                    // Compute CRC32 of the block on disk and verify if recorded
                    raf.seek(relOffset)
                    val crc = CRC32()
                    var remaining = blockLen
                    var readOk = true
                    while (remaining > 0) {
                        val n = raf.read(scratch, 0, min(scratch.size, remaining))
                        if (n <= 0) {
                            readOk = false
                            break
                        }
                        crc.update(scratch, 0, n)
                        remaining -= n
                    }
                    val actualCrc = crc.value.toInt()
                    val expectedCrc = map.blockCrcs[b]
                    if (!readOk || (expectedCrc != null && expectedCrc != actualCrc)) {
                        done.clear(b, part.endBlock)
                        break
                    }
                    validCrcs[b] = actualCrc
                    coveredBlocks.set(b)
                    validBlocksInPart++
                }

                // Truncate any unverified trailing bytes past the last verified contiguous block
                val verifiedBytesInPart = if (part.startBlock + validBlocksInPart == map.blockCount &&
                        validBlocksInPart > 0) {
                    map.blockEnd(map.blockCount - 1) - map.blockStart(part.startBlock)
                } else {
                    validBlocksInPart.toLong() * map.blockSize
                }
                if (fileLen != verifiedBytesInPart) {
                    raf.setLength(verifiedBytesInPart)
                    raf.fd.sync()
                }
            }
        }

        // Any bit not backed by a verified block in a part file must be cleared
        done.and(coveredBlocks)
        return BlockMap(map.total, map.blockSize, done, validCrcs, sanitizedParts)
    }

    /** Thrown when a block in a part file fails verification during final assembly. */
    class CorruptPartException(val blockIndex: Int, message: String) : IOException(message)

    /**
     * Merges all completed `<destination>.part.<id>` files in block order into
     * `<destination>.part.assembled`, verifying every block's length and CRC32 along the way,
     * syncs to disk, atomically renames to [destination], and cleans up all part files.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun assembleParts(destination: File, map: BlockMap) {
        val sortedParts = map.parts.sortedBy { it.startBlock }
        if (sortedParts.isEmpty() || sortedParts.first().startBlock != 0 ||
                sortedParts.last().endBlock != map.blockCount) {
            throw IOException("Part table does not cover 0..${map.blockCount}")
        }
        for (i in 1 until sortedParts.size) {
            if (sortedParts[i].startBlock != sortedParts[i - 1].endBlock) {
                throw IOException("Gap or overlap between parts at block ${sortedParts[i].startBlock}")
            }
        }

        val staging = assembledFile(destination)
        if (staging.exists() && !staging.delete()) {
            throw IOException("Could not delete stale staging file $staging")
        }

        val buffer = ByteArray(min(map.blockSize, 256L * 1024).toInt())
        RandomAccessFile(staging, "rw").use { outRaf ->
            outRaf.setLength(0)
            var writtenTotal = 0L

            for (part in sortedParts) {
                val pFile = partFile(destination, part.id)
                if (!pFile.exists()) {
                    throw CorruptPartException(part.startBlock, "Missing part file $pFile")
                }
                RandomAccessFile(pFile, "r").use { inRaf ->
                    for (b in part.startBlock until part.endBlock) {
                        val relOffset = (b - part.startBlock).toLong() * map.blockSize
                        val blockLen = map.blockLength(b)
                        if (inRaf.length() < relOffset + blockLen) {
                            throw CorruptPartException(
                                b,
                                "Part file $pFile too short for block $b"
                            )
                        }
                        inRaf.seek(relOffset)
                        val crc = CRC32()
                        var remaining = blockLen
                        while (remaining > 0) {
                            val n = inRaf.read(buffer, 0, min(buffer.size, remaining))
                            if (n <= 0) {
                                throw CorruptPartException(
                                    b,
                                    "Unexpected EOF in $pFile at block $b"
                                )
                            }
                            crc.update(buffer, 0, n)
                            outRaf.write(buffer, 0, n)
                            remaining -= n
                        }
                        val expectedCrc = map.blockCrcs[b]
                        val actualCrc = crc.value.toInt()
                        if (expectedCrc != null && expectedCrc != actualCrc) {
                            throw CorruptPartException(
                                b,
                                "CRC mismatch in $pFile at block $b"
                            )
                        }
                        writtenTotal += blockLen
                    }
                }
            }

            if (writtenTotal != map.total || outRaf.length() != map.total) {
                throw IOException("Assembled length $writtenTotal != expected ${map.total}")
            }
            outRaf.fd.sync()
        }

        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not replace existing destination $destination")
        }
        if (!staging.renameTo(destination)) {
            staging.delete()
            throw IOException("Could not rename $staging to $destination")
        }

        // Clean up part files and metadata map now that destination is in place
        for (pFile in listPartFiles(destination)) {
            pFile.delete()
        }
        dataFile(destination).delete()
        mapFile(destination).delete()
        File(mapFile(destination).path + ".tmp").delete()
    }
}
