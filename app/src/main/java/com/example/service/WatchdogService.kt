package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.KinEchoApp
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppRole
import com.example.data.local.RolePreferences
import com.example.data.remote.FamilyRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class WatchdogService : Service() {

    @Inject lateinit var rolePreferences: RolePreferences
    @Inject lateinit var repository: FamilyRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        KinEchoApp.createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startWatchdogLoop()
        return START_STICKY
    }

    private fun startWatchdogLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                runHealthCheck()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun runHealthCheck() {
        val prefs = rolePreferences.current()
        if (prefs.role != AppRole.CHILD) {
            stopSelf()
            return
        }

        val permissionGranted = NotifListener.isNotificationAccessEnabled(this)
        val listenerInstance = NotifListener.currentInstance()
        val activeNotificationsOk = listenerInstance?.canReadActiveNotifications() == true
        val now = System.currentTimeMillis()

        ListenerHealthRegistry.update {
            it.copy(
                permissionGranted = permissionGranted,
                serviceBound = listenerInstance != null,
                activeNotificationsOk = activeNotificationsOk,
                lastCheckAtMs = now
            )
        }

        if (!permissionGranted) {
            Log.w(TAG, "Watchdog detected NotificationListenerService access REVOKED")
            val shouldAlert = prefs.lastListenerHealthy ||
                (now - prefs.lastRevocationAlertAtMs > ALERT_COOLDOWN_MS)

            if (shouldAlert && prefs.familyId.isNotBlank()) {
                repository.pushListenerAlert(
                    familyId = prefs.familyId,
                    type = "listener_disabled",
                    message = "Notification access was disabled on ${Build.MANUFACTURER} ${Build.MODEL}."
                )
                repository.updateChildHealthStatus(
                    familyId = prefs.familyId,
                    listenerActive = false,
                    companionTrusted = prefs.companionAssociated
                )
                rolePreferences.setLastRevocationAlertAtMs(now)
            }
            rolePreferences.setLastListenerHealthy(false)
            return
        }

        // Permission IS granted, verify getActiveNotifications() succeeds; otherwise requestRebind()
        if (!activeNotificationsOk) {
            Log.w(TAG, "Watchdog: listener unbound or getActiveNotifications() failed; calling requestRebind()")
            NotifListener.requestSystemRebind(this)
            if (prefs.familyId.isNotBlank() && prefs.lastListenerHealthy) {
                repository.pushListenerAlert(
                    familyId = prefs.familyId,
                    type = "listener_rebound",
                    message = "Listener binding stalled on ${Build.MANUFACTURER} ${Build.MODEL}; watchdog triggered requestRebind()."
                )
            }
        } else {
            if (!prefs.lastListenerHealthy && prefs.familyId.isNotBlank()) {
                repository.updateChildHealthStatus(
                    familyId = prefs.familyId,
                    listenerActive = true,
                    companionTrusted = prefs.companionAssociated
                )
            }
            rolePreferences.setLastListenerHealthy(true)
            // Also trigger SyncWorker opportunistically if any items remain queued
            SyncWorker.enqueue(this)
        }
    }

    private fun buildPersistentNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, KinEchoApp.CHANNEL_WATCHDOG)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Monitoring notification listener health for your family")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WatchdogService"
        private const val NOTIFICATION_ID = 4101
        private val CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(45)
        private val ALERT_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(15)

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }
    }
}
