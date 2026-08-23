/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.deviceinfo

import android.os.Build
import android.os.SystemProperties
import com.android.settingslib.DeviceInfoUtils as SettingsLibDeviceInfoUtils

object DeviceInfoUtils : SettingsLibDeviceInfoUtils() {

    private const val PROP_AB_DEVICE = "ro.build.ab_update"
    private const val PROP_ALLOW_MAJOR_UPGRADES = "lunaris.updater.allow_major_upgrades"
    private const val PROP_BUILD_DATE = "ro.build.date.utc"
    private const val PROP_BUILD_VERSION_LUNARIS = "ro.lunaris.build.version"
    private const val PROP_BUILD_VERSION_LINEAGE = "ro.lineage.build.version"
    private const val PROP_BUILD_VERSION_MOD = "ro.modversion"
    private const val PROP_BUILD_VERSION_DISPLAY = "ro.build.display.id"
    private const val PROP_BUILD_VERSION_INCREMENTAL = "ro.build.version.incremental"

    private const val PROP_DEVICE_NEXT = "ro.updater.next_device"
    private const val PROP_DEVICE_LUNARIS = "ro.lunaris.device"
    private const val PROP_DEVICE_LINEAGE = "ro.lineage.device"
    private const val PROP_DEVICE_PRODUCT = "ro.product.device"
    private const val PROP_PRODUCT_NAME = "ro.product.name"
    private const val PROP_PRODUCT_MODEL = "ro.product.model"

    private const val PROP_UPDATER_ALLOW_DOWNGRADING = "lunaris.updater.allow_downgrading"
    private const val PROP_UPDATE_RECOVERY = "persist.vendor.recovery_update"

    // Read-only
    val androidVersion: String = Build.VERSION.RELEASE

    val buildSecurityPatch: String = Build.VERSION.SECURITY_PATCH

    val sdkLevel: Int = Build.VERSION.SDK_INT

    @JvmStatic
    val buildDateTimestamp: Long = SystemProperties.getLong(PROP_BUILD_DATE, 0)

    @JvmStatic
    val buildVersion: String
        get() {
            val v = SystemProperties.get(PROP_BUILD_VERSION_LUNARIS)
            if (v.isNotEmpty()) return v
            val l = SystemProperties.get(PROP_BUILD_VERSION_LINEAGE)
            if (l.isNotEmpty()) return l
            val m = SystemProperties.get(PROP_BUILD_VERSION_MOD)
            if (m.isNotEmpty()) return m
            val d = SystemProperties.get(PROP_BUILD_VERSION_DISPLAY)
            if (d.isNotEmpty()) return d
            return SystemProperties.get(PROP_BUILD_VERSION_INCREMENTAL, "")
        }

    @JvmStatic
    val device: String
        get() {
            val next = SystemProperties.get(PROP_DEVICE_NEXT)
            if (next.isNotEmpty()) return next
            val lunaris = SystemProperties.get(PROP_DEVICE_LUNARIS)
            if (lunaris.isNotEmpty()) return lunaris
            val lineage = SystemProperties.get(PROP_DEVICE_LINEAGE)
            if (lineage.isNotEmpty()) return lineage
            val prod = SystemProperties.get(PROP_DEVICE_PRODUCT)
            if (prod.isNotEmpty()) return prod
            return Build.DEVICE ?: ""
        }

    @JvmStatic
    val productName: String
        get() {
            val name = SystemProperties.get(PROP_PRODUCT_NAME)
            if (name.isNotEmpty()) return name
            val model = SystemProperties.get(PROP_PRODUCT_MODEL)
            if (model.isNotEmpty()) return model
            return Build.MODEL ?: ""
        }

    @JvmStatic
    val isABDevice: Boolean = SystemProperties.getBoolean(PROP_AB_DEVICE, false)

    // Mutable at runtime
    @JvmStatic
    val isDowngradingAllowed: Boolean
        get() = SystemProperties.getBoolean(PROP_UPDATER_ALLOW_DOWNGRADING, false) ||
                SystemProperties.getBoolean("lineage.updater.allow_downgrading", false)

    @JvmStatic
    val isMajorUpdateAllowed: Boolean
        get() = SystemProperties.getBoolean(PROP_ALLOW_MAJOR_UPGRADES, true)

    @JvmStatic
    var isRecoveryUpdateEnabled: Boolean
        get() = SystemProperties.getBoolean(PROP_UPDATE_RECOVERY, false)
        set(value) = SystemProperties.set(PROP_UPDATE_RECOVERY, value.toString())
}
