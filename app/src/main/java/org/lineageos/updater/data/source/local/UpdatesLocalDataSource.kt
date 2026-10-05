/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data.source.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdateStatus

class UpdatesLocalDataSource(private val updateDao: UpdateDao) {
    fun getUpdates(): List<Update> = updateDao.getUpdates().map { it.toUpdate() }

    fun observeUpdates(): Flow<List<Update>> =
        updateDao.observeUpdates().map { it.map(UpdateEntity::toUpdate) }

    fun addUpdate(update: Update) {
        updateDao.insertOrReplace(update.toEntity())
    }

    /**
     * Inserts a server-advertised update, or refreshes its server metadata if it is already
     * known, without touching the locally owned download state (status and path).
     */
    fun upsertServerMetadata(update: Update) {
        if (updateDao.insertIfAbsent(update.toEntity()) == -1L) {
            updateDao.updateServerMetadata(
                downloadId = update.downloadId,
                downloadUrl = update.downloadUrl,
                name = update.name,
                size = update.fileSize,
                timestamp = update.timestamp,
                type = update.type,
                version = update.version,
                osPatchLevel = update.osPatchLevel,
                osSdkLevel = update.osSdkLevel,
            )
        }
    }

    fun removeUpdate(downloadId: String) = updateDao.delete(downloadId)

    fun changeStatus(downloadId: String, status: UpdateStatus) =
        updateDao.changeStatus(downloadId, status.persistentStatus)
}
