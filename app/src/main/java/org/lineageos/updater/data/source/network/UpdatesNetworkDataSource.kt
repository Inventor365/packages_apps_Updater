/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data.source.network

import android.content.Context
import android.os.SystemProperties
import android.util.Log
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lineageos.updater.R
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import org.lineageos.updater.util.UpdateParser
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdatesNetworkDataSource(private val context: Context) {
    private val serverUrl: String
        get() {
            var base = SystemProperties.get("lunaris.updater.uri")
            if (base.isEmpty()) {
                base = SystemProperties.get("lineage.updater.uri")
            }
            if (base.isEmpty()) {
                val hasGMS = SystemProperties.getBoolean("with_google_apps", false) ||
                        SystemProperties.getBoolean("persist.sys.with_google_apps", false)
                val urlResId = if (hasGMS) {
                    R.string.updater_server_url
                } else {
                    R.string.updater_server_url_vanilla
                }
                base = context.getString(urlResId)
            }
            base = base.trim()
            require(base.startsWith("https://") || base.startsWith("http://")) {
                "Update server URL must use HTTP/HTTPS: $base"
            }
            return base.replace("{device}", DeviceInfoUtils.device)
        }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchUpdates(): List<NetworkUpdate> {
        val targetUrl = UpdateParser.prepareTargetUrl(serverUrl)
        Log.d(TAG, "Fetching updates from: $targetUrl")

        val request = Request.Builder()
            .url(targetUrl)
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .header("Pragma", "no-cache")
            .header("User-Agent", "LunarisUpdater/1.0 (Android ${DeviceInfoUtils.androidVersion}; ${DeviceInfoUtils.device})")
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP status: ${response.code} for URL: $targetUrl")
            }

            response.body?.string() ?: throw IOException("Empty response body from $targetUrl")
        }

        val updates = UpdateParser.parseUpdates(responseBody)
        Log.d(TAG, "Parsed ${updates.size} updates from response")
        return updates
    }

    companion object {
        private const val TAG = "UpdatesNetworkDataSource"
    }
}
