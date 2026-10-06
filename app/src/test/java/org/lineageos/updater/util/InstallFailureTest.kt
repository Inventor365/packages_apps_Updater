/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallFailureTest {
    @Test
    fun mapsUpdateEngineErrors() {
        assertEquals(InstallFailure.SPACE, InstallFailure.fromErrorCode(60))
        assertEquals(InstallFailure.DOWNGRADE, InstallFailure.fromErrorCode(51))
        // Payload and metadata signatures not matching the device's OTA keys
        assertEquals(InstallFailure.SIGNATURE, InstallFailure.fromErrorCode(12))
        assertEquals(InstallFailure.SIGNATURE, InstallFailure.fromErrorCode(26))
        assertEquals(InstallFailure.PACKAGE, InstallFailure.fromErrorCode(10))
        assertEquals(InstallFailure.POSTINSTALL, InstallFailure.fromErrorCode(5))
        assertEquals(InstallFailure.DEVICE, InstallFailure.fromErrorCode(64))
        assertEquals(InstallFailure.BUSY, InstallFailure.fromErrorCode(65))
        assertEquals(InstallFailure.GENERIC, InstallFailure.fromErrorCode(1))
    }

    @Test
    fun ignoresFlagBits() {
        assertEquals(InstallFailure.SPACE, InstallFailure.fromErrorCode(60 or (1 shl 31)))
        assertTrue(InstallFailure.isUserCanceled(48 or (1 shl 30)))
        assertFalse(InstallFailure.isUserCanceled(1))
    }
}
