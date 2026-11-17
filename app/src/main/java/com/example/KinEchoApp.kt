package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KinEchoApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
    }

    companion object {
        const val CHANNEL_WATCHDOG = "kinecho_watchdog_channel"
        const val CHANNEL_GUARDIAN_ALERTS = "kinecho_guardian_alerts_channel"

        fun createNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            val watchdogChannel = NotificationChannel(
                CHANNEL_WATCHDOG,
                context.getString(R.string.watchdog_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.watchdog_channel_desc)
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_GUARDIAN_ALERTS,
                context.getString(R.string.alerts_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.alerts_channel_desc)
                enableVibration(true)
            }

            manager.createNotificationChannel(watchdogChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }
}
