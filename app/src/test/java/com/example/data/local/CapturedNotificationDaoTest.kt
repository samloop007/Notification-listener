package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CapturedNotificationDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CapturedNotificationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.capturedNotificationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertBySbnKey_updatesExistingRecordInsteadOfInsertingDuplicate() = runBlocking {
        val sbnKey = "0|com.whatsapp|42|null|10055"
        val recordId = CapturedNotificationEntity.deterministicRecordId(sbnKey)

        val first = CapturedNotificationEntity(
            recordId = recordId,
            sbnKey = sbnKey,
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Maya",
            text = "Hey",
            bigText = "Hey",
            isMessagingStyle = true,
            conversationTitle = "Maya",
            messagesJson = "[\"Maya: Hey\"]",
            postTimeMs = 1000L,
            dayKey = "2026-11-16"
        )
        dao.upsertBySbnKey(first)
        dao.markBatchSynced(listOf(recordId))

        val second = first.copy(
            text = "Maya: Hey\nMaya: Are you home?",
            messagesJson = "[\"Maya: Hey\",\"Maya: Are you home?\"]",
            postTimeMs = 2000L
        )
        dao.upsertBySbnKey(second)

        val all = dao.observeRecentCaptures().first()
        assertThat(all).hasSize(1)
        val stored = all.first()
        assertThat(stored.updateCount).isEqualTo(2L)
        assertThat(stored.synced).isFalse()
        assertThat(stored.syncedOnce).isTrue()
        assertThat(stored.text).contains("Are you home?")
    }
}
