package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CapturedNotificationDao {

    @Query("SELECT * FROM captured_notifications WHERE recordId = :recordId LIMIT 1")
    suspend fun getByRecordId(recordId: String): CapturedNotificationEntity?

    @Query("SELECT * FROM captured_notifications WHERE sbnKey = :sbnKey LIMIT 1")
    suspend fun getBySbnKey(sbnKey: String): CapturedNotificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CapturedNotificationEntity)

    @Update
    suspend fun update(entity: CapturedNotificationEntity)

    /**
     * Deduplicates on StatusBarNotification.key (mapped 1:1 to recordId).
     * Updates to an active notification overwrite content in place, increment updateCount,
     * mark synced = false for re-upload, and preserve syncedOnce so Firestore update() rules apply.
     */
    @Transaction
    suspend fun upsertBySbnKey(incoming: CapturedNotificationEntity) {
        val existing = getBySbnKey(incoming.sbnKey)
        if (existing == null) {
            insert(incoming)
        } else {
            update(
                existing.copy(
                    packageName = incoming.packageName,
                    appLabel = incoming.appLabel,
                    title = incoming.title,
                    text = incoming.text,
                    bigText = incoming.bigText,
                    isMessagingStyle = incoming.isMessagingStyle,
                    conversationTitle = incoming.conversationTitle,
                    messagesJson = incoming.messagesJson,
                    postTimeMs = incoming.postTimeMs,
                    dayKey = incoming.dayKey,
                    updateCount = existing.updateCount + 1L,
                    updatedAtMs = System.currentTimeMillis(),
                    synced = false,
                    syncedOnce = existing.syncedOnce
                )
            )
        }
    }

    @Query("SELECT * FROM captured_notifications WHERE synced = 0 ORDER BY updatedAtMs ASC LIMIT :batchSize")
    suspend fun getPendingBatch(batchSize: Int = 20): List<CapturedNotificationEntity>

    @Query("UPDATE captured_notifications SET synced = 1, syncedOnce = 1, retryCount = 0 WHERE recordId IN (:recordIds)")
    suspend fun markBatchSynced(recordIds: List<String>)

    @Query("UPDATE captured_notifications SET retryCount = retryCount + 1 WHERE recordId IN (:recordIds)")
    suspend fun incrementRetryCount(recordIds: List<String>)

    @Query("SELECT COUNT(*) FROM captured_notifications WHERE synced = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM captured_notifications")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT * FROM captured_notifications ORDER BY postTimeMs DESC LIMIT 50")
    fun observeRecentCaptures(): Flow<List<CapturedNotificationEntity>>

    @Query("DELETE FROM captured_notifications WHERE synced = 1 AND updatedAtMs < :olderThanMs")
    suspend fun pruneSyncedOlderThan(olderThanMs: Long)
}
