/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalSerializationApi::class)

package org.lineageos.updater.data.source.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import org.lineageos.updater.data.Update
import org.lineageos.updater.util.VersionUtils

@Suppress("PROVIDED_RUNTIME_TOO_LOW")
@Serializable
@JsonIgnoreUnknownKeys
data class NetworkUpdateResponse(
    @SerialName("response") val response: List<NetworkUpdate> = emptyList(),
)

@Suppress("PROVIDED_RUNTIME_TOO_LOW")
@Serializable
@JsonIgnoreUnknownKeys
data class NetworkUpdate(
    @SerialName("filename") val filename: String = "",
    @SerialName("download") val download: String = "",
    @SerialName("timestamp") val timestamp: Long = 0L,
    @SerialName("sha256") val sha256: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("version") val version: String = "",
    @SerialName("md5") val md5: String? = null,
    @SerialName("buildtype") val buildType: String? = null,
    // Lunaris additional metadata
    @SerialName("maintainer") val maintainer: String? = null,
    @SerialName("oem") val oem: String? = null,
    @SerialName("device") val device: String? = null,
    @SerialName("forum") val forum: String? = null,
    @SerialName("gapps") val gapps: String? = null,
    @SerialName("firmware") val firmware: String? = null,
    @SerialName("modem") val modem: String? = null,
    @SerialName("bootloader") val bootloader: String? = null,
    @SerialName("recovery") val recovery: String? = null,
    @SerialName("paypal") val paypal: String? = null,
    @SerialName("telegram") val telegram: String? = null,
    @SerialName("os_patch_level") val osPatchLevel: String? = null,
    @SerialName("os_sdk_level") val osSdkLevel: Int? = null,
    // Upstream LineageOS / alternate fields
    @SerialName("datetime") val datetime: Long? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    // Not used
    @SerialName("ota_property_files") val otaPropertyFiles: String? = null,
)

fun NetworkUpdate.toUpdate(fallbackSdkLevel: Int = 0): Update {
    val finalVersion = version.ifBlank {
        VersionUtils.extractVersionFromFilename(filename) ?: ""
    }
    val finalTimestamp = if (timestamp > 0) timestamp else (datetime ?: 0L)
    val finalDownloadUrl = download.ifBlank { url ?: "" }.trim().takeIf { it.isNotBlank() }
    val finalBuildType = buildType ?: type
    val finalId = sha256.ifBlank {
        md5?.ifBlank { null }
            ?: id?.ifBlank { null }
            ?: filename.ifBlank { finalDownloadUrl?.hashCode()?.toString() ?: "" }
    }

    return Update(
        downloadId = finalId,
        name = filename.ifBlank { "update" },
        timestamp = finalTimestamp,
        type = finalBuildType,
        fileSize = size,
        downloadUrl = finalDownloadUrl,
        version = finalVersion,
        osPatchLevel = osPatchLevel?.takeIf { it.isNotBlank() },
        osSdkLevel = osSdkLevel?.takeIf { it > 0 } ?: fallbackSdkLevel,
        isAvailableOnline = true,
    )
}
