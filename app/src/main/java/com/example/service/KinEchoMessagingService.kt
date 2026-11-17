package com.example.service

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.KinEchoApp
import com.example.MainActivity
import com.example.data.local.RolePreferences
import com.example.data.remote.FamilyRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class KinEchoMessagingService : FirebaseMessagingService() {

    @Inject lateinit var rolePreferences: RolePreferences
    @Inject lateinit var repository: FamilyRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch {
            val prefs = rolePreferences.current()
            if (prefs.familyId.isNotBlank()) {
                repository.updateMemberFcmToken(prefs.familyId, token)
            }
        }
    }

    /**
     * Requirement 6: FCM data messages carry ONLY {type, recordId, familyId}
     * keeping payloads tiny (< 4 KB) while sensitive notification payloads remain in Firestore.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"] ?: return
        val recordId = message.data["recordId"].orEmpty()
        Log.d(TAG, "Received FCM data message type=$type recordId=$recordId")

        if (type == "listener_alert") {
            postParentAlertNotification(recordId)
        }
    }

    private fun postParentAlertNotification(recordId: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        KinEchoApp.createNotificationChannels(this)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            recordId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, KinEchoApp.CHANNEL_GUARDIAN_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Child Listener Alert")
            .setContentText("Notification mirroring on the child device was interrupted. Open KinEcho for details.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(
            ALERT_NOTIF_BASE_ID + (recordId.hashCode() and 0xFF),
            notification
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KinEchoFCM"
        private const val ALERT_NOTIF_BASE_ID = 5200
    }
}
