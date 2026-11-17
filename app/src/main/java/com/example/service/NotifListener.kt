package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.data.local.AppRole
import com.example.data.local.CapturedNotificationDao
import com.example.data.local.CapturedNotificationEntity
import com.example.data.local.RolePreferences
import com.example.data.model.MonitoredApp
import com.example.data.remote.FamilyRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NotifListener : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotifListenerEntryPoint {
        fun dao(): CapturedNotificationDao
        fun rolePreferences(): RolePreferences
        fun familyRepository(): FamilyRepository
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val entryPoint: NotifListenerEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, NotifListenerEntryPoint::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
    }

    override fun onDestroy() {
        if (instanceRef.get() === this) {
            instanceRef = WeakReference(null)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instanceRef = WeakReference(this)
        Log.i(TAG, "NotificationListenerService connected")
        ListenerHealthRegistry.update {
            it.copy(
                permissionGranted = true,
                serviceBound = true,
                activeNotificationsOk = canReadActiveNotifications(),
                lastCheckAtMs = System.currentTimeMillis()
            )
        }
        serviceScope.launch {
            val prefs = entryPoint.rolePreferences().current()
            entryPoint.rolePreferences().setLastListenerHealthy(true)
            if (prefs.role == AppRole.CHILD && prefs.familyId.isNotBlank()) {
                entryPoint.familyRepository().updateChildHealthStatus(
                    familyId = prefs.familyId,
                    listenerActive = true,
                    companionTrusted = prefs.companionAssociated
                )
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListenerService disconnected")
        ListenerHealthRegistry.update {
            it.copy(
                serviceBound = false,
                activeNotificationsOk = false,
                lastCheckAtMs = System.currentTimeMillis()
            )
        }
        // Request immediate OS rebind if permission is still granted
        if (isNotificationAccessEnabled(this)) {
            requestRebind(ComponentName(this, NotifListener::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Requirement 2: Skip package's own notifications and FLAG_ONGOING_EVENT
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        // Filter strictly to monitored apps: [WhatsApp, Messages, Messenger, Telegram]
        if (sbn.packageName !in MonitoredApp.ALL_MONITORED_PACKAGES) return

        serviceScope.launch {
            val prefs = entryPoint.rolePreferences().current()
            if (prefs.role != AppRole.CHILD) return@launch

            val entity = extractEntity(sbn, notification)
            // Requirement 2 & 5: Deduplicate on StatusBarNotification.key and persist to Room first
            entryPoint.dao().upsertBySbnKey(entity)

            // Trigger WorkManager batch upload with exponential backoff
            SyncWorker.enqueue(applicationContext)
        }
    }

    private fun extractEntity(
        sbn: StatusBarNotification,
        notification: Notification
    ): CapturedNotificationEntity {
        val extras = notification.extras
        val extraTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val extraText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val extraBigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()

        // Requirement 1: For chat apps, extract NotificationCompat.MessagingStyle messages instead of EXTRA_TEXT
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val extractedMessages = mutableListOf<String>()
        var conversationTitle = ""
        var isMessagingStyle = false

        if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
            isMessagingStyle = true
            conversationTitle = messagingStyle.conversationTitle?.toString().orEmpty().trim()
            messagingStyle.messages.takeLast(25).forEach { msg ->
                val sender = msg.person?.name?.toString()?.trim()
                    ?: extraTitle.ifBlank { "Sender" }
                val body = msg.text?.toString()?.trim().orEmpty()
                if (body.isNotEmpty()) {
                    extractedMessages.add("$sender: $body")
                }
            }
        }

        val resolvedText = if (isMessagingStyle && extractedMessages.isNotEmpty()) {
            extractedMessages.joinToString("\n")
        } else {
            extraBigText.ifBlank { extraText }
        }

        // Heuristic check for Android 15+ OTP redaction when untrusted
        if (
            extraText.contains("Sensitive notification content hidden", ignoreCase = true) ||
            resolvedText.contains("Sensitive notification content hidden", ignoreCase = true)
        ) {
            ListenerHealthRegistry.update { it.copy(otpRedactionDetected = true) }
        }

        val postTimeMs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dayKey = Instant.ofEpochMilli(postTimeMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

        val recordId = CapturedNotificationEntity.deterministicRecordId(sbn.key)
        return CapturedNotificationEntity(
            recordId = recordId,
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            appLabel = MonitoredApp.displayLabelFor(sbn.packageName),
            title = extraTitle.ifBlank { conversationTitle },
            text = resolvedText,
            bigText = extraBigText,
            isMessagingStyle = isMessagingStyle,
            conversationTitle = conversationTitle,
            messagesJson = json.encodeToString(extractedMessages),
            postTimeMs = postTimeMs,
            dayKey = dayKey,
            updateCount = 1L,
            capturedAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            synced = false,
            syncedOnce = false,
            retryCount = 0
        )
    }

    fun canReadActiveNotifications(): Boolean {
        return try {
            // Calling getActiveNotifications() verifies the binder connection is live and not dead/revoked
            activeNotifications != null
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveNotifications threw SecurityException", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "getActiveNotifications failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "NotifListener"
        @Volatile
        private var instanceRef: WeakReference<NotifListener?> = WeakReference(null)

        fun currentInstance(): NotifListener? = instanceRef.get()

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
            if (context.packageName in enabledPackages) return true

            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val expected = ComponentName(context, NotifListener::class.java).flattenToString()
            return flat.split(":").any { it.equals(expected, ignoreCase = true) }
        }

        fun requestSystemRebind(context: Context) {
            try {
                requestRebind(ComponentName(context, NotifListener::class.java))
                ListenerHealthRegistry.update {
                    it.copy(lastRebindAttemptAtMs = System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind failed: ${e.message}")
            }
        }
    }
}
