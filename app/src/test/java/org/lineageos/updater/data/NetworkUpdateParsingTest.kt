/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.updater.data.source.network.toUpdate
import org.lineageos.updater.util.UpdateParser

class NetworkUpdateParsingTest {

    @Test
    fun testParseUserPeridotJson() {
        val json = """
            {
              "response": [
                {
                  "maintainer": "Yuki",
                  "oem": "Xiaomi",
                  "device": "peridot",
                  "filename": "Lunaris-AOSP-peridot-Community-3.9-GMS-2026042812.zip",
                  "download": "https://sourceforge.net/projects/yukiverse/files/luna/peridot/Lunaris-AOSP-peridot-Community-3.9-GMS-2026042812.zip/download",
                  "timestamp": 1777378735,
                  "md5": "31e3ebbe5caedfeb16d5bfe1fb22df85",
                  "sha256": "76e5689dbba845a2fb713d9a1d768be52c6bced29edaed5de296bbb9f86dcd2a",
                  "size": 4026617866,
                  "version": "3.9",
                  "buildtype": "user",
                  "forum": "",
                  "gapps": "",
                  "firmware": "https://xmfirmwareupdater.com/archive/firmware/peridot/",
                  "modem": "",
                  "bootloader": "",
                  "recovery": "https://sourceforge.net/projects/ghosuto/files/Peridot/recovery/recovery.img/download",
                  "paypal": "",
                  "telegram": "https://t.me/+aVq_TSmHq0FhY2Y1",
                  "dt": "",
                  "common-dt": "",
                  "kernel": ""
                }
              ]
            }
        """.trimIndent()

        val updates = UpdateParser.parseUpdates(json)

        assertEquals(1, updates.size)
        val raw = updates[0]
        assertEquals("Yuki", raw.maintainer)
        assertEquals("peridot", raw.device)
        assertEquals("Lunaris-AOSP-peridot-Community-3.9-GMS-2026042812.zip", raw.filename)
        assertEquals("3.9", raw.version)
        assertEquals(1777378735L, raw.timestamp)
        assertEquals(4026617866L, raw.size)

        val update = raw.toUpdate(fallbackSdkLevel = 36)
        assertEquals("76e5689dbba845a2fb713d9a1d768be52c6bced29edaed5de296bbb9f86dcd2a", update.downloadId)
        assertEquals("3.9", update.version)
        assertEquals(36, update.osSdkLevel)
        assertNotNull(update.downloadUrl)
    }

    @Test
    fun testParseRootArrayJson() {
        val json = """
            [
              {
                "filename": "Lunaris-AOSP-peridot-Community-3.12.1-GMS.zip",
                "download": "https://sourceforge.net/download",
                "timestamp": 1780000000,
                "version": "3.12.1"
              }
            ]
        """.trimIndent()

        val updates = UpdateParser.parseUpdates(json)

        assertEquals(1, updates.size)
        val update = updates[0].toUpdate(fallbackSdkLevel = 36)
        assertEquals("3.12.1", update.version)
        assertEquals(1780000000L, update.timestamp)
        assertEquals(36, update.osSdkLevel)
    }

    @Test
    fun testPrepareTargetUrl() {
        val githubBlob = "https://github.com/Inventor365/evolution-peridot/blob/luna/peridot.json"
        val normalized = UpdateParser.prepareTargetUrl(githubBlob)
        assertTrue(normalized.startsWith("https://raw.githubusercontent.com/Inventor365/evolution-peridot/luna/peridot.json"))
        assertTrue(normalized.contains("_nocache="))
    }
}
