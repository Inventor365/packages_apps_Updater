/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.os.SystemProperties
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SourceForgeMirrorUtils {
    private const val TAG = "SourceForgeMirrorUtils"
    private const val PROP_PREFERRED_MIRROR = "lunaris.updater.sf_mirror"
    const val DEFAULT_PREFERRED_MIRROR = "twds"

    // Recognized reliable SourceForge mirrors categorized by geographic region
    private val ASIA_OCEANIA_MIRRORS = listOf("twds", "jaist", "pilotfiber", "master", "phoenixnap")
    private val EUROPE_AFRICA_MIRRORS = listOf("deac-riga", "netix", "netcologne", "twds", "pilotfiber", "master")
    private val AMERICAS_MIRRORS = listOf("pilotfiber", "phoenixnap", "versaweb", "twds", "master")
    private val GLOBAL_FALLBACK_MIRRORS = listOf("twds", "pilotfiber", "master", "phoenixnap", "deac-riga")

    enum class Region {
        ASIA_OCEANIA, EUROPE_AFRICA, AMERICAS, GLOBAL
    }

    /**
     * Determines the user's geographic region based on default device Locale and TimeZone.
     */
    fun detectUserRegion(): Region {
        val country = runCatching { Locale.getDefault().country.uppercase() }.getOrDefault("")
        val timeZone = runCatching { TimeZone.getDefault().id.lowercase() }.getOrDefault("")

        val asiaCountries = setOf(
            "IN", "CN", "TW", "HK", "JP", "KR", "ID", "MY", "SG", "PH", "TH", "VN",
            "PK", "BD", "NP", "LK", "MM", "KH", "LA", "AU", "NZ"
        )
        val europeAfricaCountries = setOf(
            "DE", "FR", "GB", "IT", "ES", "PL", "NL", "RU", "UA", "RO", "SE", "NO",
            "FI", "CZ", "GR", "PT", "BE", "HU", "AT", "CH", "BG", "DK", "IE", "TR",
            "ZA", "EG", "NG", "KE", "MA"
        )
        val americasCountries = setOf(
            "US", "CA", "MX", "BR", "AR", "CL", "CO", "PE", "VE"
        )

        return when {
            country in asiaCountries || timeZone.startsWith("asia/") || timeZone.startsWith("australia/") || timeZone.startsWith("pacific/") ->
                Region.ASIA_OCEANIA
            country in europeAfricaCountries || timeZone.startsWith("europe/") || timeZone.startsWith("africa/") ->
                Region.EUROPE_AFRICA
            country in americasCountries || timeZone.startsWith("america/") ->
                Region.AMERICAS
            else -> Region.GLOBAL
        }
    }

    fun getPreferredMirror(): String? {
        val prop = runCatching { SystemProperties.get(PROP_PREFERRED_MIRROR) }.getOrNull()
        return if (!prop.isNullOrBlank()) prop.trim() else null
    }

    fun getRegionalMirrors(): List<String> {
        val explicit = getPreferredMirror()
        val baseList = when (detectUserRegion()) {
            Region.ASIA_OCEANIA -> ASIA_OCEANIA_MIRRORS
            Region.EUROPE_AFRICA -> EUROPE_AFRICA_MIRRORS
            Region.AMERICAS -> AMERICAS_MIRRORS
            Region.GLOBAL -> GLOBAL_FALLBACK_MIRRORS
        }

        if (explicit.isNullOrBlank()) {
            return baseList
        }

        return listOf(explicit) + baseList.filterNot { it.equals(explicit, ignoreCase = true) }
    }

    /**
     * Checks if a URL is a SourceForge direct mirror link (e.g. *.dl.sourceforge.net).
     */
    fun isSourceForgeDirectMirrorUrl(url: URL): Boolean {
        val host = url.host?.lowercase() ?: return false
        return host.endsWith(".dl.sourceforge.net")
    }

    /**
     * Checks if a URL string is any SourceForge URL.
     */
    fun isSourceForgeUrl(urlStr: String): Boolean {
        val lower = urlStr.lowercase()
        return lower.contains("sourceforge.net")
    }

    /**
     * Generates candidate URLs with different mirrors for a SourceForge *.dl.sourceforge.net URL,
     * ordered by the user's geographic location (with twds favored for Asia & global).
     */
    fun getMirrorCandidateUrls(originalUrl: URL): List<URL> {
        if (!isSourceForgeDirectMirrorUrl(originalUrl)) {
            return listOf(originalUrl)
        }

        val regionalMirrors = getRegionalMirrors()
        val candidates = mutableListOf<URL>()
        val protocol = originalUrl.protocol
        val file = originalUrl.file
        val port = originalUrl.port

        for (mirror in regionalMirrors) {
            val mirrorHost = "$mirror.dl.sourceforge.net"
            val candidateUrl = if (port != -1) {
                URL(protocol, mirrorHost, port, file)
            } else {
                URL(protocol, mirrorHost, file)
            }
            if (!candidates.contains(candidateUrl)) {
                candidates.add(candidateUrl)
            }
        }

        if (!candidates.contains(originalUrl)) {
            candidates.add(originalUrl)
        }

        return candidates
    }

    /**
     * Quickly probes candidate mirrors with lightweight HEAD requests to find the lowest-latency responsive mirror.
     * If probing times out or fails, returns the top prioritized candidate.
     */
    fun selectFastestMirror(candidates: List<URL>): URL {
        if (candidates.size <= 1) {
            return candidates.firstOrNull() ?: throw IllegalArgumentException("Empty candidates")
        }

        val poolSize = minOf(candidates.size, 4)
        val executor = Executors.newFixedThreadPool(poolSize)
        try {
            val completionService = ExecutorCompletionService<Pair<URL, Long>>(executor)
            for (url in candidates.take(poolSize)) {
                completionService.submit(Callable {
                    val start = System.currentTimeMillis()
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 1500
                        readTimeout = 1500
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "LunarisUpdater/1.0")
                    }
                    try {
                        conn.connect()
                        val code = conn.responseCode
                        val elapsed = System.currentTimeMillis() - start
                        if (code == 200 || code == 206) {
                            return@Callable Pair(url, elapsed)
                        }
                    } finally {
                        conn.disconnect()
                    }
                    throw IOException("Mirror ${url.host} returned non-success HTTP status")
                })
            }

            val firstCompleted = completionService.poll(1500, TimeUnit.MILLISECONDS)
            if (firstCompleted != null) {
                val result = runCatching { firstCompleted.get() }.getOrNull()
                if (result != null) {
                    Log.d(TAG, "Fastest mirror detected based on location probe: ${result.first.host} (${result.second} ms)")
                    return result.first
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mirror probe interrupted or failed, using location priority", e)
        } finally {
            executor.shutdownNow()
        }

        Log.d(TAG, "Selected top location-priority mirror: ${candidates.first().host}")
        return candidates.first()
    }
}
