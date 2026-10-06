/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.BitSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class ParallelDownloadClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<FileServer>()

    @After
    fun stopServers() = servers.forEach { it.stop() }

    private fun data(size: Int) = Random(size).nextBytes(size)

    /** Serves one file like a SourceForge mirror: redirect, signed link, byte ranges. */
    private inner class FileServer(
        val data: ByteArray,
        val ranges: Boolean = true,
        /** Chance that a ranged response is cut off halfway. */
        val dropChance: Double = 0.0,
        /** Bytes per second per connection; 0 for unlimited. */
        val rate: Int = 0,
        /** Ranged requests a link serves before it expires; 0 for never. */
        val linkLifetime: Int = 0,
    ) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val served = AtomicLong()
        val token = AtomicInteger(1)
        /** Byte ranges asked for, start to end inclusive. */
        val requestedRanges: MutableList<LongRange> = java.util.Collections.synchronizedList(mutableListOf())
        private val requestsWithToken = AtomicInteger()
        private val random = Random(42)

        val port get() = server.address.port
        val startUrl get() = "http://127.0.0.1:$port/start"
        fun fileUrl(token: Int = this.token.get()) = URL("http://127.0.0.1:$port/file?t=$token")

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/start") { exchange ->
                exchange.responseHeaders.add("Location", fileUrl().toString())
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/file") { exchange -> serve(exchange) }
            server.start()
            servers += this
        }

        fun stop() = server.stop(0)

        private fun serve(exchange: HttpExchange) {
            val requested = exchange.requestURI.query?.removePrefix("t=")?.toIntOrNull()
            val range = exchange.requestHeaders.getFirst("Range")
                ?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) }
            if (linkLifetime > 0 && range != null) {
                if (requested != token.get()) {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                    return
                }
                if (requestsWithToken.incrementAndGet() > linkLifetime) {
                    // The link expires; the next resolve hands out a new one
                    requestsWithToken.set(0)
                    token.incrementAndGet()
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                    return
                }
            }
            val start: Int
            val end: Int
            if (ranges && range != null) {
                start = range.groupValues[1].toInt()
                end = range.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
                    ?.coerceAtMost(data.size - 1) ?: (data.size - 1)
                requestedRanges.add(start.toLong()..end.toLong())
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
            } else {
                start = 0
                end = data.size - 1
                exchange.sendResponseHeaders(200, data.size.toLong())
            }
            val drop = range != null && synchronized(random) { random.nextDouble() } < dropChance
            val stopAt = if (drop) start + (end - start + 1) / 2 else end + 1
            try {
                exchange.responseBody.use { out ->
                    var position = start
                    while (position < stopAt) {
                        val n = minOf(16 * 1024, stopAt - position)
                        out.write(data, position, n)
                        position += n
                        served.addAndGet(n.toLong())
                        if (rate > 0) Thread.sleep(n * 1000L / rate)
                    }
                    if (drop) throw java.io.IOException("dropped")
                }
            } catch (_: java.io.IOException) {
                // Client went away, or the connection is dropped on purpose
            } finally {
                exchange.close()
            }
        }
    }

    private class Result {
        val done = CountDownLatch(1)
        @Volatile var success = false
        @Volatile var cancelled = false
        @Volatile var lastBytes = 0L
        @Volatile var lastTotal = 0L
        val reportedSize = AtomicLong(-1)
    }

    private fun client(
        server: FileServer,
        destination: File,
        result: Result,
        mirrors: (URL) -> List<URL> = { listOf(it) },
        connections: Int = 4,
    ) = ParallelDownloadClient(
        server.startUrl, destination,
        { bytes, total, _, _ ->
            result.lastBytes = bytes
            result.lastTotal = total
        },
        object : DownloadClient.DownloadCallback {
            override fun onResponse(headers: DownloadClient.Headers) {
                headers.get("Content-Length")?.let { result.reportedSize.set(it.toLong()) }
            }

            override fun onSuccess() {
                result.success = true
                result.done.countDown()
            }

            override fun onFailure(cancelled: Boolean) {
                result.cancelled = cancelled
                result.done.countDown()
            }
        },
        false,
        ParallelDownloadClient.Config(
            connections = connections,
            blockSize = BLOCK,
            runBlocks = 4,
            probeBytes = 16 * 1024,
            probeTimeoutMs = 500,
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000,
            retryDelayMs = 10,
            mirrorCandidates = mirrors,
        ),
    )

    private fun download(client: ParallelDownloadClient, result: Result, resume: Boolean = false) {
        if (resume) client.resume() else client.start()
        assertTrue("download didn't finish", result.done.await(60, TimeUnit.SECONDS))
    }

    private fun assertComplete(destination: File, data: ByteArray, result: Result) {
        assertTrue("download failed", result.success)
        assertArrayEquals(data, destination.readBytes())
        assertFalse(PartialDownload.dataFile(destination).exists())
        assertFalse(PartialDownload.mapFile(destination).exists())
        assertEquals(data.size.toLong(), result.lastBytes)
        assertEquals(data.size.toLong(), result.reportedSize.get())
    }

    @Test
    fun downloadsInParallelFromTwoMirrors() {
        val data = data(10 * 1024 * 1024 + 123)
        val a = FileServer(data)
        val b = FileServer(data)
        val destination = File(tmp.root, "ota.zip")
        val result = Result()
        val c = client(a, destination, result, mirrors = { listOf(it, b.fileUrl(a.token.get())) })
        download(c, result)
        assertComplete(destination, data, result)
        assertTrue(a.served.get() > 0 && b.served.get() > 0)
    }

    @Test
    fun streamsFromServersWithoutRanges() {
        val data = data(3 * 1024 * 1024)
        val server = FileServer(data, ranges = false)
        val destination = File(tmp.root, "ota.zip")
        val result = Result()
        download(client(server, destination, result), result)
        assertTrue(result.success)
        assertArrayEquals(data, destination.readBytes())
        assertFalse(PartialDownload.dataFile(destination).exists())
    }

    @Test
    fun survivesDroppedConnections() {
        val data = data(6 * 1024 * 1024)
        val server = FileServer(data, dropChance = 0.3)
        val destination = File(tmp.root, "ota.zip")
        val result = Result()
        download(client(server, destination, result), result)
        assertComplete(destination, data, result)
    }

    @Test
    fun resumesWithoutFetchingFinishedBlocksAgain() {
        val data = data(8 * 1024 * 1024)
        val slow = FileServer(data, rate = 2 * 1024 * 1024)
        val destination = File(tmp.root, "ota.zip")
        val first = Result()
        val c = client(slow, destination, first)
        c.start()
        val deadline = System.currentTimeMillis() + 20_000
        while (first.lastBytes < data.size / 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        c.cancel()
        assertTrue(first.done.await(20, TimeUnit.SECONDS))
        assertTrue(first.cancelled)
        assertFalse(destination.exists())
        val map = PartialDownload.readMap(PartialDownload.mapFile(destination))
        assertNotNull(map)
        val doneBytes = map!!.doneBytes()
        assertTrue("nothing recorded as done", doneBytes > 0)
        assertEquals(doneBytes, PartialDownload.downloadedBytes(destination))

        val fast = FileServer(data)
        val second = Result()
        download(client(fast, destination, second), second, resume = true)
        assertComplete(destination, data, second)
        assertNothingFetchedTwice(fast, map)
    }

    @Test
    fun continuesASequentialDownloadOfAnOlderUpdater() {
        val data = data(5 * 1024 * 1024)
        val destination = File(tmp.root, "ota.zip")
        val prefix = 3 * 1024 * 1024 + 1000
        destination.writeBytes(data.copyOf(prefix))
        val server = FileServer(data)
        val result = Result()
        download(client(server, destination, result), result, resume = true)
        assertComplete(destination, data, result)
        val reused = (prefix / BLOCK).toInt()
        assertNothingFetchedTwice(server, PartialDownload.BlockMap(data.size.toLong(), BLOCK,
            BitSet().apply { set(0, reused) }))
    }

    /** No request may ask for a block that was already on disk, past the 1-byte resolve. */
    private fun assertNothingFetchedTwice(server: FileServer, done: PartialDownload.BlockMap) {
        val doneRanges = (0 until done.blockCount).filter { done.done[it] }
            .map { done.blockStart(it) until done.blockEnd(it) }
        for (range in server.requestedRanges.toList()) {
            if (range == 0L..0L) continue
            for (block in doneRanges) {
                assertFalse("$range overlaps finished $block",
                    range.first <= block.last && block.first <= range.last)
            }
        }
    }

    @Test
    fun refreshesExpiredLinks() {
        val data = data(6 * 1024 * 1024)
        val server = FileServer(data, linkLifetime = 5)
        val destination = File(tmp.root, "ota.zip")
        val result = Result()
        download(client(server, destination, result), result)
        assertComplete(destination, data, result)
        assertTrue(server.token.get() > 1)
    }

    @Test
    fun ignoresUnreachableMirrors() {
        val data = data(4 * 1024 * 1024)
        val server = FileServer(data)
        val closedPort = ServerSocket(0).use { it.localPort }
        val destination = File(tmp.root, "ota.zip")
        val result = Result()
        val c = client(server, destination, result,
            mirrors = { listOf(it, URL("http://127.0.0.1:$closedPort/file")) })
        download(c, result)
        assertComplete(destination, data, result)
    }

    @Test
    fun startsOverWhenTheFileOnTheServerChanged() {
        val data = data(3 * 1024 * 1024)
        val destination = File(tmp.root, "ota.zip")
        // Progress of a package with another size
        PartialDownload.dataFile(destination).writeBytes(ByteArray(1024 * 1024) { 7 })
        PartialDownload.writeMap(PartialDownload.mapFile(destination), PartialDownload.BlockMap(
            2 * 1024 * 1024, BLOCK, BitSet().apply { set(0, 8) }))
        val server = FileServer(data)
        val result = Result()
        download(client(server, destination, result), result, resume = true)
        assertComplete(destination, data, result)
    }

    @Test
    fun blockMapRoundTripsAndRejectsDamage() {
        val file = File(tmp.root, "map")
        val done = BitSet().apply { set(0); set(5); set(9) }
        PartialDownload.writeMap(file, PartialDownload.BlockMap(10 * BLOCK - 5, BLOCK, done))
        val map = PartialDownload.readMap(file)!!
        assertEquals(10 * BLOCK - 5, map.total)
        assertEquals(done, map.done)
        // The last block is short
        assertEquals(3 * BLOCK - 5, map.doneBytes())

        val bytes = file.readBytes()
        bytes[10] = (bytes[10] + 1).toByte()
        file.writeBytes(bytes)
        assertNull(PartialDownload.readMap(file))
    }

    companion object {
        private const val BLOCK = 256L * 1024
        // Mirror probes read a little from the start of the file
        private const val PROBE_ALLOWANCE = 64L * 1024
    }
}
