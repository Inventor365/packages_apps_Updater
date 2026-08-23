/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.os.SystemProperties
import java.net.URL

object SourceForgeMirrorUtils {
    private const val PROP_PREFERRED_MIRROR = "lunaris.updater.sf_mirror"
    const val DEFAULT_PREFERRED_MIRROR = "twds"

    // Fast and reliable SourceForge mirrors in order of preference
    val CANDIDATE_MIRRORS = listOf(
        "twds",
        "pilotfiber",
        "master",
        "phoenixnap",
        "netix",
        "deac-riga",
    )

    fun getPreferredMirror(): String {
        val prop = runCatching { SystemProperties.get(PROP_PREFERRED_MIRROR) }.getOrNull()
        return if (!prop.isNullOrBlank()) prop.trim() else DEFAULT_PREFERRED_MIRROR
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
     * Generates candidate URLs with different mirrors for a SourceForge *.dl.sourceforge.net URL.
     * The preferred mirror (default: twds) is placed first.
     */
    fun getMirrorCandidateUrls(originalUrl: URL): List<URL> {
        if (!isSourceForgeDirectMirrorUrl(originalUrl)) {
            return listOf(originalUrl)
        }

        val preferred = getPreferredMirror()
        val mirrors = mutableListOf<String>()
        mirrors.add(preferred)
        for (m in CANDIDATE_MIRRORS) {
            if (!mirrors.contains(m)) {
                mirrors.add(m)
            }
        }

        val candidates = mutableListOf<URL>()
        val protocol = originalUrl.protocol
        val file = originalUrl.file
        val port = originalUrl.port

        for (mirror in mirrors) {
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
}
