/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import android.util.Log
import org.lineageos.updater.util.SourceForgeMirrorUtils
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 1DM / IDM style multi-part parallel download client with SourceForge fast-mirror selection.
 *
 * The package is partitioned into independent parts (`<destination>.part.0`,
 * `<destination>.part.1`, ...), each owned by at most one worker at a time. Workers fetch
 * block-aligned ranges from the fastest mirrors, switch mirrors when theirs falls behind, and
 * dynamically split the largest remaining part into a new `.part.<id>` file when they finish
 * early.
 *
 * Every block is CRC32-checksummed before being committed to its part file, and on pause/resume
 * all existing part files are CRC32-verified and truncated to the last verified block boundary.
 * Only when all parts are 100% complete are they merged and verified in order into
 * `<destination>`.
 */
class ParallelDownloadClient @JvmOverloads internal constructor(
    private val url: String,
    private val destination: File,
    private val progressListener: DownloadClient.ProgressListener?,
    private val callback: DownloadClient.DownloadCallback,
    private val useDuplicateLinks: Boolean,
    private val config: Config = Config(),
) : DownloadClient {

    /** Tunables. The defaults are for multi-GB packages; tests use small values. */
    class Config(
        val connections: Int = 8,
        val blockSize: Long = PartialDownload.BLOCK_SIZE,
        /** Blocks per HTTP range request; a worker can switch to a faster mirror between requests. */
        val runBlocks: Int = 8,
        val probeBytes: Int = 256 * 1024,
        val probeTimeoutMs: Long = 4000,
        val connectTimeoutMs: Int = 15000,
        val readTimeoutMs: Int = 20000,
        /** Consecutive failures after which a worker gives up. */
        val maxWorkerFailures: Int = 6,
        val retryDelayMs: Long = 1000,
        val maxLinkRefreshes: Int = 3,
        val mirrorCandidates: (URL) -> List<URL> = SourceForgeMirrorUtils::getMirrorCandidateUrls,
    )

    private val cancelled = AtomicInteger(0)
    private val connections: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var coordinator: Thread? = null

    override fun start() = launch(resume = false)

    override fun resume() = launch(resume = true)

    @Synchronized
    private fun launch(resume: Boolean) {
        if (coordinator != null) {
            Log.e(TAG, "Already downloading")
            return
        }
        coordinator = Thread({ coordinate(resume) }, "UpdaterDownload").also { it.start() }
    }

    @Synchronized
    override fun cancel() {
        val thread = coordinator ?: run {
            Log.e(TAG, "Not downloading")
            return
        }
        cancelled.set(1)
        thread.interrupt()
        // Unblock reads stuck on sockets. Closing a TLS socket may touch the network, so don't
        // do it on the caller's (UI) thread.
        val open = connections.toList()
        Thread({ open.forEach { it.disconnect() } }, "UpdaterDownloadCancel").start()
    }

    private val isCancelled: Boolean
        get() = cancelled.get() != 0

    private class CancelledException : IOException("cancelled")

    private class LinkExpiredException(code: Int, val generation: Int) : IOException("HTTP $code")

    private fun coordinate(resume: Boolean) {
        val key = destination.absolutePath
        val previous = writers.put(key, Thread.currentThread())
        try {
            // A resumed download must not measure the files while the previous one still writes
            previous?.join()
            val success = download(resume)
            if (success) {
                Log.i(TAG, "Downloaded $destination")
                callback.onSuccess()
            } else {
                callback.onFailure(isCancelled)
            }
        } catch (e: InterruptedException) {
            callback.onFailure(true)
        } catch (e: IOException) {
            if (isCancelled) {
                callback.onFailure(true)
            } else {
                Log.e(TAG, "Download of $destination failed", e)
                callback.onFailure(false)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Download of $destination failed", e)
            callback.onFailure(isCancelled)
        } finally {
            writers.remove(key, Thread.currentThread())
        }
    }

    // Resolution -------------------------------------------------------------------------

    private class Resolved(
        val url: URL,
        val total: Long,
        val ranges: Boolean,
        val duplicates: List<URL>,
    )

    private fun open(url: URL, range: String?) =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            // Transparent gzip would break byte ranges
            setRequestProperty("Accept-Encoding", "identity")
            if (range != null) setRequestProperty("Range", range)
        }

    /** Follows redirects to the server that has the file, and asks it for the size. */
    private fun resolve(): Resolved {
        var current = URL(url)
        val duplicates = mutableListOf<URL>()
        repeat(MAX_REDIRECTS) {
            if (isCancelled) throw CancelledException()
            val connection = open(current, "bytes=0-0")
            connections += connection
            try {
                val code = connection.responseCode
                if (useDuplicateLinks) {
                    duplicates += duplicateLinks(connection)
                }
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Redirect without Location from ${current.host}")
                        current = URL(current, location)
                    }

                    code == HttpURLConnection.HTTP_PARTIAL -> {
                        val total = contentRange(connection)?.total ?: -1
                        Log.i(TAG, "Resolved ${current.host}${current.path}: $total bytes")
                        return Resolved(current, total, total > 0, duplicates)
                    }

                    code == HttpURLConnection.HTTP_OK -> {
                        Log.i(TAG, "${current.host} doesn't support ranges")
                        return Resolved(current, connection.contentLengthLong, false, duplicates)
                    }

                    else -> throw IOException("HTTP $code from ${current.host}")
                }
            } finally {
                connection.disconnect()
                connections -= connection
            }
        }
        throw IOException("Too many redirects")
    }

    private class ContentRange(val start: Long, val end: Long, val total: Long)

    private fun contentRange(connection: HttpURLConnection): ContentRange? {
        val match = CONTENT_RANGE.matchEntire(connection.getHeaderField("Content-Range") ?: "")
            ?: return null
        return ContentRange(
            match.groupValues[1].toLong(),
            match.groupValues[2].toLong(),
            match.groupValues[3].toLong(),
        )
    }

    private fun duplicateLinks(connection: HttpURLConnection): List<URL> =
        connection.headerFields.entries
            .filter { it.key.equals("Link", ignoreCase = true) }
            .flatMap { it.value }
            .mapNotNull { DUPLICATE_LINK.find(it)?.groupValues?.get(1) }
            .mapNotNull { runCatching { URL(it) }.getOrNull() }

    // Download ---------------------------------------------------------------------------

    private fun download(resume: Boolean): Boolean {
        val resolved = resolve()
        if (!resolved.ranges) {
            return downloadStream(resolved)
        }
        val total = resolved.total
        val map = prepare(resume, total)
        callback.onResponse(object : DownloadClient.Headers {
            override fun get(name: String) =
                if (name.equals("Content-Length", ignoreCase = true)) total.toString() else null
        })
        return Session(resolved, map).run()
    }

    /**
     * Prepares and verifies the block & part map to continue from, sanitizing on-disk part
     * files so every marked-done block is CRC32-verified and any partial tail is truncated.
     */
    private fun prepare(resume: Boolean, total: Long): PartialDownload.BlockMap {
        val mapFile = PartialDownload.mapFile(destination)
        if (resume) {
            val rawMap = PartialDownload.readMap(mapFile)
            if (rawMap != null && rawMap.total == total && rawMap.blockSize == config.blockSize) {
                val verified = PartialDownload.verifyAndSanitizeParts(destination, rawMap)
                if (verified.parts.isNotEmpty()) {
                    PartialDownload.writeMap(mapFile, verified)
                    Log.i(
                        TAG,
                        "Resuming with ${verified.done.cardinality()} of ${verified.blockCount} " +
                            "verified blocks across ${verified.parts.size} parts"
                    )
                    return verified
                }
            }
            val legacyLength = destination.length()
            val part0 = PartialDownload.partFile(destination, 0)
            if (!part0.exists() && legacyLength in 1 until total && destination.renameTo(part0)) {
                // Started by a sequential download: the file is a prefix of the package
                val fullBlocks = (legacyLength / config.blockSize).toInt()
                val done = BitSet().apply { set(0, fullBlocks) }
                val blockCount = ((total + config.blockSize - 1) / config.blockSize).toInt()
                val rawLegacy = PartialDownload.BlockMap(
                    total,
                    config.blockSize,
                    done,
                    emptyMap(),
                    listOf(PartialDownload.PartSpec(0, 0, blockCount)),
                )
                val verified = PartialDownload.verifyAndSanitizeParts(destination, rawLegacy)
                PartialDownload.writeMap(mapFile, verified)
                Log.i(TAG, "Continuing a sequential download at ${verified.doneBytes()} bytes")
                return verified
            }
            Log.w(TAG, "Nothing valid to resume from, starting over")
        }
        PartialDownload.delete(destination)
        return createInitialPartMap(total)
    }

    /** Partitions a fresh download into 1DM/IDM initial parts (`0 until numParts`). */
    private fun createInitialPartMap(total: Long): PartialDownload.BlockMap {
        val blockCount = ((total + config.blockSize - 1) / config.blockSize).toInt()
        val numParts = min(max(1, config.connections), max(1, blockCount))
        val baseBlocks = blockCount / numParts
        val remainder = blockCount % numParts
        val parts = ArrayList<PartialDownload.PartSpec>(numParts)
        var cursor = 0
        for (i in 0 until numParts) {
            val count = baseBlocks + if (i < remainder) 1 else 0
            if (count > 0) {
                parts.add(PartialDownload.PartSpec(i, cursor, cursor + count))
                cursor += count
            }
        }
        return PartialDownload.BlockMap(total, config.blockSize, BitSet(), emptyMap(), parts)
    }

    /** For servers without ranges: one stream, from the start. */
    private fun downloadStream(resolved: Resolved): Boolean {
        PartialDownload.delete(destination)
        val data = PartialDownload.dataFile(destination)
        val connection = open(resolved.url, null)
        connections += connection
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val total = connection.contentLengthLong
            callback.onResponse(object : DownloadClient.Headers {
                override fun get(name: String): String? = connection.getHeaderField(name)
            })
            val progress = Progress(0, total)
            RandomAccessFile(data, "rw").use { file ->
                val channel = file.channel
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var position = 0L
                    while (true) {
                        if (isCancelled) throw CancelledException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        writeFully(channel, buffer, n, position)
                        position += n
                        progress.add(n.toLong())
                        progress.report(false)
                    }
                    if (total >= 0 && position != total) {
                        throw IOException("Stream ended after $position of $total bytes")
                    }
                }
                channel.force(false)
            }
            progress.report(true)
            if (!data.renameTo(destination)) throw IOException("Could not rename $data")
            return true
        } finally {
            connection.disconnect()
            connections -= connection
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteArray, length: Int, position: Long) {
        val bytes = ByteBuffer.wrap(buffer, 0, length)
        var at = position
        while (bytes.hasRemaining()) {
            at += channel.write(bytes, at)
        }
    }

    /** Download progress, speed and ETA, reported at most twice a second. */
    private inner class Progress(initial: Long, private val total: Long) {
        private val bytes = AtomicLong(initial)
        private var lastReport = 0L
        private var sampleBytes = initial
        private var sampleTime = System.nanoTime()
        private var speed = -1L

        fun add(delta: Long) {
            bytes.addAndGet(delta)
        }

        fun set(value: Long) {
            bytes.set(value)
        }

        @Synchronized
        fun report(force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - lastReport < REPORT_INTERVAL_NS) return
            lastReport = now
            val current = if (total > 0) min(max(0L, bytes.get()), total) else max(0L, bytes.get())
            val elapsed = now - sampleTime
            if (elapsed >= SPEED_SAMPLE_NS) {
                val sample = (current - sampleBytes) * 1_000_000_000L / elapsed
                speed = if (speed < 0) sample else (speed * 3 + sample) / 4
                sampleBytes = current
                sampleTime = now
            }
            val eta = if (speed > 0 && total > 0) max(1, (total - current) / speed) else -1
            progressListener?.update(current, total, speed, eta)
        }
    }

    // Mirrors ----------------------------------------------------------------------------

    private class Mirror(@Volatile var url: URL) {
        /** Bytes per second of one connection; negative until measured. */
        @Volatile
        var rate = -1.0

        @Volatile
        var dead = false
        val failures = AtomicInteger()
        val workers = AtomicInteger()

        fun record(bytes: Long, nanos: Long) {
            if (bytes <= 0 || nanos <= 0) return
            val sample = bytes * 1e9 / nanos
            rate = if (rate < 0) sample else rate * 0.5 + sample * 0.5
            failures.set(0)
        }

        override fun toString() = url.host
    }

    // Session (1DM / IDM By-Parts Engine) ------------------------------------------------

    /**
     * Mutable state of one download part `<destination>.part.<id>` covering blocks
     * `[startBlock, endBlock)`. Blocks `startBlock until currentBlock` are already written,
     * CRC32-verified, and flushed to `<destination>.part.<id>`.
     */
    private class PartState(
        val id: Int,
        val startBlock: Int,
        @Volatile var currentBlock: Int,
        @Volatile var endBlock: Int,
    ) {
        @Volatile
        var active = false

        val isComplete: Boolean
            get() = currentBlock >= endBlock

        val remainingBlocks: Int
            get() = max(0, endBlock - currentBlock)

        fun toSpec() = PartialDownload.PartSpec(id, startBlock, endBlock)
    }

    private inner class Session(resolved: Resolved, initialMap: PartialDownload.BlockMap) {
        private val total = initialMap.total
        private val blockSize = initialMap.blockSize
        private val blockCount = initialMap.blockCount
        private val mapFile = PartialDownload.mapFile(destination)
        private val lock = Any()
        private val done = initialMap.done.clone() as BitSet
        private val blockCrcs = LinkedHashMap<Int, Int>(initialMap.blockCrcs)
        private val parts = ArrayList<PartState>()
        private var nextPartId = 0
        private val progress = Progress(initialMap.doneBytes(), total)
        private val mirrors: List<Mirror>

        @Volatile
        private var linkGeneration = 0
        private var linkRefreshes = 0
        private val refreshLock = Any()

        init {
            val candidates = buildList {
                add(resolved.url)
                addAll(resolved.duplicates)
                addAll(config.mirrorCandidates(resolved.url))
            }.distinctBy { it.toString() }
            mirrors = candidates.map { Mirror(it) }

            val specs = if (initialMap.parts.isNotEmpty()) {
                initialMap.parts.sortedBy { it.startBlock }
            } else {
                createInitialPartMap(total).parts
            }
            for (spec in specs) {
                var cur = spec.startBlock
                while (cur < spec.endBlock && done.get(cur)) {
                    cur++
                }
                // Clear any non-contiguous bits after the first unfinished block in this part
                if (cur < spec.endBlock) {
                    done.clear(cur, spec.endBlock)
                }
                parts.add(PartState(spec.id, spec.startBlock, cur, spec.endBlock))
                if (spec.id >= nextPartId) {
                    nextPartId = spec.id + 1
                }
            }
            progress.set(snapshotMap().doneBytes())
        }

        private fun snapshotMap(): PartialDownload.BlockMap = synchronized(lock) {
            PartialDownload.BlockMap(
                total,
                blockSize,
                done.clone() as BitSet,
                LinkedHashMap(blockCrcs),
                parts.sortedBy { it.startBlock }.map { it.toSpec() },
            )
        }

        fun run(): Boolean {
            // Save initial part layout so even an immediate pause can resume cleanly
            save()

            repeat(2) { attempt ->
                if (!isAllComplete()) {
                    if (attempt == 0) {
                        probeMirrors()
                    }
                    val missingBlocks = synchronized(lock) { blockCount - done.cardinality() }
                    val workerCount = min(config.connections, max(1, missingBlocks))
                    val workers = (0 until workerCount).map { id ->
                        Thread({ work(id) }, "UpdaterDownload-$id").also { it.start() }
                    }
                    var lastSave = System.nanoTime()
                    try {
                        while (workers.any { it.isAlive }) {
                            Thread.sleep(REPORT_INTERVAL_MS)
                            progress.report(false)
                            if (System.nanoTime() - lastSave >= SAVE_INTERVAL_NS) {
                                save()
                                lastSave = System.nanoTime()
                            }
                        }
                    } catch (e: InterruptedException) {
                        // Cancelled; wait for the workers to finish or roll back their current block
                        cancelled.set(1)
                        workers.forEach { it.join() }
                    } finally {
                        save()
                    }
                }

                if (isCancelled) return false
                if (!isAllComplete()) {
                    val finished = synchronized(lock) { done.cardinality() }
                    Log.e(TAG, "Download stopped with $finished of $blockCount blocks")
                    return false
                }

                try {
                    val finalMap = snapshotMap()
                    PartialDownload.assembleParts(destination, finalMap)
                    progress.set(total)
                    progress.report(true)
                    return true
                } catch (e: PartialDownload.CorruptPartException) {
                    Log.w(TAG, "Corrupt part detected at block ${e.blockIndex}, re-fetching part", e)
                    invalidateFromBlock(e.blockIndex)
                    save()
                }
            }
            return false
        }

        private fun isAllComplete(): Boolean = synchronized(lock) {
            done.cardinality() == blockCount && parts.all { it.isComplete }
        }

        /** Marks a corrupted part's blocks from [badBlock] onward as unfinished so it re-fetches. */
        private fun invalidateFromBlock(badBlock: Int) = synchronized(lock) {
            val part = parts.find { badBlock in it.startBlock until it.endBlock } ?: return@synchronized
            for (b in badBlock until part.endBlock) {
                done.clear(b)
                blockCrcs.remove(b)
            }
            if (part.currentBlock > badBlock) {
                part.currentBlock = badBlock
            }
            val pFile = PartialDownload.partFile(destination, part.id)
            if (pFile.exists()) {
                runCatching {
                    RandomAccessFile(pFile, "rw").use { raf ->
                        raf.setLength((badBlock - part.startBlock).toLong() * blockSize)
                    }
                }
            }
            progress.set(
                PartialDownload.BlockMap(total, blockSize, done).doneBytes()
            )
        }

        /** Writes the map of finished blocks and part ranges atomically to disk. */
        private fun save() {
            try {
                PartialDownload.writeMap(mapFile, snapshotMap())
            } catch (e: IOException) {
                Log.w(TAG, "Could not save download progress", e)
            }
        }

        /** Measures every mirror with a small ranged read, all at the same time. */
        private fun probeMirrors() {
            if (mirrors.size < 2) return
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.probeTimeoutMs)
            val probes = mirrors.map { mirror ->
                Thread({ probe(mirror, deadline) }, "UpdaterProbe-${mirror.url.host}")
                    .also { it.start() }
            }
            probes.forEach { it.join(config.probeTimeoutMs + config.connectTimeoutMs) }
            if (mirrors.all { it.dead }) {
                // Probing can fail for reasons a retry gets over; don't give up yet
                mirrors.first().dead = false
            }
            Log.i(TAG, "Mirrors: " + mirrors.sortedByDescending { it.rate }.joinToString { m ->
                if (m.dead) "$m (unreachable)" else "$m ${"%.0f".format(m.rate / 1024)} KiB/s"
            })
        }

        private fun probe(mirror: Mirror, deadline: Long) {
            val probeEnd = min(total - 1, config.probeBytes.toLong() - 1)
            if (probeEnd < 0) return
            val connection = open(mirror.url, "bytes=0-$probeEnd")
            connection.connectTimeout = config.probeTimeoutMs.toInt()
            connection.readTimeout = config.probeTimeoutMs.toInt()
            connections += connection
            val start = System.nanoTime()
            var bytes = 0L
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL ||
                        contentRange(connection)?.total != total) {
                    throw IOException("HTTP ${connection.responseCode}")
                }
                val contentType = connection.contentType?.lowercase().orEmpty()
                if (contentType.startsWith("text/html")) {
                    throw IOException("Mirror returned HTML instead of binary data")
                }
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (bytes < config.probeBytes && System.nanoTime() < deadline) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                    }
                }
                mirror.rate = bytes * 1e9 / max(1, System.nanoTime() - start)
            } catch (e: IOException) {
                Log.d(TAG, "Mirror ${mirror.url.host} failed the probe: $e")
                mirror.dead = true
            } finally {
                connection.disconnect()
                connections -= connection
            }
        }

        private fun bestMirror(): Mirror? =
            mirrors.filterNot { it.dead }.maxByOrNull { it.rate }

        /**
         * Spreads the first assignments over the mirrors that are nearly as fast as the best
         * one, then moves a worker only when its mirror falls well behind.
         */
        private fun pickMirror(current: Mirror?, worker: Int): Mirror? {
            val best = bestMirror() ?: return null
            if (current == null) {
                val good = mirrors.filter { !it.dead && it.rate >= best.rate * GOOD_MIRROR_SHARE }
                    .sortedByDescending { it.rate }
                return good.getOrElse(worker % max(1, min(good.size, MAX_START_MIRRORS))) { best }
            }
            if (current.dead || current.rate < best.rate * SWITCH_SHARE) {
                return best
            }
            return current
        }

        /**
         * Claims an inactive unfinished part, or dynamically splits the largest active part into
         * a new `.part.<id>` file (1DM / IDM dynamic segmentation).
         */
        private fun acquirePart(): PartState? = synchronized(lock) {
            val free = parts
                .filter { !it.active && !it.isComplete }
                .maxByOrNull { it.remainingBlocks }
            if (free != null) {
                free.active = true
                return@synchronized free
            }

            // Dynamic part splitting: split the active part with the most remaining blocks
            val victim = parts
                .filter { it.active && it.remainingBlocks >= 2 }
                .maxByOrNull { it.remainingBlocks }
                ?: return@synchronized null

            // Leave at least 1 block past victim.currentBlock so the victim finishes its current
            // in-flight block without waste, and hand the second half to the new part file.
            val splitBlock = victim.currentBlock + max(1, victim.remainingBlocks / 2)
            if (splitBlock >= victim.endBlock) return@synchronized null

            val oldEnd = victim.endBlock
            victim.endBlock = splitBlock
            val newPart = PartState(
                id = nextPartId++,
                startBlock = splitBlock,
                currentBlock = splitBlock,
                endBlock = oldEnd,
            ).apply { active = true }
            parts.add(newPart)
            newPart
        }

        private fun releasePart(part: PartState) = synchronized(lock) {
            part.active = false
        }

        private fun blockStart(block: Int): Long = block * blockSize

        private fun blockEnd(block: Int): Long = min(total, (block + 1) * blockSize)

        private fun blockLength(block: Int): Int = (blockEnd(block) - blockStart(block)).toInt()

        private fun work(worker: Int) {
            var mirror = pickMirror(null, worker)
            var failures = 0
            while (!isCancelled) {
                val part = acquirePart() ?: return
                try {
                    while (!isCancelled) {
                        val hasMore = synchronized(lock) { part.currentBlock < part.endBlock }
                        if (!hasMore) break

                        mirror = pickMirror(mirror, worker) ?: return
                        mirror.workers.incrementAndGet()
                        try {
                            fetchRun(part, mirror)
                            failures = 0
                        } catch (e: IOException) {
                            if (isCancelled) return
                            if (e is LinkExpiredException && refreshLinks(e.generation)) continue
                            failures++
                            if (mirror.failures.incrementAndGet() >= MIRROR_MAX_FAILURES &&
                                    mirrors.count { !it.dead } > 1) {
                                Log.w(TAG, "Dropping mirror ${mirror.url.host}")
                                mirror.dead = true
                            }
                            Log.w(TAG, "Worker $worker: ${mirror.url.host} failed ($failures): $e")
                            if (failures >= config.maxWorkerFailures) {
                                Log.e(TAG, "Worker $worker gives up")
                                return
                            }
                            try {
                                Thread.sleep(config.retryDelayMs * (1L shl min(failures - 1, 4)))
                            } catch (_: InterruptedException) {
                                return
                            }
                        } finally {
                            mirror.workers.decrementAndGet()
                        }
                    }
                } finally {
                    releasePart(part)
                }
            }
        }

        /**
         * Downloads up to `config.runBlocks` blocks for [part] into its dedicated
         * `<destination>.part.<part.id>` file.
         *
         * Each block is buffered in memory, CRC32-checksummed on completion, and written
         * atomically at its block-aligned offset in `<destination>.part.<part.id>`. If interrupted
         * or cut short mid-block, the uncommitted in-flight block is discarded and the part file
         * is truncated to the exact last verified block boundary.
         */
        private fun fetchRun(part: PartState, mirror: Mirror) {
            val (runStartBlock, requestedEndBlock) = synchronized(lock) {
                val s = part.currentBlock
                val e = min(part.endBlock, s + config.runBlocks)
                s to e
            }
            if (runStartBlock >= requestedEndBlock) return

            val rangeStartByte = blockStart(runStartBlock)
            val rangeEndByte = blockEnd(requestedEndBlock - 1) - 1
            val generation = linkGeneration
            val connection = open(mirror.url, "bytes=$rangeStartByte-$rangeEndByte")
            connections += connection
            val startTime = System.nanoTime()
            var totalBytesCommitted = 0L
            var inFlightBlockBytes = 0

            val pFile = PartialDownload.partFile(destination, part.id)
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_FORBIDDEN ||
                        code == HttpURLConnection.HTTP_NOT_FOUND || code == HTTP_GONE) {
                    throw LinkExpiredException(code, generation)
                }
                if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")
                val contentType = connection.contentType?.lowercase().orEmpty()
                if (contentType.startsWith("text/html")) {
                    throw IOException("Mirror ${mirror.url.host} returned HTML page")
                }
                val range = contentRange(connection)
                if (range?.start != rangeStartByte || range.total != total ||
                        range.end < rangeEndByte) {
                    throw IOException("Unexpected range from ${mirror.url.host}")
                }

                RandomAccessFile(pFile, "rw").use { raf ->
                    val channel = raf.channel
                    val blockBuffer = ByteArray(blockSize.toInt())
                    connection.inputStream.use { input ->
                        var block = runStartBlock
                        while (block < requestedEndBlock) {
                            // Check if dynamic part splitting reduced part.endBlock
                            val currentEndBlock = synchronized(lock) { part.endBlock }
                            if (block >= currentEndBlock) break

                            val expectedLen = blockLength(block)
                            var offsetInBlock = 0
                            while (offsetInBlock < expectedLen) {
                                if (isCancelled) throw CancelledException()
                                val toRead = min(BUFFER_SIZE, expectedLen - offsetInBlock)
                                val n = input.read(blockBuffer, offsetInBlock, toRead)
                                if (n < 0) {
                                    throw IOException(
                                        "Connection closed at block $block (+$offsetInBlock bytes)"
                                    )
                                }
                                offsetInBlock += n
                                inFlightBlockBytes += n
                                progress.add(n.toLong())
                            }

                            // Re-check under lock that this block still belongs to this part
                            // before committing it to `<destination>.part.<part.id>`
                            val crc = PartialDownload.computeBlockCrc(blockBuffer, 0, expectedLen)
                            val relOffset = (block - part.startBlock).toLong() * blockSize
                            writeFully(channel, blockBuffer, expectedLen, relOffset)
                            channel.force(false)

                            val committed = synchronized(lock) {
                                if (block < part.endBlock && part.currentBlock == block) {
                                    blockCrcs[block] = crc
                                    done.set(block)
                                    part.currentBlock = block + 1
                                    true
                                } else {
                                    false
                                }
                            }
                            if (!committed) {
                                // Part was split right at this block; roll back progress and stop
                                progress.add(-inFlightBlockBytes.toLong())
                                inFlightBlockBytes = 0
                                val validPartBytes = synchronized(lock) {
                                    (part.currentBlock - part.startBlock).toLong() * blockSize
                                }
                                raf.setLength(validPartBytes)
                                break
                            }

                            inFlightBlockBytes = 0
                            totalBytesCommitted += expectedLen
                            block++
                        }
                    }
                }
                mirror.record(totalBytesCommitted, System.nanoTime() - startTime)
            } catch (e: InterruptedIOException) {
                if (isCancelled) throw CancelledException()
                throw e
            } finally {
                if (inFlightBlockBytes > 0) {
                    progress.add(-inFlightBlockBytes.toLong())
                    inFlightBlockBytes = 0
                }
                // Ensure the part file on disk never has trailing bytes beyond part.currentBlock
                if (pFile.exists()) {
                    runCatching {
                        val validBytes = synchronized(lock) {
                            val verifiedBlocks = part.currentBlock - part.startBlock
                            if (part.currentBlock == blockCount && verifiedBlocks > 0) {
                                blockEnd(blockCount - 1) - blockStart(part.startBlock)
                            } else {
                                verifiedBlocks.toLong() * blockSize
                            }
                        }
                        RandomAccessFile(pFile, "rw").use { raf ->
                            if (raf.length() != validBytes) {
                                raf.setLength(validBytes)
                            }
                        }
                    }
                }
                val fullyRead = synchronized(lock) { part.currentBlock >= requestedEndBlock }
                if (!fullyRead || isCancelled) connection.disconnect()
                connections -= connection
            }
        }

        /**
         * Signed mirror links expire; get a fresh one from the original URL. Workers that hit
         * the expiry with an older link just retry with the one already refreshed.
         */
        private fun refreshLinks(seenGeneration: Int): Boolean = synchronized(refreshLock) {
            if (linkGeneration != seenGeneration) return true
            if (linkRefreshes >= config.maxLinkRefreshes) return false
            linkRefreshes++
            val fresh = try {
                resolve()
            } catch (e: IOException) {
                Log.w(TAG, "Could not refresh the download link", e)
                return false
            }
            Log.i(TAG, "Refreshed the download link")
            for (mirror in mirrors) {
                if (mirror.url.path == fresh.url.path) {
                    mirror.url = URL(
                        mirror.url.protocol,
                        mirror.url.host,
                        mirror.url.port,
                        fresh.url.file,
                    )
                    mirror.failures.set(0)
                }
            }
            linkGeneration++
            true
        }
    }

    companion object {
        private const val TAG = "ParallelDownloadClient"
        private const val USER_AGENT = "LunarisUpdater/1.0"
        private const val MAX_REDIRECTS = 10
        private const val BUFFER_SIZE = 64 * 1024
        private const val HTTP_GONE = 410
        private const val REPORT_INTERVAL_MS = 500L
        private const val REPORT_INTERVAL_NS = REPORT_INTERVAL_MS * 1_000_000
        private const val SPEED_SAMPLE_NS = 2_000_000_000L
        private const val SAVE_INTERVAL_NS = 2_000_000_000L
        private const val MIRROR_MAX_FAILURES = 3

        /** Mirrors at least this fast relative to the best one get workers from the start. */
        private const val GOOD_MIRROR_SHARE = 0.5
        private const val MAX_START_MIRRORS = 3

        /** A worker leaves its mirror when it drops below this share of the best one. */
        private const val SWITCH_SHARE = 0.35

        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")
        private val DUPLICATE_LINK = Regex(
            """<([^>]+)>\s*;\s*rel=duplicate""",
            RegexOption.IGNORE_CASE,
        )

        // Only one download may write a destination at a time
        private val writers = ConcurrentHashMap<String, Thread>()
    }
}
