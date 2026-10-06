/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.os.SystemProperties
import java.net.URL
import java.util.Locale
import java.util.TimeZone

/**
 * SourceForge mirrors to try next to the one SourceForge picked. A download link resolves to a
 * signed URL on one mirror (`<mirror>.dl.sourceforge.net/project/...?...&st=...`) that any
 * other mirror accepts as well; the download client measures them and uses the fastest.
 */
object SourceForgeMirrorUtils {
    private const val PROP_PREFERRED_MIRROR = "lunaris.updater.sf_mirror"

    // Mirrors that served the Lunaris packages on 2026-10-06, nearest first per region
    private val ASIA_OCEANIA_MIRRORS = listOf(
        "excellmedia", // Hyderabad
        "onboardcloud", // Singapore
        "zenlayer", // Hong Kong
        "twds", // Taipei
    )
    private val EUROPE_AFRICA_MIRRORS = listOf(
        "altushost-swe", // Stockholm
        "netix", // Sofia
        "deac-riga", // Riga
        "yer", // Baku
    )
    private val AMERICAS_MIRRORS = listOf(
        "pilotfiber", // New York
        "gigenet", // Chicago
        "netactuate", // Durham
        "phoenixnap", // Phoenix
        "psychz", // Los Angeles
    )
    private val GLOBAL_FALLBACK_MIRRORS = listOf("onboardcloud", "twds", "altushost-swe", "pilotfiber")

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

        // The time zone is the better hint: many phones use an en-US locale outside the US
        return when {
            timeZone.startsWith("asia/") || timeZone.startsWith("australia/") ||
                    timeZone.startsWith("pacific/") -> Region.ASIA_OCEANIA
            timeZone.startsWith("europe/") || timeZone.startsWith("africa/") ->
                Region.EUROPE_AFRICA
            timeZone.startsWith("america/") -> Region.AMERICAS
            country in asiaCountries -> Region.ASIA_OCEANIA
            country in europeAfricaCountries -> Region.EUROPE_AFRICA
            country in americasCountries -> Region.AMERICAS
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
     * The same file on the mirrors of the user's region, after the mirror [originalUrl] points
     * to (SourceForge's own pick). Links that aren't SourceForge mirror links are returned as is.
     */
    @JvmStatic
    fun getMirrorCandidateUrls(originalUrl: URL): List<URL> {
        if (!isSourceForgeDirectMirrorUrl(originalUrl)) {
            return listOf(originalUrl)
        }

        val candidates = mutableListOf(originalUrl)
        for (mirror in getRegionalMirrors()) {
            val host = "$mirror.dl.sourceforge.net"
            val candidate = URL(originalUrl.protocol, host, originalUrl.port, originalUrl.file)
            if (candidates.none { it.host.equals(host, ignoreCase = true) }) {
                candidates.add(candidate)
            }
        }
        return candidates
    }
}
