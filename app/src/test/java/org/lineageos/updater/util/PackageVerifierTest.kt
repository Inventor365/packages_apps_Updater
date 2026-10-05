/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lineageos.updater.data.Update
import org.lineageos.updater.util.PackageVerifier.Failure
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the checks that don't need the platform: size, structure, metadata and SHA-256.
 * The signature check uses RecoverySystem and is exercised on device.
 */
class PackageVerifierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val abMetadata = """
        ota-type=AB
        post-timestamp=1791120017
        post-sdk-level=36
        post-security-patch-level=2026-09-01
    """.trimIndent()

    private fun otaZip(
        metadata: String? = abMetadata,
        entries: List<String> = listOf("payload.bin", "payload_properties.txt"),
    ): File {
        val file = tmp.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            if (metadata != null) {
                zip.putNextEntry(ZipEntry("META-INF/com/android/metadata"))
                zip.write(metadata.toByteArray())
                zip.closeEntry()
            }
            for (name in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun verify(file: File?, expectedSize: Long = 0, expectedSha256: String? = null) =
        PackageVerifier.verify(file, expectedSize, expectedSha256, checkSignature = false)

    @Test
    fun completePackageWithMatchingHashIsVerified() {
        val file = otaZip()
        val result = verify(file, file.length(), sha256(file))
        assertTrue(result.detail, result.isVerified)
    }

    @Test
    fun hashComparisonIgnoresCase() {
        val file = otaZip()
        assertTrue(verify(file, file.length(), sha256(file).uppercase()).isVerified)
    }

    @Test
    fun localPackageWithoutPublishedMetadataIsVerifiedStructurally() {
        assertTrue(verify(otaZip()).isVerified)
    }

    @Test
    fun missingPackageFails() {
        assertEquals(Failure.MISSING, verify(null).failure)
        assertEquals(Failure.MISSING, verify(File(tmp.root, "absent.zip")).failure)
    }

    @Test
    fun shortPackageIsIncompleteAndKeptForResuming() {
        val file = otaZip()
        val failure = verify(file, file.length() + 1, sha256(file)).failure
        assertEquals(Failure.INCOMPLETE, failure)
        assertTrue(failure!!.keepsPackage)
    }

    @Test
    fun oversizedPackageIsCorrupt() {
        val file = otaZip()
        val failure = verify(file, file.length() - 1).failure
        assertEquals(Failure.CORRUPT, failure)
        assertFalse(failure!!.keepsPackage)
    }

    @Test
    fun hashMismatchFails() {
        val file = otaZip()
        assertEquals(Failure.HASH_MISMATCH, verify(file, file.length(), "0".repeat(64)).failure)
    }

    @Test
    fun nonZipIsCorrupt() {
        val file = tmp.newFile().apply { writeText("<html>not a package</html>") }
        assertEquals(Failure.CORRUPT, verify(file).failure)
    }

    @Test
    fun zipWithoutOtaMetadataIsCorrupt() {
        assertEquals(Failure.CORRUPT, verify(otaZip(metadata = null)).failure)
    }

    @Test
    fun metadataWithoutTimestampIsCorrupt() {
        assertEquals(Failure.CORRUPT, verify(otaZip(metadata = "ota-type=AB")).failure)
    }

    @Test
    fun abPackageWithoutPayloadIsCorrupt() {
        assertEquals(
            Failure.CORRUPT,
            verify(otaZip(entries = listOf("payload_properties.txt"))).failure,
        )
    }

    @Test
    fun metadataIsReadWithoutInventingValues() {
        val metadata = OtaMetadataParser(otaZip())
        assertTrue(metadata.isABUpdate)
        assertEquals(1791120017L, metadata.timestamp)
        assertEquals(36, metadata.sdkLevel)
        assertEquals("2026-09-01", metadata.securityPatchLevel)
    }

    @Test
    fun expectedSha256OnlyComesFromSha256DownloadIds() {
        val sha = "3C0C500422324428B27E5DF89FB1C2FC2088DB907C190162955F02376896B27A"
        assertEquals(sha.lowercase(), Update(downloadId = sha, osSdkLevel = 0).expectedSha256)
        assertNull(Update(downloadId = Update.LOCAL_ID, osSdkLevel = 0).expectedSha256)
        assertNull(Update(downloadId = "639df74d8f5cc8ab7f5af7c9e1003d51", osSdkLevel = 0)
            .expectedSha256)
    }
}
