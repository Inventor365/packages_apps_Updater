/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data.source.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the updates table.
 */
@Dao
interface UpdateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(update: UpdateEntity)

    /** @return the new row ID, or -1 if a row with the same download ID already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(update: UpdateEntity): Long

    /**
     * Refreshes the fields owned by the update server. Status and path are owned by the
     * download/verify/install lifecycle and are deliberately left untouched.
     */
    @Query(
        "UPDATE updates SET download_url = :downloadUrl, name = :name, size = :size, " +
                "timestamp = :timestamp, type = :type, version = :version, " +
                "os_patch_level = :osPatchLevel, os_sdk_level = :osSdkLevel " +
                "WHERE download_id = :downloadId"
    )
    fun updateServerMetadata(
        downloadId: String,
        downloadUrl: String?,
        name: String,
        size: Long,
        timestamp: Long,
        type: String?,
        version: String,
        osPatchLevel: String?,
        osSdkLevel: Int?,
    )

    @Query("DELETE FROM updates WHERE download_id = :downloadId")
    fun delete(downloadId: String)

    @Query("UPDATE updates SET status = :status WHERE download_id = :downloadId")
    fun changeStatus(downloadId: String, status: Int)

    @Query("SELECT * FROM updates ORDER BY timestamp DESC")
    fun getUpdates(): List<UpdateEntity>

    @Query("SELECT * FROM updates ORDER BY timestamp DESC")
    fun observeUpdates(): Flow<List<UpdateEntity>>
}
