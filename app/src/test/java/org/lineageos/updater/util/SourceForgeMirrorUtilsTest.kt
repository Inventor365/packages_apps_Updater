/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class SourceForgeMirrorUtilsTest {

    @Test
    fun testIsSourceForgeUrl() {
        assertTrue(SourceForgeMirrorUtils.isSourceForgeUrl("https://sourceforge.net/projects/yukiverse/files/ota.zip/download"))
        assertTrue(SourceForgeMirrorUtils.isSourceForgeUrl("https://downloads.sourceforge.net/project/yukiverse/ota.zip"))
        assertTrue(SourceForgeMirrorUtils.isSourceForgeUrl("https://master.dl.sourceforge.net/project/yukiverse/ota.zip"))
        assertFalse(SourceForgeMirrorUtils.isSourceForgeUrl("https://raw.githubusercontent.com/test/ota.json"))
        assertFalse(SourceForgeMirrorUtils.isSourceForgeUrl("https://github.com/test/ota/releases/download/v1/ota.zip"))
    }

    @Test
    fun testIsSourceForgeDirectMirrorUrl() {
        assertTrue(SourceForgeMirrorUtils.isSourceForgeDirectMirrorUrl(URL("https://master.dl.sourceforge.net/project/abc/file.zip?token=123")))
        assertTrue(SourceForgeMirrorUtils.isSourceForgeDirectMirrorUrl(URL("https://twds.dl.sourceforge.net/project/abc/file.zip?token=123")))
        assertFalse(SourceForgeMirrorUtils.isSourceForgeDirectMirrorUrl(URL("https://downloads.sourceforge.net/project/abc/file.zip")))
        assertFalse(SourceForgeMirrorUtils.isSourceForgeDirectMirrorUrl(URL("https://sourceforge.net/projects/abc/files/file.zip/download")))
    }

    @Test
    fun testGetMirrorCandidateUrls() {
        val original = URL("https://master.dl.sourceforge.net/project/yukiverse/peridot.zip?viasf=1&fid=123&st=abc")
        val candidates = SourceForgeMirrorUtils.getMirrorCandidateUrls(original)

        assertTrue(candidates.isNotEmpty())
        // Candidate URLs should retain path and query parameters
        for (c in candidates) {
            assertEquals("/project/yukiverse/peridot.zip?viasf=1&fid=123&st=abc", c.file)
            assertTrue(c.host.endsWith(".dl.sourceforge.net"))
        }

        // Check hosts include twds and master
        val hosts = candidates.map { it.host }
        assertTrue(hosts.contains("twds.dl.sourceforge.net"))
        assertTrue(hosts.contains("master.dl.sourceforge.net"))
    }

    @Test
    fun testRegionDetection() {
        val region = SourceForgeMirrorUtils.detectUserRegion()
        assertNotNull(region)
    }

    @Test
    fun testSelectFastestMirrorFallback() {
        val c1 = URL("https://twds.dl.sourceforge.net/project/a/b.zip")
        val c2 = URL("https://master.dl.sourceforge.net/project/a/b.zip")
        val selected = SourceForgeMirrorUtils.selectFastestMirror(listOf(c1, c2))
        assertNotNull(selected)
        assertTrue(selected.host.endsWith(".dl.sourceforge.net"))
    }
}
