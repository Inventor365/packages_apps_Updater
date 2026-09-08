/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.lineageos.updater.data.Update
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import java.io.File

object InstallUtils {
    @JvmStatic
    fun isScratchMounted() = runCatching {
        File("/proc/mounts").useLines { lines ->
            lines.any { it.split(" ")[1] == "/mnt/scratch" }
        }
    }.getOrDefault(false)

    enum class BlockedReason {
        NONE, DOWNGRADE, VERSION_UNSUPPORTED
    }

    @JvmStatic
    fun getBlockedReason(update: Update): BlockedReason {
        if (DeviceInfoUtils.isDowngradingAllowed || update.downloadId == Update.LOCAL_ID) {
            return BlockedReason.NONE
        }

        val currentVersion = DeviceInfoUtils.buildVersion
        val currentTimestamp = DeviceInfoUtils.buildDateTimestamp

        val hasCurrentVersion = currentVersion.isNotBlank()
        val hasUpdateVersion = update.version.isNotBlank()

        val isNewerVer = hasCurrentVersion && hasUpdateVersion &&
                VersionUtils.isNewer(update.version, currentVersion)
        val isOlderVer = hasCurrentVersion && hasUpdateVersion &&
                VersionUtils.isOlder(update.version, currentVersion)

        val hasCurrentTime = currentTimestamp > 0
        val hasUpdateTime = update.timestamp > 0

        val isNewerTime = hasCurrentTime && hasUpdateTime && update.timestamp > currentTimestamp
        val isOlderTime = hasCurrentTime && hasUpdateTime && update.timestamp < currentTimestamp

        val isSdkDowngrade = update.osSdkLevel in 1 until DeviceInfoUtils.sdkLevel && !isNewerVer && !isNewerTime
        val isDowngrade = (isOlderVer && !isNewerTime) || (isOlderTime && !isNewerVer) || isSdkDowngrade

        if (isDowngrade) {
            return BlockedReason.DOWNGRADE
        }

        // Allow major Android and ROM version upgrades unless explicitly blocked by property
        if (!DeviceInfoUtils.isMajorUpdateAllowed && update.osSdkLevel > DeviceInfoUtils.sdkLevel) {
            return BlockedReason.VERSION_UNSUPPORTED
        }

        return BlockedReason.NONE
    }

    @JvmStatic
    fun canInstall(update: Update) = getBlockedReason(update) == BlockedReason.NONE

    @JvmStatic
    fun canStreamUpdate(update: Update, streamUpdatesEnabled: Boolean) =
        DeviceInfoUtils.isABDevice &&
                streamUpdatesEnabled &&
                update.isAvailableOnline &&
                update.hasPayloadFileRanges() &&
                !update.hasFullyDownloadedPackage() &&
                !update.hasVerifiedPackage()
}
