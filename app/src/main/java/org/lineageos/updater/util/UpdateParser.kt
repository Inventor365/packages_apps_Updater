/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.json.JSONArray
import org.json.JSONObject
import org.lineageos.updater.data.source.network.NetworkUpdate

object UpdateParser {

    fun parseUpdates(jsonString: String): List<NetworkUpdate> {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            if (trimmed.startsWith("[")) {
                parseArray(JSONArray(trimmed))
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                when {
                    obj.has("response") -> {
                        val resp = obj.optJSONArray("response")
                        if (resp != null) parseArray(resp) else emptyList()
                    }
                    obj.has("updates") -> {
                        val upds = obj.optJSONArray("updates")
                        if (upds != null) parseArray(upds) else emptyList()
                    }
                    obj.has("result") -> {
                        val res = obj.optJSONArray("result")
                        if (res != null) parseArray(res) else emptyList()
                    }
                    obj.has("data") -> {
                        val data = obj.optJSONArray("data")
                        if (data != null) parseArray(data) else emptyList()
                    }
                    obj.has("filename") || obj.has("download") || obj.has("url") -> {
                        listOfNotNull(parseObject(obj))
                    }
                    else -> emptyList()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseArray(array: JSONArray): List<NetworkUpdate> {
        val list = mutableListOf<NetworkUpdate>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseObject(obj)?.let { list.add(it) }
        }
        return list
    }

    private fun parseObject(obj: JSONObject): NetworkUpdate? {
        val filename = obj.optString("filename").trim()
        val download = (if (obj.has("download")) obj.optString("download") else obj.optString("url")).trim()
        val version = obj.optString("version").trim()
        val timestamp = if (obj.has("timestamp")) obj.optLong("timestamp", 0L) else obj.optLong("datetime", 0L)
        val size = obj.optLong("size", 0L)
        val sha256 = obj.optString("sha256").trim()
        val md5 = obj.optString("md5").trim().takeIf { it.isNotBlank() }
        val buildtype = if (obj.has("buildtype")) obj.optString("buildtype") else obj.optString("type")

        val maintainer = obj.optString("maintainer").trim().takeIf { it.isNotBlank() }
        val oem = obj.optString("oem").trim().takeIf { it.isNotBlank() }
        val device = obj.optString("device").trim().takeIf { it.isNotBlank() }
        val forum = obj.optString("forum").trim().takeIf { it.isNotBlank() }
        val gapps = obj.optString("gapps").trim().takeIf { it.isNotBlank() }
        val firmware = obj.optString("firmware").trim().takeIf { it.isNotBlank() }
        val modem = obj.optString("modem").trim().takeIf { it.isNotBlank() }
        val bootloader = obj.optString("bootloader").trim().takeIf { it.isNotBlank() }
        val recovery = obj.optString("recovery").trim().takeIf { it.isNotBlank() }
        val paypal = obj.optString("paypal").trim().takeIf { it.isNotBlank() }
        val telegram = obj.optString("telegram").trim().takeIf { it.isNotBlank() }
        val osPatchLevel = obj.optString("os_patch_level").trim().takeIf { it.isNotBlank() }
        val osSdkLevel = if (obj.has("os_sdk_level")) obj.optInt("os_sdk_level").takeIf { it > 0 } else null

        if (filename.isBlank() && download.isBlank() && sha256.isBlank()) {
            return null
        }

        return NetworkUpdate(
            filename = filename,
            download = download,
            timestamp = timestamp,
            sha256 = sha256,
            size = size,
            version = version,
            md5 = md5,
            buildType = buildtype?.takeIf { it.isNotBlank() },
            maintainer = maintainer,
            oem = oem,
            device = device,
            forum = forum,
            gapps = gapps,
            firmware = firmware,
            modem = modem,
            bootloader = bootloader,
            recovery = recovery,
            paypal = paypal,
            telegram = telegram,
            osPatchLevel = osPatchLevel,
            osSdkLevel = osSdkLevel,
        )
    }

    fun prepareTargetUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.startsWith("https://github.com/") || url.startsWith("http://github.com/")) {
            url = url
                .replace("github.com/", "raw.githubusercontent.com/")
                .replace("/blob/", "/")
                .replace("/raw/", "/")
        }

        if (url.contains("raw.githubusercontent.com")) {
            val separator = if (url.contains("?")) "&" else "?"
            if (!url.contains("_nocache") && !url.contains("_t=")) {
                url += "${separator}_nocache=${System.currentTimeMillis()}"
            }
        }
        return url
    }
}
