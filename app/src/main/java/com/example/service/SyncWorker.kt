package com.example.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.example.data.local.AppRole
import com.example.data.local.CapturedNotificationDao
import com.example.data.local.RolePreferences
import com.example.data.remote.FamilyRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: CapturedNotificationDao,
    private val rolePreferences: RolePreferences,
    private val repository: FamilyRepository,
    private val auth: FirebaseAuth
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = rolePreferences.current()
        if (prefs.role != AppRole.CHILD) {
            return Result.success()
        }

        if (prefs.familyId.isBlank() || auth.currentUser == null) {
            // Keep pending items queued in Room until pairing & Google Sign-In complete
            Log.w(TAG, "Child not paired or signed in yet; deferring sync with backoff")
            return Result.retry()
        }

        var hadFailure = false
        while (true) {
            val batch = dao.getPendingBatch(BATCH_SIZE)
            if (batch.isEmpty()) break

            val succeededIds = repository.syncNotificationBatch(prefs.familyId, batch)
            if (succeededIds.isNotEmpty()) {
                dao.markBatchSynced(succeededIds)
            }

            val failedIds = batch.map { it.recordId }.filterNot { it in succeededIds }
            if (failedIds.isNotEmpty()) {
                dao.incrementRetryCount(failedIds)
                hadFailure = true
                // Break loop and let WorkManager apply exponential backoff
                break
            }
        }

        // Prune synced local cache older than 7 days
        val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        dao.pruneSyncedOlderThan(sevenDaysAgo)

        return if (hadFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val UNIQUE_WORK_NAME = "kinecho_notification_batch_sync"
        private const val BATCH_SIZE = 20

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
