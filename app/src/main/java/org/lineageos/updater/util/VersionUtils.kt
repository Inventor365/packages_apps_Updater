/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

object VersionUtils {

    /**
     * Cleans a version string by stripping common prefixes (e.g. "v", "V", "Lunaris-", "Lunaris-AOSP-", "lineage-")
     */
    fun cleanVersion(version: String): String {
        var v = version.trim()
        val prefixes = listOf(
            "Lunaris-AOSP-", "Lunaris-", "Lunaris_", "lunaris-", "lunaris_",
            "lineage-", "lineage_", "crDroidAndroid-", "crdroid-", "update-",
            "v", "V"
        )
        for (prefix in prefixes) {
            if (v.startsWith(prefix, ignoreCase = true)) {
                v = v.substring(prefix.length).trim()
            }
        }
        return v
    }

    /**
     * Compares two version strings.
     * Returns:
     *   > 0 if v1 is newer than v2
     *   < 0 if v1 is older than v2
     *   0 if v1 is equivalent to v2
     */
    fun compare(v1: String, v2: String): Int {
        val clean1 = cleanVersion(v1)
        val clean2 = cleanVersion(v2)

        if (clean1.equals(clean2, ignoreCase = true)) {
            return 0
        }
        if (clean1.isEmpty()) return if (clean2.isEmpty()) 0 else -1
        if (clean2.isEmpty()) return 1

        val tokens1 = tokenize(clean1)
        val tokens2 = tokenize(clean2)

        val maxLen = maxOf(tokens1.size, tokens2.size)
        for (i in 0 until maxLen) {
            val t1 = tokens1.getOrNull(i)
            val t2 = tokens2.getOrNull(i)

            if (t1 == null && t2 != null) {
                // Check if all remaining tokens in tokens2 are 0
                val remainingAllZeros = tokens2.subList(i, tokens2.size).all {
                    it is Token.Number && it.value == 0L
                }
                if (remainingAllZeros) {
                    return 0
                }

                return if (isHotfixOrSuffix(t2)) {
                    -1
                } else if (t2 is Token.Number && t2.value > 0) {
                    -1
                } else if (t2 is Token.Text && isPreRelease(t2.value)) {
                    1 // v1 is release, v2 is pre-release -> v1 > v2
                } else {
                    -1
                }
            }
            if (t2 == null && t1 != null) {
                // Check if all remaining tokens in tokens1 are 0
                val remainingAllZeros = tokens1.subList(i, tokens1.size).all {
                    it is Token.Number && it.value == 0L
                }
                if (remainingAllZeros) {
                    return 0
                }

                return if (isHotfixOrSuffix(t1)) {
                    1
                } else if (t1 is Token.Number && t1.value > 0) {
                    1
                } else if (t1 is Token.Text && isPreRelease(t1.value)) {
                    -1 // v1 is pre-release, v2 is release -> v1 < v2
                } else {
                    1
                }
            }

            if (t1 != null && t2 != null) {
                val cmp = t1.compareTo(t2)
                if (cmp != 0) {
                    return cmp
                }
            }
        }

        return 0
    }

    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        if (remoteVersion.isBlank()) return false
        if (currentVersion.isBlank()) return true
        return compare(remoteVersion, currentVersion) > 0
    }

    fun isOlder(remoteVersion: String, currentVersion: String): Boolean {
        if (remoteVersion.isBlank() || currentVersion.isBlank()) return false
        return compare(remoteVersion, currentVersion) < 0
    }

    fun isSame(remoteVersion: String, currentVersion: String): Boolean {
        if (remoteVersion.isBlank() && currentVersion.isBlank()) return true
        if (remoteVersion.isBlank() || currentVersion.isBlank()) return false
        return compare(remoteVersion, currentVersion) == 0
    }

    /**
     * Extracts version string from a filename if not provided in JSON metadata.
     * e.g. "Lunaris-AOSP-peridot-Community-3.12-GMS-2026042812.zip" -> "3.12"
     * e.g. "Lunaris-AOSP-peridot-Community-3.12.1-GMS-2026042812.zip" -> "3.12.1"
     */
    fun extractVersionFromFilename(filename: String): String? {
        if (filename.isBlank()) return null
        val regex = Regex("""(?i)(?:lunaris|lineage|crdroid|aosp)?[^\d]*(\d+\.\d+(?:\.\d+)?(?:[-_](?:hotfix|patch|p|rc|beta|alpha)\d*)?)""")
        return regex.find(filename)?.groupValues?.getOrNull(1)
    }

    private sealed interface Token : Comparable<Token> {
        data class Number(val value: Long) : Token {
            override fun compareTo(other: Token): Int = when (other) {
                is Number -> value.compareTo(other.value)
                is Text -> {
                    if (isPreRelease(other.value)) 1 else -1
                }
            }
        }

        data class Text(val value: String) : Token {
            override fun compareTo(other: Token): Int = when (other) {
                is Number -> if (isPreRelease(value)) -1 else 1
                is Text -> {
                    val pre1 = isPreRelease(value)
                    val pre2 = isPreRelease(other.value)
                    if (pre1 && !pre2) -1
                    else if (!pre1 && pre2) 1
                    else value.compareTo(other.value, ignoreCase = true)
                }
            }
        }
    }

    private fun isPreRelease(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("alpha") || lower.startsWith("beta") ||
                lower.startsWith("rc") || lower.startsWith("dev") || lower.startsWith("preview")
    }

    private fun isHotfixOrSuffix(token: Token?): Boolean {
        if (token == null) return false
        return when (token) {
            is Token.Number -> token.value > 0
            is Token.Text -> {
                val lower = token.value.lowercase()
                lower.startsWith("hotfix") || lower.startsWith("patch") ||
                        lower.startsWith("p") || lower.startsWith("fix") || !isPreRelease(lower)
            }
        }
    }

    private fun tokenize(version: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val parts = version.split(Regex("[.\\-_+]"))
        for (part in parts) {
            if (part.isBlank()) continue
            val num = part.toLongOrNull()
            if (num != null) {
                tokens.add(Token.Number(num))
            } else {
                val match = Regex("""(\d+)?([a-zA-Z]+)?(\d+)?""").matchEntire(part)
                if (match != null) {
                    val g1 = match.groupValues[1]
                    val g2 = match.groupValues[2]
                    val g3 = match.groupValues[3]
                    if (g1.isNotBlank()) tokens.add(Token.Number(g1.toLong()))
                    if (g2.isNotBlank()) tokens.add(Token.Text(g2))
                    if (g3.isNotBlank()) tokens.add(Token.Number(g3.toLong()))
                } else {
                    tokens.add(Token.Text(part))
                }
            }
        }
        return tokens
    }
}
