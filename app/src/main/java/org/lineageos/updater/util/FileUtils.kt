/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import android.os.FileUtils as OsFileUtils

object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * Copies a local file and removes the incomplete destination if the copy fails.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(sourceFile: File, destFile: File) {
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    OsFileUtils.copy(input, output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not copy file", e)
            destFile.delete()
            throw e
        }
    }

    /**
     * Copies a local file into a content URI opened for writing.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(cr: ContentResolver, sourceFile: File, destUri: Uri) {
        try {
            FileInputStream(sourceFile).use { input ->
                cr.openFileDescriptor(destUri, "w")!!.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        OsFileUtils.copy(input, output)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not copy file", e)
            throw e
        }
    }

    /**
     * Returns the display name advertised by a content URI.
     */
    @JvmStatic
    fun queryName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Computes the cryptographic hash (SHA-256, MD5, SHA-1) of a file.
     */
    @JvmStatic
    fun calculateHash(file: File, algorithm: String): String? = try {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to compute $algorithm hash for $file", e)
        null
    }

    /**
     * Checks if a file is a valid, uncorrupted zip archive.
     */
    @JvmStatic
    fun isZipValid(file: File): Boolean = try {
        ZipFile(file).use { zip ->
            zip.size() > 0
        }
    } catch (e: Exception) {
        Log.e(TAG, "Zip file $file is corrupted or invalid", e)
        false
    }

    /**
     * Verifies package integrity:
     * 1. Checks zip archive structure (ensures file is not corrupted or truncated).
     * 2. If a hash is provided (SHA-256 or MD5), verifies that the file matches the expected hash.
     * Bypasses strict OEM cryptographic signature verification so custom/unofficial builds work.
     */
    @JvmStatic
    fun verifyPackageIntegrity(file: File, expectedHash: String?): Boolean {
        if (!file.exists() || !isZipValid(file)) {
            Log.e(TAG, "Package is missing or not a valid zip archive: $file")
            return false
        }

        if (expectedHash.isNullOrBlank() || expectedHash.equals("local", ignoreCase = true)) {
            Log.d(TAG, "Package zip integrity verified (local or unhashed update): ${file.name}")
            return true
        }

        val cleanExpected = expectedHash.trim().lowercase()
        val algorithm = when (cleanExpected.length) {
            64 -> "SHA-256"
            32 -> "MD5"
            40 -> "SHA-1"
            else -> null
        }

        if (algorithm == null) {
            Log.d(TAG, "Expected identifier is not a hash, zip integrity verified: ${file.name}")
            return true
        }

        val computedHash = calculateHash(file, algorithm)?.lowercase()
        val matches = computedHash == cleanExpected
        if (!matches) {
            Log.e(TAG, "$algorithm hash mismatch for ${file.name}: expected $cleanExpected, computed $computedHash")
        } else {
            Log.d(TAG, "$algorithm hash verified successfully for ${file.name}")
        }
        return matches
    }
}
