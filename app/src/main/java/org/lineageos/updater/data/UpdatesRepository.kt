/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.lineageos.updater.data.source.local.UpdatesLocalDataSource
import org.lineageos.updater.data.source.network.UpdatesNetworkDataSource
import org.lineageos.updater.data.source.network.toUpdate
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import org.lineageos.updater.notifications.NotificationHelper
import org.lineageos.updater.util.NetworkMonitor
import org.lineageos.updater.util.VersionUtils
import java.io.IOException

private const val TAG = "UpdatesRepository"

class UpdatesRepository(
    private val networkMonitor: NetworkMonitor,
    private val notificationHelper: NotificationHelper,
    private val networkDataSource: UpdatesNetworkDataSource,
    private val localDataSource: UpdatesLocalDataSource,
) {
    private val _deviceMetadata = MutableStateFlow(DeviceMetadata())
    val deviceMetadata: StateFlow<DeviceMetadata> = _deviceMetadata.asStateFlow()

    fun observeLocalUpdates(): Flow<List<Update>> = localDataSource.observeUpdates()

    /**
     * Fetches available updates from the server, syncs the local database, and posts a
     * notification if new updates are found. Callers observe [observeLocalUpdates] for results —
     * Room only emits when the stored data actually changes.
     *
     * @return the timestamp of the fetch, or null if skipped due to no network.
     * @throws IOException on network or HTTP errors.
     * @throws SerializationException if the response cannot be parsed.
     */
    suspend fun fetchUpdates(): Long? {
        if (!networkMonitor.currentNetworkState.isOnline) return null

        val networkUpdatesRaw = withContext(Dispatchers.IO) {
            networkDataSource.fetchUpdates()
        }

        _deviceMetadata.value = DeviceMetadata(
            maintainer = networkUpdatesRaw.firstNotNullOfOrNull {
                it.maintainer?.takeIf(String::isNotBlank)
            },
            device = networkUpdatesRaw.firstNotNullOfOrNull {
                it.device?.takeIf(String::isNotBlank)
            },
            forum = networkUpdatesRaw.firstNotNullOfOrNull {
                it.forum?.takeIf(String::isNotBlank)
            },
            telegram = networkUpdatesRaw.firstNotNullOfOrNull {
                it.telegram?.takeIf(String::isNotBlank)
            } ?: DeviceMetadata.DEFAULT_TELEGRAM,
            paypal = networkUpdatesRaw.firstNotNullOfOrNull {
                it.paypal?.takeIf(String::isNotBlank)
            } ?: DeviceMetadata.DEFAULT_PAYPAL,
        )

        val networkUpdates = networkUpdatesRaw
            .map { it.toUpdate(fallbackSdkLevel = DeviceInfoUtils.sdkLevel) }
            .filter { filterUpdates(it) }

        if (networkUpdates.isEmpty()) return System.currentTimeMillis()

        val networkIds = networkUpdates.map { it.downloadId }.toSet()

        val localUpdates = withContext(Dispatchers.IO) {
            localDataSource.getUpdates()
        }.associateBy { it.downloadId }

        if (localUpdates.isNotEmpty() && networkUpdates.any { it.downloadId !in localUpdates }) {
            notificationHelper.showNewUpdatesNotification()
        }

        withContext(Dispatchers.IO) {
            // Merge local state into each network update and upsert into the DB.
            // Room's observeUpdates() Flow will emit automatically if anything changed.
            networkUpdates.forEach { networkUpdate ->
                val local = localUpdates[networkUpdate.downloadId]
                val update = if (local != null && local.status.persistentStatus > 0) {
                    networkUpdate.copy(status = local.status, file = local.file)
                } else {
                    networkUpdate
                }
                localDataSource.addUpdate(update)
            }

            // Delete temp files and DB entries for updates no longer advertised by the server.
            localUpdates.values.filter {
                it.downloadId !in networkIds && it.downloadId != Update.LOCAL_ID &&
                        it.downloadUrl != null
            }.forEach {
                it.file?.delete()
                localDataSource.removeUpdate(it.downloadId)
            }
        }

        return System.currentTimeMillis()
    }

    private fun filterUpdates(update: Update): Boolean {
        if (DeviceInfoUtils.isDowngradingAllowed) {
            return true
        }

        val currentVersion = DeviceInfoUtils.buildVersion
        val currentTimestamp = DeviceInfoUtils.buildDateTimestamp

        val hasCurrentVersion = currentVersion.isNotBlank()
        val hasUpdateVersion = update.version.isNotBlank()

        val isNewerVer = hasCurrentVersion && hasUpdateVersion &&
                VersionUtils.isNewer(update.version, currentVersion)
        val isOlderVer = hasCurrentVersion && hasUpdateVersion &&
                VersionUtils.isOlder(update.version, currentVersion)
        val isSameVer = hasCurrentVersion && hasUpdateVersion &&
                VersionUtils.isSame(update.version, currentVersion)

        val hasCurrentTime = currentTimestamp > 0
        val hasUpdateTime = update.timestamp > 0

        val isNewerTime = hasCurrentTime && hasUpdateTime && update.timestamp > currentTimestamp
        val isOlderTime = hasCurrentTime && hasUpdateTime && update.timestamp < currentTimestamp
        val isSameTime = hasCurrentTime && hasUpdateTime && update.timestamp == currentTimestamp

        // Same version and same timestamp -> current running build
        if (isSameVer && isSameTime) {
            Log.d(TAG, "${update.name} is the current build (version=$currentVersion, timestamp=$currentTimestamp)")
            return false
        }

        // If version is newer (e.g. 3.12.1 vs 3.12, or 3.13 vs 3.12) -> accept
        if (isNewerVer) {
            Log.d(TAG, "${update.name} is a newer version: ${update.version} > $currentVersion")
            return true
        }

        // If timestamp is newer -> accept
        if (isNewerTime) {
            Log.d(TAG, "${update.name} has newer timestamp: ${update.timestamp} > $currentTimestamp")
            return true
        }

        // If version is older and timestamp is not strictly newer -> reject
        if (isOlderVer && !isNewerTime) {
            Log.d(TAG, "${update.name} is older version: ${update.version} < $currentVersion")
            return false
        }

        // If timestamp is older and version is not strictly newer -> reject
        if (isOlderTime && !isNewerVer) {
            Log.d(TAG, "${update.name} is older build timestamp: ${update.timestamp} < $currentTimestamp")
            return false
        }

        // If Android SDK level is explicitly lower than current SDK level and not newer -> reject
        if (update.osSdkLevel in 1 until DeviceInfoUtils.sdkLevel && !isNewerVer && !isNewerTime) {
            Log.d(TAG, "${update.name} has older Android SDK level: ${update.osSdkLevel} < ${DeviceInfoUtils.sdkLevel}")
            return false
        }

        return true
    }
}
