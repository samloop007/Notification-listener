package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

@Entity(
    tableName = "captured_notifications",
    indices = [
        Index(value = ["sbnKey"], unique = true),
        Index(value = ["synced", "capturedAtMs"])
    ]
)
data class CapturedNotificationEntity(
    @PrimaryKey val recordId: String,
    val sbnKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val bigText: String,
    val isMessagingStyle: Boolean,
    val conversationTitle: String,
    val messagesJson: String,
    val postTimeMs: Long,
    val dayKey: String,
    val updateCount: Long = 1L,
    val capturedAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val syncedOnce: Boolean = false,
    val retryCount: Int = 0
) {
    companion object {
        fun deterministicRecordId(sbnKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(sbnKey.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
