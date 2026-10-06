/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.os.SystemProperties
import java.io.File

/**
 * Tells a real security patch downgrade from one that only looks like it.
 *
 * update_engine refuses (or, on an unlocked bootloader, factory resets for) an update whose
 * security patch is older than ro.build.version.security_patch. That property can report a
 * newer level than the build has, e.g. to pass integrity checks, which makes every update of
 * that build look like a downgrade. The level the build was made with is in /system/build.prop.
 */
object SecurityPatch {
    /** Payload property that makes update_engine skip its own security patch check. */
    const val SPL_DOWNGRADE_HEADER = "SPL_DOWNGRADE=1"

    private const val PROPERTY = "ro.build.version.security_patch"
    private val LEVEL = Regex("""\d{4}-\d{2}-\d{2}""")

    enum class Check {
        OK,

        /** The update has an older security patch than the installed build. */
        DOWNGRADE,

        /**
         * Not a downgrade, but update_engine would take it for one: the reported level is
         * newer than the installed build's.
         */
        MISREPORTED,
    }

    /**
     * @param packageLevel security patch of the update
     * @param installedLevel the installed build's, from its build.prop
     * @param reportedLevel ro.build.version.security_patch, which update_engine checks
     */
    @JvmStatic
    fun check(packageLevel: String?, installedLevel: String?, reportedLevel: String?): Check {
        val update = packageLevel?.takeIf { LEVEL.matches(it) } ?: return Check.OK
        val reported = reportedLevel?.takeIf { LEVEL.matches(it) }
        val installed = installedLevel?.takeIf { LEVEL.matches(it) } ?: reported
            ?: return Check.OK
        return when {
            update < installed -> Check.DOWNGRADE
            reported != null && update < reported -> Check.MISREPORTED
            else -> Check.OK
        }
    }

    @JvmStatic
    fun check(packageLevel: String?) = check(packageLevel, installedLevel, reportedLevel())

    /** The installed build's security patch, as written in /system/build.prop. */
    @JvmStatic
    val installedLevel: String? by lazy {
        runCatching {
            File("/system/build.prop").useLines { lines ->
                lines.firstOrNull { it.startsWith("$PROPERTY=") }?.substringAfter('=')?.trim()
            }
        }.getOrNull()
    }

    @JvmStatic
    fun reportedLevel(): String? = runCatching { SystemProperties.get(PROPERTY) }.getOrNull()
}
