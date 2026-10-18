package com.gruning.mimir.rewrite

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

internal const val MButtonKeepAliveRestartMode = Service.START_NOT_STICKY

/**
 * Keeps the user-enabled global M-button window responsive while Mimir is behind another app.
 *
 * This service owns no navigation or input interception. The native overlay remains the sole touch
 * source and [MButtonIntentAuthority] remains the sole Activity route. Foreground importance only
 * prevents Android's cached-process freezer from suspending that interactive window mid-touch.
 */
class MButtonKeepAliveService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldKeepMButtonProcessAlive(currentMButtonKeepAliveTarget(this))) {
            stopSelf(startId)
            return MButtonKeepAliveRestartMode
        }
        enterForeground()
        return MButtonKeepAliveRestartMode
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(ChannelId) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    ChannelId,
                    "Mimir button",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps the user-enabled Mimir button available over other apps."
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        // D1 (QA 2026-09-11) covers this MainActivity start too: it names the Main display. A sender's own launch
        // display still wins (Android 13 lays sender options over creator options, and SystemUI passes the display its
        // shade is on), so this decides only when the sender passes none.
        val openMimir = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
        )
        val notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("The Mimir button is active")
            .setContentText("Quick Launch and Utility stay available over other apps.")
            .setContentIntent(openMimir)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // POST_NOTIFICATIONS is intentionally not a prerequisite: Android always exposes an active FGS in
        // Task Manager, while the user-enabled floating M is itself visible. Mimir asks for it once, at the
        // first start after the M button is turned on (O13, MimirNotificationPermissionAsk); a decline only
        // keeps this notice out of the shade, and this service starts either way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationId, notification)
        }
    }

    companion object {
        private const val Tag = "MimirMKeepAlive"
        private const val ChannelId = "mimir_m_button"
        private const val NotificationId = 0x4D

        fun ensureStarted(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, MButtonKeepAliveService::class.java),
            )
        }.onFailure { failure ->
            Log.w(Tag, "could not retain M-button process", failure)
        }.isSuccess

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, MButtonKeepAliveService::class.java),
            )
        }
    }
}
