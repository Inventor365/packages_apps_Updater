/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.updater.util.SecurityPatch.Check

class SecurityPatchTest {
    @Test
    fun sameOrNewerSecurityPatchIsFine() {
        assertEquals(Check.OK, SecurityPatch.check("2026-09-01", "2026-09-01", "2026-09-01"))
        assertEquals(Check.OK, SecurityPatch.check("2026-10-01", "2026-09-01", "2026-09-01"))
    }

    @Test
    fun newerReportedLevelIsNotADowngrade() {
        // The build has 2026-09-01, but the property reports a later patch
        assertEquals(Check.MISREPORTED,
            SecurityPatch.check("2026-09-01", "2026-09-01", "2026-10-05"))
    }

    @Test
    fun olderThanTheBuildIsADowngrade() {
        assertEquals(Check.DOWNGRADE, SecurityPatch.check("2026-08-01", "2026-09-01", "2026-09-01"))
        assertEquals(Check.DOWNGRADE, SecurityPatch.check("2026-08-01", "2026-09-01", "2026-10-05"))
    }

    @Test
    fun fallsBackToTheReportedLevel() {
        assertEquals(Check.DOWNGRADE, SecurityPatch.check("2026-08-01", null, "2026-09-01"))
        assertEquals(Check.OK, SecurityPatch.check("2026-09-01", null, "2026-09-01"))
    }

    @Test
    fun unknownLevelsAreNotChecked() {
        assertEquals(Check.OK, SecurityPatch.check(null, "2026-09-01", "2026-09-01"))
        assertEquals(Check.OK, SecurityPatch.check("", "2026-09-01", "2026-09-01"))
        assertEquals(Check.OK, SecurityPatch.check("2026-09", "2026-09-01", "2026-09-01"))
        assertEquals(Check.OK, SecurityPatch.check("2026-09-01", null, null))
    }
}
