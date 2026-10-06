/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipEntryLocatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = "CrAU".toByteArray() + ByteArray(5000) { it.toByte() }

    /** Writes zips byte by byte, to get layouts ZipOutputStream won't produce. */
    private class RawZip {
        private val out = ByteArrayOutputStream()
        private val central = ByteArrayOutputStream()
        private var count = 0

        fun add(
            name: String,
            data: ByteArray,
            method: Int = 0,
            localExtra: ByteArray = ByteArray(0),
            centralExtra: ByteArray = ByteArray(0),
            dataDescriptor: Boolean = false,
            zip64Offset: Boolean = false,
        ) {
            val offset = out.size().toLong()
            val crc = CRC32().apply { update(data) }.value.toInt()
            val nameBytes = name.toByteArray()
            val flags = if (dataDescriptor) 8 else 0
            val local = le(30 + nameBytes.size + localExtra.size)
                .putInt(0x04034b50).putShort(20).putShort(flags.toShort())
                .putShort(method.toShort()).putInt(0)
                .putInt(if (dataDescriptor) 0 else crc)
                .putInt(if (dataDescriptor) 0 else data.size)
                .putInt(if (dataDescriptor) 0 else data.size)
                .putShort(nameBytes.size.toShort()).putShort(localExtra.size.toShort())
                .put(nameBytes).put(localExtra)
            out.write(local.array())
            out.write(data)
            if (dataDescriptor) {
                out.write(le(16).putInt(0x08074b50).putInt(crc).putInt(data.size)
                    .putInt(data.size).array())
            }

            val extra = if (zip64Offset) {
                le(12).putShort(1).putShort(8).putLong(offset).array() + centralExtra
            } else {
                centralExtra
            }
            central.write(
                le(46 + nameBytes.size + extra.size)
                    .putInt(0x02014b50).putShort(20).putShort(20).putShort(flags.toShort())
                    .putShort(method.toShort()).putInt(0).putInt(crc)
                    .putInt(data.size).putInt(data.size)
                    .putShort(nameBytes.size.toShort()).putShort(extra.size.toShort())
                    .putShort(0).putShort(0).putShort(0).putInt(0)
                    .putInt(if (zip64Offset) -1 else offset.toInt())
                    .put(nameBytes).put(extra).array()
            )
            count++
        }

        fun write(file: File, zip64End: Boolean = false) {
            val cdOffset = out.size().toLong()
            val cd = central.toByteArray()
            out.write(cd)
            if (zip64End) {
                val zip64EndOffset = out.size().toLong()
                out.write(
                    le(56).putInt(0x06064b50).putLong(44).putShort(45).putShort(45)
                        .putInt(0).putInt(0).putLong(count.toLong()).putLong(count.toLong())
                        .putLong(cd.size.toLong()).putLong(cdOffset).array()
                )
                out.write(le(20).putInt(0x07064b50).putInt(0).putLong(zip64EndOffset)
                    .putInt(1).array())
            }
            out.write(
                le(22).putInt(0x06054b50).putShort(0).putShort(0)
                    .putShort(count.toShort()).putShort(count.toShort())
                    .putInt(if (zip64End) -1 else cd.size)
                    .putInt(if (zip64End) -1 else cdOffset.toInt())
                    .putShort(0).array()
            )
            file.writeBytes(out.toByteArray())
        }

        private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun assertLocates(file: File, name: String = "payload.bin") {
        val entry = ZipEntryLocator.locateStored(file, name)
        assertEquals(payload.size.toLong(), entry.size)
        val bytes = ByteArray(payload.size)
        RandomAccessFile(file, "r").use {
            it.seek(entry.dataOffset)
            it.readFully(bytes)
        }
        assertArrayEquals(payload, bytes)
    }

    @Test
    fun findsStoredEntryWrittenByZipOutputStream() {
        val file = tmp.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/com/android/metadata"))
            zip.write("ota-type=AB".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload.bin").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                crc = CRC32().apply { update(payload) }.value
            })
            zip.write(payload)
            zip.closeEntry()
        }
        assertLocates(file)
    }

    @Test
    fun usesLocalExtraFieldNotCentralOne() {
        // Alignment padding only in the local header, as zipalign and signapk write it
        val file = tmp.newFile()
        RawZip().apply {
            add("META-INF/com/android/metadata", "ota-type=AB".toByteArray(),
                localExtra = ByteArray(7))
            add("payload.bin", payload, localExtra = ByteArray(13))
        }.write(file)
        assertLocates(file)
    }

    @Test
    fun skipsEntriesWithDataDescriptors() {
        val file = tmp.newFile()
        RawZip().apply {
            add("META-INF/com/android/otacert", ByteArray(300) { 7 }, method = 8,
                dataDescriptor = true)
            add("payload.bin", payload)
        }.write(file)
        assertLocates(file)
    }

    @Test
    fun readsZip64Offsets() {
        val file = tmp.newFile()
        RawZip().apply {
            add("care_map.pb", ByteArray(100))
            add("payload.bin", payload, zip64Offset = true)
        }.write(file, zip64End = true)
        assertLocates(file)
    }

    @Test
    fun rejectsCompressedEntry() {
        val file = tmp.newFile()
        RawZip().apply { add("payload.bin", payload, method = 8) }.write(file)
        assertThrows(IOException::class.java) { ZipEntryLocator.locateStored(file, "payload.bin") }
    }

    @Test
    fun rejectsMissingEntryAndNonZip() {
        val zip = tmp.newFile()
        RawZip().apply { add("payload_properties.txt", "FILE_HASH=x".toByteArray()) }.write(zip)
        assertThrows(IOException::class.java) { ZipEntryLocator.locateStored(zip, "payload.bin") }

        val html = tmp.newFile().apply { writeText("<html>not a zip</html>") }
        assertThrows(IOException::class.java) { ZipEntryLocator.locateStored(html, "payload.bin") }
    }
}
