/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SourceForgeDownloadTest {

    @Test
    fun testDownloadFromSourceForgeLinkWithFastMirror() {
        val testUrl = "https://sourceforge.net/projects/yukiverse/files/luna/peridot/Lunaris-AOSP-peridot-Community-3.12-GMS-2026070503.zip/download"
        val tempDest = File.createTempFile("sf_test_", ".zip")
        tempDest.deleteOnExit()

        val responseReceived = AtomicBoolean(false)
        val progressBytes = AtomicLong(0L)
        val latch = CountDownLatch(1)

        val client = DownloadClient.Builder()
            .setUrl(testUrl)
            .setDestination(tempDest)
            .setUseDuplicateLinks(true)
            .setDownloadCallback(object : DownloadClient.DownloadCallback {
                override fun onResponse(headers: DownloadClient.Headers?) {
                    responseReceived.set(true)
                }

                override fun onSuccess() {
                    latch.countDown()
                }

                override fun onFailure(cancelled: Boolean) {
                    latch.countDown()
                }
            })
            .setProgressListener { bytesRead, totalLength, speed, eta ->
                progressBytes.set(bytesRead)
                // Stop download once we verify it connects and reads data
                if (bytesRead > 64 * 1024) {
                    latch.countDown()
                }
            }
            .build()

        client.start()
        val completed = latch.await(20, TimeUnit.SECONDS)
        client.cancel()

        assertTrue("Expected HTTP response headers to be received", responseReceived.get())
        assertTrue("Expected to receive data from mirror (read ${progressBytes.get()} bytes)", progressBytes.get() > 0)
        println("Successfully verified download from SourceForge! Bytes read: ${progressBytes.get()}")

        tempDest.delete()
    }
}
