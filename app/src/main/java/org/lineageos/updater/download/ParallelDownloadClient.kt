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
 * Downloads a package over several connections at once, each fetching a different byte range.
 *
 * Download servers, SourceForge mirrors in particular, cap the speed of each connection, so a
 * single stream stays slow however fast the network is. Here the package is split in blocks
 * (see [PartialDownload]); workers fetch runs of blocks from the fastest mirrors, move to a
 * better mirror when theirs falls behind, and split the largest unfinished run when they run
 * out of work. Finished blocks are recorded on disk, so a pause, a network error or the process
 * dying only costs the blocks that were in flight.
 *
 * Servers without range support are downloaded in a single stream.
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
        /** Blocks per request; a worker can change mirror between requests. */
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

    private class Resolved(val url: URL, val total: Long, val ranges: Boolean,
            val duplicates: List<URL>)

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

    private class ContentRange(val start: Long, val total: Long)

    private fun contentRange(connection: HttpURLConnection): ContentRange? {
        val match = CONTENT_RANGE.matchEntire(connection.getHeaderField("Content-Range") ?: "")
            ?: return null
        return ContentRange(match.groupValues[1].toLong(), match.groupValues[3].toLong())
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

    /** The block map to continue from, set up so that the data file matches it. */
    private fun prepare(resume: Boolean, total: Long): PartialDownload.BlockMap {
        val data = PartialDownload.dataFile(destination)
        val mapFile = PartialDownload.mapFile(destination)
        if (resume) {
            val map = PartialDownload.readMap(mapFile)
            if (map != null && map.total == total && map.blockSize == config.blockSize &&
                    data.exists()) {
                Log.i(TAG, "Resuming with ${map.done.cardinality()} of ${map.blockCount} blocks")
                return map
            }
            val legacyLength = destination.length()
            if (!data.exists() && legacyLength in 1 until total && destination.renameTo(data)) {
                // Started by a sequential download: the file is a prefix of the package
                val done = BitSet().apply { set(0, (legacyLength / config.blockSize).toInt()) }
                Log.i(TAG, "Continuing a sequential download at $legacyLength bytes")
                return PartialDownload.BlockMap(total, config.blockSize, done)
            }
            Log.w(TAG, "Nothing to resume from, starting over")
        }
        PartialDownload.delete(destination)
        return PartialDownload.BlockMap(total, config.blockSize, BitSet())
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

        @Synchronized
        fun report(force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - lastReport < REPORT_INTERVAL_NS) return
            lastReport = now
            val current = if (total > 0) min(bytes.get(), total) else bytes.get()
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

    // Session ----------------------------------------------------------------------------

    private class Run(start: Long, @Volatile var end: Long) {
        @Volatile
        var position = start
    }

    private inner class Session(resolved: Resolved, private val map: PartialDownload.BlockMap) {
        private val total = map.total
        private val data = PartialDownload.dataFile(destination)
        private val mapFile = PartialDownload.mapFile(destination)
        private val queue = ArrayDeque<Run>()
        private val active = mutableSetOf<Run>()
        private val lock = Any()
        private val progress = Progress(map.doneBytes(), total)
        private val mirrors: List<Mirror>
        @Volatile
        private var linkGeneration = 0
        private var linkRefreshes = 0
        private val refreshLock = Any()
        private lateinit var channel: FileChannel

        init {
            val candidates = buildList {
                add(resolved.url)
                addAll(resolved.duplicates)
                addAll(config.mirrorCandidates(resolved.url))
            }.distinctBy { it.toString() }
            mirrors = candidates.map { Mirror(it) }

            // Contiguous missing blocks, in runs of up to runBlocks
            var block = map.done.nextClearBit(0)
            while (block < map.blockCount) {
                var end = block
                while (end < map.blockCount && !map.done[end] && end - block < config.runBlocks) {
                    end++
                }
                queue.addLast(Run(map.blockStart(block), map.blockEnd(end - 1)))
                block = map.done.nextClearBit(end)
            }
        }

        fun run(): Boolean {
            if (map.isComplete() && data.length() >= total) {
                return finish()
            }
            RandomAccessFile(data, "rw").use { file ->
                channel = file.channel
                probeMirrors()
                val missing = map.blockCount - map.done.cardinality()
                val workers = (0 until min(config.connections, max(1, missing)))
                    .map { id -> Thread({ work(id) }, "UpdaterDownload-$id").also { it.start() } }
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
                    // Cancelled; wait for the workers to stop writing
                    cancelled.set(1)
                    workers.forEach { it.join() }
                } finally {
                    save()
                }
            }
            if (isCancelled) return false
            if (!map.isComplete()) {
                Log.e(TAG, "Download stopped with ${map.done.cardinality()} of " +
                        "${map.blockCount} blocks")
                return false
            }
            progress.report(true)
            return finish()
        }

        private fun finish(): Boolean {
            RandomAccessFile(data, "rw").use { file ->
                if (file.length() != total) file.setLength(total)
                file.fd.sync()
            }
            if (!data.renameTo(destination)) throw IOException("Could not rename $data")
            mapFile.delete()
            return true
        }

        /** Writes the map of finished blocks, after the blocks themselves reached the disk. */
        private fun save() {
            val done = synchronized(lock) { map.done.clone() as BitSet }
            try {
                if (channel.isOpen) channel.force(false)
                PartialDownload.writeMap(mapFile,
                    PartialDownload.BlockMap(total, map.blockSize, done))
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
            val connection = open(mirror.url, "bytes=0-${config.probeBytes - 1}")
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

        private fun nextRun(): Run? = synchronized(lock) {
            val run = queue.removeFirstOrNull() ?: steal() ?: return null
            active += run
            run
        }

        /** Splits the largest unfinished run, so idle workers help with the tail. */
        private fun steal(): Run? {
            val victim = active.maxByOrNull { it.end - it.position } ?: return null
            val remaining = victim.end - victim.position
            if (remaining < 2 * map.blockSize) return null
            // On a block boundary, at least a block past where the victim is
            val split = ((victim.position + remaining / 2 + map.blockSize - 1) / map.blockSize) *
                    map.blockSize
            if (split >= victim.end) return null
            val stolen = Run(split, victim.end)
            victim.end = split
            return stolen
        }

        /** Puts back what is left of a run, from the start of its unfinished block. */
        private fun requeue(run: Run) = synchronized(lock) {
            active -= run
            if (run.position >= run.end) return@synchronized
            val start = run.position / map.blockSize * map.blockSize
            if (start < run.end) {
                // Bytes past the block start will be fetched again
                progress.add(-(run.position - start))
                queue.addFirst(Run(start, run.end))
            }
        }

        private fun work(worker: Int) {
            var mirror = pickMirror(null, worker)
            var failures = 0
            while (!isCancelled) {
                val run = nextRun() ?: return
                mirror = pickMirror(mirror, worker) ?: run {
                    requeue(run)
                    return
                }
                mirror.workers.incrementAndGet()
                try {
                    fetch(run, mirror)
                    synchronized(lock) { active -= run }
                    failures = 0
                } catch (e: IOException) {
                    requeue(run)
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
        }

        /** Downloads the rest of a run from a mirror, marking blocks done as they complete. */
        private fun fetch(run: Run, mirror: Mirror) {
            val generation = linkGeneration
            val requestedEnd = run.end
            val connection = open(mirror.url, "bytes=${run.position}-${requestedEnd - 1}")
            connections += connection
            val start = System.nanoTime()
            var bytes = 0L
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_FORBIDDEN ||
                        code == HttpURLConnection.HTTP_NOT_FOUND || code == HTTP_GONE) {
                    throw LinkExpiredException(code, generation)
                }
                if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")
                val range = contentRange(connection)
                if (range?.start != run.position || range.total != total) {
                    throw IOException("Unexpected range from ${mirror.url.host}")
                }
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (run.position < run.end) {
                        if (isCancelled) throw CancelledException()
                        val n = input.read(buffer, 0, min(buffer.size.toLong(),
                            run.end - run.position).toInt())
                        if (n < 0) throw IOException("Connection closed at ${run.position}")
                        // The run may have been split meanwhile; never write past its end
                        val length = min(n.toLong(), run.end - run.position).toInt()
                        if (length <= 0) break
                        writeFully(channel, buffer, length, run.position)
                        val before = run.position
                        run.position += length
                        bytes += length
                        progress.add(length.toLong())
                        markDone(before, run.position)
                    }
                }
                mirror.record(bytes, System.nanoTime() - start)
            } catch (e: InterruptedIOException) {
                if (isCancelled) throw CancelledException()
                throw e
            } finally {
                // A run cut short (by a split or an error) leaves an unread body; the
                // connection can't be reused then
                if (run.position < requestedEnd || isCancelled) connection.disconnect()
                connections -= connection
            }
        }

        /** Marks the blocks whose end the run just passed (runs start on block boundaries). */
        private fun markDone(from: Long, to: Long) {
            var block = (from / map.blockSize).toInt()
            synchronized(lock) {
                while (block < map.blockCount && map.blockEnd(block) <= to) {
                    if (map.blockEnd(block) > from) map.done.set(block)
                    block++
                }
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
                    mirror.url = URL(mirror.url.protocol, mirror.url.host, mirror.url.port,
                        fresh.url.file)
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
        private val DUPLICATE_LINK = Regex("""<([^>]+)>\s*;\s*rel=duplicate""",
            RegexOption.IGNORE_CASE)

        // Only one download may write a destination at a time
        private val writers = ConcurrentHashMap<String, Thread>()
    }
}
