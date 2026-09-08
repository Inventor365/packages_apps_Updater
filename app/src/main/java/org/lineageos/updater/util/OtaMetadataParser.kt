/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.ota.nano.OtaPackageMetadata.OtaMetadata
import android.util.Log
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class OtaMetadataParser @Throws(IOException::class) constructor(file: File) {
    var sdkLevel: Int = 0
        private set
    var securityPatchLevel: String = ""
        private set
    var timestamp: Long = 0L
        private set
    var isABUpdate: Boolean = false
        private set

    init {
        // Step 1: Ensure the zip file is not corrupted or truncated
        val zipFile = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw IOException("Corrupted update package: cannot open zip archive ${file.name}", e)
        }

        zipFile.use { zip ->
            var parsedSuccessfully = false

            // Step 2: Try modern protobuf metadata (META-INF/com/android/metadata.pb)
            try {
                val pbEntry = zip.getEntry(METADATA_PROTO_NAME)
                if (pbEntry != null) {
                    val metadata = zip.getInputStream(pbEntry).use { input ->
                        OtaMetadata.parseFrom(input.readBytes())
                    }
                    isABUpdate = (metadata.type == OtaMetadata.AB)
                    val postcondition = metadata.postcondition
                    if (postcondition != null) {
                        sdkLevel = postcondition.sdkLevel.toInt()
                        securityPatchLevel = postcondition.securityPatchLevel ?: ""
                        timestamp = postcondition.timestamp
                        parsedSuccessfully = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse $METADATA_PROTO_NAME, falling back to text metadata", e)
            }

            // Step 3: Try legacy text metadata (META-INF/com/android/metadata)
            if (!parsedSuccessfully) {
                try {
                    val textEntry = zip.getEntry(METADATA_TEXT_NAME)
                    if (textEntry != null) {
                        zip.getInputStream(textEntry).bufferedReader().useLines { lines ->
                            for (line in lines) {
                                val trimmed = line.trim()
                                when {
                                    trimmed.startsWith("post-timestamp=") ->
                                        timestamp = trimmed.substringAfter("=").toLongOrNull() ?: timestamp
                                    trimmed.startsWith("post-sdk-level=") ->
                                        sdkLevel = trimmed.substringAfter("=").toIntOrNull() ?: sdkLevel
                                    trimmed.startsWith("ota-type=") ->
                                        isABUpdate = trimmed.substringAfter("=").equals("AB", ignoreCase = true)
                                    trimmed.startsWith("post-security-patch-level=") ->
                                        securityPatchLevel = trimmed.substringAfter("=")
                                }
                            }
                        }
                        parsedSuccessfully = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse $METADATA_TEXT_NAME", e)
                }
            }

            // Step 4: Robust fallbacks for custom/unofficial zips so import NEVER fails on valid zips
            if (sdkLevel <= 0) {
                sdkLevel = DeviceInfoUtils.sdkLevel
            }
            if (securityPatchLevel.isBlank()) {
                securityPatchLevel = DeviceInfoUtils.buildSecurityPatch
            }
            if (timestamp <= 0L) {
                timestamp = VersionUtils.extractTimestampFromFilename(file.name)
                    ?: (file.lastModified() / 1000L)
            }
            if (!isABUpdate) {
                isABUpdate = zip.getEntry("payload.bin") != null
            }
        }
    }

    companion object {
        private const val TAG = "OtaMetadataParser"
        private const val METADATA_PROTO_NAME = "META-INF/com/android/metadata.pb"
        private const val METADATA_TEXT_NAME = "META-INF/com/android/metadata"
    }
}
