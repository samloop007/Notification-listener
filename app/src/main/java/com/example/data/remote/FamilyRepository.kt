package com.example.data.remote

import android.os.Build
import android.util.Log
import com.example.data.local.AppRole
import com.example.data.local.CapturedNotificationEntity
import com.example.data.model.FamilyMember
import com.example.data.model.GeneratedPairingCode
import com.example.data.model.ListenerAlert
import com.example.data.model.NotificationRecord
import com.example.data.model.PairingRedemptionResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FamilyRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val messaging: FirebaseMessaging
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    private fun deviceModelString(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(128)

    suspend fun fetchFcmTokenOrEmpty(): String = try {
        messaging.token.await().orEmpty().take(512)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to obtain FCM token (expected if offline or Play Services unavailable): ${e.message}")
        ""
    }

    suspend fun syncUserProfile(role: AppRole, familyId: String?) {
        val uid = requireUserId()
        val fcmToken = fetchFcmTokenOrEmpty()
        val userRef = db.collection("users").document(uid)
        val existingSnap = try {
            userRef.get().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, userRef.path, auth)
            throw e
        }

        val payload = buildMap<String, Any> {
            put("userId", uid)
            put("role", role.storageValue)
            put("deviceModel", deviceModelString())
            if (!familyId.isNullOrBlank()) put("familyId", familyId)
            if (fcmToken.isNotBlank()) put("fcmToken", fcmToken)
            put("updatedAt", FieldValue.serverTimestamp())
            if (!existingSnap.exists()) {
                put("createdAt", FieldValue.serverTimestamp())
            }
        }

        try {
            if (existingSnap.exists()) {
                userRef.update(payload).await()
            } else {
                userRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userRef.path, auth)
            throw e
        }
    }

    /**
     * Loads the signed-in user's existing family binding from /users/{uid} if present.
     */
    suspend fun getStoredFamilyBinding(): Pair<String, String>? {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        return try {
            val userDoc = userRef.get().await()
            val familyId = userDoc.getString("familyId").orEmpty()
            if (familyId.isBlank()) return null
            val familyDoc = db.collection("families").document(familyId).get().await()
            val name = familyDoc.getString("name") ?: "My Family"
            familyId to name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, userRef.path, auth)
            null
        }
    }

    /**
     * Ensures the signed-in parent owns a Family document and parent member record.
     */
    suspend fun ensureParentFamily(familyName: String = "My Family"): Pair<String, String> {
        val uid = requireUserId()
        val displayName = (auth.currentUser?.displayName ?: auth.currentUser?.email ?: "Parent").take(80)
        val fcmToken = fetchFcmTokenOrEmpty()

        val existing = getStoredFamilyBinding()
        if (existing != null) {
            if (fcmToken.isNotBlank()) {
                updateMemberFcmToken(existing.first, fcmToken)
            }
            return existing
        }

        // Check if parent already owns a family created on another session
        val ownedSnap = try {
            db.collection("families")
                .whereEqualTo("ownerUid", uid)
                .limit(1)
                .get()
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "families", auth)
            null
        }

        if (ownedSnap != null && !ownedSnap.isEmpty) {
            val doc = ownedSnap.documents.first()
            val famId = doc.id
            val famName = doc.getString("name") ?: familyName
            val existingMemberRef = db.collection("families").document(famId).collection("members").document(uid)
            val memberSnap = try { existingMemberRef.get().await() } catch (_: Exception) { null }
            if (memberSnap == null || !memberSnap.exists()) {
                val memberPayload = buildMap<String, Any> {
                    put("uid", uid)
                    put("familyId", famId)
                    put("role", "parent")
                    put("displayName", displayName)
                    put("deviceModel", deviceModelString())
                    if (fcmToken.isNotBlank()) put("fcmToken", fcmToken)
                    put("pairedAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                existingMemberRef.set(memberPayload).await()
            } else if (fcmToken.isNotBlank()) {
                updateMemberFcmToken(famId, fcmToken)
            }
            syncUserProfile(AppRole.PARENT, famId)
            return famId to famName
        }

        val newFamilyId = "fam_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val familyRef = db.collection("families").document(newFamilyId)
        val memberRef = familyRef.collection("members").document(uid)

        try {
            familyRef.set(
                mapOf(
                    "name" to familyName.take(80),
                    "ownerUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val memberPayload = buildMap<String, Any> {
                put("uid", uid)
                put("familyId", newFamilyId)
                put("role", "parent")
                put("displayName", displayName)
                put("deviceModel", deviceModelString())
                if (fcmToken.isNotBlank()) put("fcmToken", fcmToken)
                put("pairedAt", FieldValue.serverTimestamp())
                put("updatedAt", FieldValue.serverTimestamp())
            }
            memberRef.set(memberPayload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, familyRef.path, auth)
            throw e
        }

        syncUserProfile(AppRole.PARENT, newFamilyId)
        return newFamilyId to familyName
    }

    suspend fun updateMemberFcmToken(familyId: String, fcmToken: String) {
        if (familyId.isBlank() || fcmToken.isBlank()) return
        val uid = auth.currentUser?.uid ?: return
        val memberRef = db.collection("families").document(familyId).collection("members").document(uid)
        try {
            memberRef.update(
                mapOf(
                    "fcmToken" to fcmToken.take(512),
                    "deviceModel" to deviceModelString(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, memberRef.path, auth)
        }
    }

    suspend fun updateChildHealthStatus(
        familyId: String,
        listenerActive: Boolean,
        companionTrusted: Boolean
    ) {
        if (familyId.isBlank()) return
        val uid = auth.currentUser?.uid ?: return
        val memberRef = db.collection("families").document(familyId).collection("members").document(uid)
        val fcmToken = fetchFcmTokenOrEmpty()
        val payload = buildMap<String, Any> {
            put("listenerActive", listenerActive)
            put("companionTrusted", companionTrusted)
            put("deviceModel", deviceModelString())
            if (fcmToken.isNotBlank()) put("fcmToken", fcmToken)
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            memberRef.update(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, memberRef.path, auth)
        }
    }

    /**
     * Requirement 8: Parent calls Cloud Function generatePairingCode to mint a 6-character code.
     */
    suspend fun generatePairingCode(familyId: String): GeneratedPairingCode {
        requireUserId()
        val result = functions
            .getHttpsCallable("generatePairingCode")
            .call(mapOf("familyId" to familyId))
            .await()

        @Suppress("UNCHECKED_CAST")
        val data = result.getData() as? Map<String, Any?>
            ?: throw IllegalStateException("Malformed Cloud Function response.")
        val code = (data["code"] as? String).orEmpty()
        val expiresAtMs = (data["expiresAtMs"] as? Number)?.toLong() ?: 0L
        require(code.length == 6) { "Invalid pairing code returned by backend." }
        return GeneratedPairingCode(code = code, expiresAtMs = expiresAtMs)
    }

    /**
     * Requirement 8: Child enters 6-character code; Cloud Function validates and writes familyId binding.
     */
    suspend fun redeemPairingCode(code: String, childLabel: String): PairingRedemptionResult {
        requireUserId()
        val fcmToken = fetchFcmTokenOrEmpty()
        val payload = mapOf(
            "code" to code.trim().uppercase(),
            "displayName" to childLabel.ifBlank { deviceModelString() }.take(80),
            "deviceModel" to deviceModelString(),
            "fcmToken" to fcmToken
        )
        val result = functions
            .getHttpsCallable("redeemPairingCode")
            .call(payload)
            .await()

        @Suppress("UNCHECKED_CAST")
        val data = result.getData() as? Map<String, Any?>
            ?: throw IllegalStateException("Malformed Cloud Function response.")
        val familyId = (data["familyId"] as? String).orEmpty()
        val familyName = (data["familyName"] as? String).orEmpty().ifBlank { "Family" }
        require(familyId.isNotBlank()) { "Cloud Function did not return a familyId." }
        return PairingRedemptionResult(familyId = familyId, familyName = familyName)
    }

    /**
     * Requirement 5 & 2: Sync Room batch to Firestore with deterministic recordId (from StatusBarNotification.key).
     * Returns list of recordIds that succeeded so failed items remain queued for retry.
     */
    suspend fun syncNotificationBatch(
        familyId: String,
        entities: List<CapturedNotificationEntity>
    ): List<String> {
        val childUid = requireUserId()
        if (familyId.isBlank() || entities.isEmpty()) return emptyList()

        val succeeded = mutableListOf<String>()
        for (entity in entities) {
            val docRef = db.collection("families")
                .document(familyId)
                .collection("notifications")
                .document(entity.recordId)

            val parsedMessages: List<String> = try {
                json.decodeFromString<List<String>>(entity.messagesJson).take(25).map { it.take(1000) }
            } catch (_: Exception) {
                emptyList()
            }

            try {
                if (!entity.syncedOnce) {
                    val createPayload = mapOf(
                        "sbnKey" to entity.sbnKey.take(512),
                        "familyId" to familyId,
                        "childUid" to childUid,
                        "packageName" to entity.packageName.take(200),
                        "appLabel" to entity.appLabel.take(64),
                        "title" to entity.title.take(500),
                        "text" to entity.text.take(4000),
                        "bigText" to entity.bigText.take(8000),
                        "isMessagingStyle" to entity.isMessagingStyle,
                        "conversationTitle" to entity.conversationTitle.take(256),
                        "messages" to parsedMessages,
                        "postTimeMs" to entity.postTimeMs,
                        "dayKey" to entity.dayKey.take(10),
                        "updateCount" to entity.updateCount.coerceAtLeast(1L),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    docRef.set(createPayload).await()
                } else {
                    val updatePayload = mapOf(
                        "packageName" to entity.packageName.take(200),
                        "appLabel" to entity.appLabel.take(64),
                        "title" to entity.title.take(500),
                        "text" to entity.text.take(4000),
                        "bigText" to entity.bigText.take(8000),
                        "isMessagingStyle" to entity.isMessagingStyle,
                        "conversationTitle" to entity.conversationTitle.take(256),
                        "messages" to parsedMessages,
                        "postTimeMs" to entity.postTimeMs,
                        "dayKey" to entity.dayKey.take(10),
                        "updateCount" to entity.updateCount.coerceAtLeast(1L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    try {
                        docRef.update(updatePayload).await()
                    } catch (notFoundOrOther: FirebaseFirestoreException) {
                        if (notFoundOrOther.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                            val fallbackCreate = updatePayload + mapOf(
                                "sbnKey" to entity.sbnKey.take(512),
                                "familyId" to familyId,
                                "childUid" to childUid,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                            docRef.set(fallbackCreate).await()
                        } else {
                            throw notFoundOrOther
                        }
                    }
                }
                succeeded.add(entity.recordId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            }
        }
        return succeeded
    }

    /**
     * Requirement 4: Push listener revocation / watchdog health alert to parent via Firestore + Cloud Function FCM.
     */
    suspend fun pushListenerAlert(familyId: String, type: String, message: String) {
        val childUid = auth.currentUser?.uid ?: return
        if (familyId.isBlank()) return
        val alertId = "alt_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val alertRef = db.collection("families")
            .document(familyId)
            .collection("alerts")
            .document(alertId)

        val payload = mapOf(
            "familyId" to familyId,
            "childUid" to childUid,
            "type" to type,
            "message" to message.take(500),
            "acknowledged" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            alertRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, alertRef.path, auth)
        }
    }

    fun observeFamilyNotifications(familyId: String): Flow<List<NotificationRecord>> = flow {
        val uid = requireUserId()
        val path = "families/$familyId/notifications"
        emitAll(
            db.collection("families")
                .document(familyId)
                .collection("notifications")
                .orderBy("postTimeMs", Query.Direction.DESCENDING)
                .limit(200)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        NotificationRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path, auth)
                    }
                    throw error
                }
        )
    }

    fun observeFamilyMembers(familyId: String): Flow<List<FamilyMember>> = flow {
        val uid = requireUserId()
        val path = "families/$familyId/members"
        emitAll(
            db.collection("families")
                .document(familyId)
                .collection("members")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        FamilyMember::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path, auth)
                    }
                    throw error
                }
        )
    }

    fun observeFamilyAlerts(familyId: String): Flow<List<ListenerAlert>> = flow {
        val uid = requireUserId()
        val path = "families/$familyId/alerts"
        emitAll(
            db.collection("families")
                .document(familyId)
                .collection("alerts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(25)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ListenerAlert::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun acknowledgeAlert(familyId: String, alertId: String) {
        requireUserId()
        val alertRef = db.collection("families")
            .document(familyId)
            .collection("alerts")
            .document(alertId)
        try {
            alertRef.update(
                mapOf(
                    "acknowledged" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, alertRef.path, auth)
            throw e
        }
    }

    private companion object {
        const val TAG = "FamilyRepository"
    }
}
