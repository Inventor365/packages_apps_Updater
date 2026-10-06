/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Downloads a real package from SourceForge, once over a single connection (for a while) and
 * once in full with the parallel client. Only runs when LUNARIS_SF_BENCH points to a scratch
 * directory with room for the package; the package details come from the environment too:
 *
 *   LUNARIS_SF_BENCH=/tmp/bench LUNARIS_SF_URL=... LUNARIS_SF_SHA256=... ./gradlew testDebugUnitTest
 */
class SourceForgeDownloadBenchmark {
    private val dir = System.getenv("LUNARIS_SF_BENCH")?.let(::File)
    private val url = System.getenv("LUNARIS_SF_URL")
    private val sha256 = System.getenv("LUNARIS_SF_SHA256")

    private class Run(val client: ParallelDownloadClient, val done: CountDownLatch) {
        @Volatile var bytes = 0L
        @Volatile var success = false
    }

    private fun run(destination: File, config: ParallelDownloadClient.Config): Run {
        val done = CountDownLatch(1)
        lateinit var run: Run
        val client = ParallelDownloadClient(url!!, destination,
            { bytes, total, speed, eta ->
                run.bytes = bytes
                val now = System.nanoTime()
                if (now - lastPrint > 10_000_000_000L) {
                    lastPrint = now
                    println("  ${bytes / 1048576} / ${total / 1048576} MiB, " +
                            "${speed / 1048576} MiB/s, eta ${eta}s")
                }
            },
            object : DownloadClient.DownloadCallback {
                override fun onResponse(headers: DownloadClient.Headers) {}
                override fun onSuccess() {
                    run.success = true
                    done.countDown()
                }

                override fun onFailure(cancelled: Boolean) = done.countDown()
            },
            false, config)
        run = Run(client, done)
        return run
    }

    @Volatile
    private var lastPrint = 0L

    @Test
    fun singleVersusParallel() {
        assumeTrue(dir != null && url != null && sha256 != null)
        dir!!.mkdirs()

        val single = File(dir, "single.zip")
        PartialDownload.deleteQuietly(single)
        val one = run(single, ParallelDownloadClient.Config(connections = 1,
            mirrorCandidates = { listOf(it) }))
        val start1 = System.nanoTime()
        one.client.start()
        one.done.await(25, TimeUnit.SECONDS)
        one.client.cancel()
        one.done.await(30, TimeUnit.SECONDS)
        val singleRate = one.bytes / ((System.nanoTime() - start1) / 1e9)
        println("One connection: ${"%.2f".format(singleRate / 1048576)} MiB/s")
        PartialDownload.deleteQuietly(single)

        val parallel = File(dir, "parallel.zip")
        PartialDownload.deleteQuietly(parallel)
        val many = run(parallel, ParallelDownloadClient.Config())
        val start2 = System.nanoTime()
        many.client.start()
        assertTrue(many.done.await(2, TimeUnit.HOURS))
        val seconds = (System.nanoTime() - start2) / 1e9
        assertTrue("parallel download failed", many.success)
        println("Parallel: ${parallel.length() / 1048576} MiB in ${"%.0f".format(seconds)} s, " +
                "${"%.2f".format(parallel.length() / seconds / 1048576)} MiB/s")

        val digest = MessageDigest.getInstance("SHA-256")
        parallel.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        assertEquals(sha256, digest.digest().joinToString("") { "%02x".format(it) })
        println("SHA-256 matches")
        parallel.delete()
    }
}
