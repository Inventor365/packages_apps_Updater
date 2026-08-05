/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.data.source.network

import android.content.Context
import android.os.SystemProperties
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lineageos.updater.R
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdatesNetworkDataSource(private val context: Context) {
    private val serverUrl: String
        get() {
            var base = SystemProperties.get("lunaris.updater.uri")
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
            require(base.startsWith("https://")) {
                "Update server URL must use HTTPS: $base"
            }
            return base
                .replace("{device}", DeviceInfoUtils.device)
        }

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun fetchUpdates(): List<NetworkUpdate> {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP status: ${response.code}")
            }

            response.body?.string() ?: throw IOException("Empty response body")
        }

        return json.decodeFromString<NetworkUpdateResponse>(responseBody).response
    }
}
