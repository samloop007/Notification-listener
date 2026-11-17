package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class NotificationRecord(
    @DocumentId val id: String = "",
    val sbnKey: String = "",
    val familyId: String = "",
    val childUid: String = "",
    val packageName: String = "",
    val appLabel: String = "",
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val isMessagingStyle: Boolean = false,
    val conversationTitle: String = "",
    val messages: List<String> = emptyList(),
    val postTimeMs: Long = 0L,
    val dayKey: String = "",
    val updateCount: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Family(
    @DocumentId val id: String = "",
    val name: String = "",
    val ownerUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FamilyMember(
    @DocumentId val id: String = "",
    val uid: String = "",
    val familyId: String = "",
    val role: String = "",
    val displayName: String = "",
    val deviceModel: String = "",
    val fcmToken: String = "",
    val listenerActive: Boolean = false,
    val companionTrusted: Boolean = false,
    @ServerTimestamp val pairedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ListenerAlert(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val childUid: String = "",
    val type: String = "",
    val message: String = "",
    val acknowledged: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class GeneratedPairingCode(
    val code: String,
    val expiresAtMs: Long
)

data class PairingRedemptionResult(
    val familyId: String,
    val familyName: String
)
