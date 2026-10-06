/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.ota.nano.OtaPackageMetadata.OtaMetadata
import org.lineageos.updater.misc.Constants
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Reads the OTA metadata of a package and validates that it is something the installer can
 * apply. Values are never invented: a package without usable metadata is rejected.
 */
class OtaMetadataParser @Throws(IOException::class) constructor(file: File) {
    val sdkLevel: Int
    val securityPatchLevel: String
    val timestamp: Long
    val isABUpdate: Boolean

    /** Devices the package is built for (ro.product.device values); empty if not stated. */
    val preDevices: List<String>

    init {
        val zipFile = try {
            ZipFile(file)
        } catch (e: IOException) {
            throw IOException("Not a valid zip archive: ${file.name}", e)
        }

        zipFile.use { zip ->
            val metadata = readProtoMetadata(zip) ?: readTextMetadata(zip)
                ?: throw IOException("No OTA metadata in ${file.name}")
            isABUpdate = metadata.isABUpdate
            sdkLevel = metadata.sdkLevel
            securityPatchLevel = metadata.securityPatchLevel
            timestamp = metadata.timestamp
            preDevices = metadata.preDevices

            if (timestamp <= 0) {
                throw IOException("OTA metadata of ${file.name} has no post-timestamp")
            }
            if (isABUpdate) {
                for (entry in listOf(Constants.AB_PAYLOAD_BIN_PATH,
                        Constants.AB_PAYLOAD_PROPERTIES_PATH)) {
                    if (zip.getEntry(entry) == null) {
                        throw IOException("A/B package ${file.name} is missing $entry")
                    }
                }
            }
        }
    }

    private class Metadata(
        val isABUpdate: Boolean,
        val sdkLevel: Int,
        val securityPatchLevel: String,
        val timestamp: Long,
        val preDevices: List<String>,
    )

    companion object {
        private const val METADATA_PROTO_NAME = "META-INF/com/android/metadata.pb"
        private const val METADATA_TEXT_NAME = "META-INF/com/android/metadata"

        private fun isABType(type: String) = when (type) {
            "AB" -> true
            "BLOCK" -> false
            else -> throw IOException("Unsupported OTA type: $type")
        }

        @Throws(IOException::class)
        private fun readProtoMetadata(zip: ZipFile): Metadata? {
            val entry = zip.getEntry(METADATA_PROTO_NAME) ?: return null
            val metadata = zip.getInputStream(entry).use { OtaMetadata.parseFrom(it.readBytes()) }
            val postcondition = metadata.postcondition
                ?: throw IOException("$METADATA_PROTO_NAME has no postcondition")
            return Metadata(
                isABUpdate = when (metadata.type) {
                    OtaMetadata.AB -> true
                    OtaMetadata.BLOCK -> false
                    else -> throw IOException("Unsupported OTA type: ${metadata.type}")
                },
                sdkLevel = postcondition.sdkLevel.toInt(),
                securityPatchLevel = postcondition.securityPatchLevel ?: "",
                timestamp = postcondition.timestamp,
                preDevices = metadata.precondition?.device?.filter { it.isNotBlank() }.orEmpty(),
            )
        }

        /** Legacy key=value metadata, used by packages that predate metadata.pb. */
        @Throws(IOException::class)
        private fun readTextMetadata(zip: ZipFile): Metadata? {
            val entry = zip.getEntry(METADATA_TEXT_NAME) ?: return null
            val values = zip.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    line.split('=', limit = 2).takeIf { it.size == 2 }
                        ?.let { (key, value) -> key.trim() to value.trim() }
                }.toMap()
            }
            return Metadata(
                isABUpdate = isABType(values["ota-type"]
                    ?: throw IOException("$METADATA_TEXT_NAME has no ota-type")),
                sdkLevel = values["post-sdk-level"]?.toIntOrNull() ?: 0,
                securityPatchLevel = values["post-security-patch-level"] ?: "",
                timestamp = values["post-timestamp"]?.toLongOrNull() ?: 0,
                preDevices = values["pre-device"]?.split(',')?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }.orEmpty(),
            )
        }
    }
}
