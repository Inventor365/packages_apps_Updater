/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun testSemanticAndHotfixVersions() {
        // User example: Current is 3.12, hotfix is 3.12.1
        assertTrue(VersionUtils.isNewer("3.12.1", "3.12"))
        assertTrue(VersionUtils.isOlder("3.12", "3.12.1"))
        assertFalse(VersionUtils.isNewer("3.12", "3.12.1"))

        // Hotfix 3.12.2 vs 3.12.1
        assertTrue(VersionUtils.isNewer("3.12.2", "3.12.1"))

        // Minor version 3.13 vs 3.12
        assertTrue(VersionUtils.isNewer("3.13", "3.12"))

        // Major version 4.0 vs 3.12
        assertTrue(VersionUtils.isNewer("4.0", "3.12"))

        // Older version 3.9 vs 3.12
        assertTrue(VersionUtils.isOlder("3.9", "3.12"))
        assertTrue(VersionUtils.isNewer("3.12", "3.9"))

        // Same versions
        assertTrue(VersionUtils.isSame("3.12", "3.12"))
        assertTrue(VersionUtils.isSame("3.12", "3.12.0"))
        assertTrue(VersionUtils.isSame("3.12.0", "3.12"))
        assertFalse(VersionUtils.isNewer("3.12", "3.12"))
        assertFalse(VersionUtils.isOlder("3.12", "3.12"))
    }

    @Test
    fun testHotfixSuffixes() {
        // 3.12-hotfix vs 3.12
        assertTrue(VersionUtils.isNewer("3.12-hotfix", "3.12"))
        // 3.12-hotfix2 vs 3.12-hotfix1
        assertTrue(VersionUtils.isNewer("3.12-hotfix2", "3.12-hotfix1"))
        // 3.12-p1 vs 3.12
        assertTrue(VersionUtils.isNewer("3.12-p1", "3.12"))
    }

    @Test
    fun testPrefixCleaning() {
        assertTrue(VersionUtils.isSame("v3.12", "3.12"))
        assertTrue(VersionUtils.isSame("V3.12", "3.12"))
        assertTrue(VersionUtils.isSame("Lunaris-3.12", "3.12"))
        assertTrue(VersionUtils.isSame("Lunaris-AOSP-3.12", "3.12"))
        assertTrue(VersionUtils.isNewer("v3.12.1", "3.12"))
        assertTrue(VersionUtils.isNewer("Lunaris-3.12.1", "Lunaris-3.12"))
    }

    @Test
    fun testExtractVersionFromFilename() {
        assertEquals(
            "3.12",
            VersionUtils.extractVersionFromFilename("Lunaris-AOSP-peridot-Community-3.12-GMS-2026042812.zip")
        )
        assertEquals(
            "3.12.1",
            VersionUtils.extractVersionFromFilename("Lunaris-AOSP-peridot-Community-3.12.1-GMS-2026042812.zip")
        )
        assertEquals(
            "3.9",
            VersionUtils.extractVersionFromFilename("Lunaris-AOSP-peridot-Community-3.9-GMS-2026042812.zip")
        )
        assertEquals(
            "3.12-hotfix1",
            VersionUtils.extractVersionFromFilename("Lunaris-3.12-hotfix1-peridot.zip")
        )
    }
}
