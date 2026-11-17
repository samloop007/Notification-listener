package com.example.data.remote

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.CapturedNotificationEntity
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw t
    }
    fail("Expected ${T::class.java.simpleName} to be thrown, but nothing was thrown")
    error("Unreachable")
}

class FamilyRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun createRepository(): FamilyRepository =
        FamilyRepository(
            db = firestore,
            auth = auth,
            functions = FirebaseFunctions.getInstance(firestore.app),
            messaging = FirebaseMessaging.getInstance()
        )

    @Test
    fun ensureParentFamily_authenticatedParent_createsFamilyAndReadsEmptyFeed() = runBlocking {
        signInTestUser("parent_${UUID.randomUUID()}@test.com")
        val repo = createRepository()

        val (familyId, familyName) = withTimeout(TIMEOUT_MS) {
            repo.ensureParentFamily("Test Family")
        }
        assertThat(familyId).isNotEmpty()
        assertThat(familyName).isEqualTo("Test Family")

        val notifications = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeFamilyNotifications(familyId).first()
        }
        assertThat(notifications).isEmpty()
    }

    @Test
    fun parentCannotWriteNotifications_permissionDenied() = runBlocking {
        signInTestUser("parent_writer_${UUID.randomUUID()}@test.com")
        val repo = createRepository()
        val (familyId, _) = withTimeout(TIMEOUT_MS) { repo.ensureParentFamily("Parent Family") }

        val sbnKey = "0|com.whatsapp|${UUID.randomUUID()}|null|1000"
        val entity = CapturedNotificationEntity(
            recordId = CapturedNotificationEntity.deterministicRecordId(sbnKey),
            sbnKey = sbnKey,
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Alex",
            text = "Hello",
            bigText = "Hello",
            isMessagingStyle = true,
            conversationTitle = "Alex",
            messagesJson = "[\"Alex: Hello\"]",
            postTimeMs = System.currentTimeMillis(),
            dayKey = "2026-11-16"
        )

        val succeeded = withTimeout(TIMEOUT_MS) {
            repo.syncNotificationBatch(familyId, listOf(entity))
        }
        assertThat(succeeded).isEmpty()
    }

    @Test
    fun unauthenticatedCaller_observeNotifications_failsWithPermissionDenied() = runBlocking {
        signInTestUser("owner_${UUID.randomUUID()}@test.com")
        val repo = createRepository()
        val (familyId, _) = withTimeout(TIMEOUT_MS) { repo.ensureParentFamily("Locked Family") }

        auth.signOut()

        // Client guard blocks unauthenticated Flow observation immediately
        assertFailsWith<IllegalStateException> {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeFamilyNotifications(familyId).first()
            }
        }

        // Firestore rules independently reject unauthenticated reads with PERMISSION_DENIED
        val exception = assertFailsWith<Exception> {
            withTimeout(FLOW_TIMEOUT_MS) {
                firestore.collection("families")
                    .document(familyId)
                    .collection("notifications")
                    .snapshots()
                    .first()
            }
        }
        val firestoreEx = checkNotNull(exception.unwrapFirestoreException()) {
            "Expected FirebaseFirestoreException, got $exception"
        }
        assertThat(firestoreEx.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    @Test
    fun crossFamilyIsolation_otherParentDenied() = runBlocking {
        signInTestUser("alice_${UUID.randomUUID()}@test.com")
        val aliceRepo = createRepository()
        val (aliceFamilyId, _) = withTimeout(TIMEOUT_MS) { aliceRepo.ensureParentFamily("Alice Family") }

        signInTestUser("bob_${UUID.randomUUID()}@test.com")
        val exception = assertFailsWith<FirebaseFirestoreException> {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("families")
                    .document(aliceFamilyId)
                    .collection("notifications")
                    .get()
                    .await()
            }
        }
        assertThat(exception.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    private companion object {
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
