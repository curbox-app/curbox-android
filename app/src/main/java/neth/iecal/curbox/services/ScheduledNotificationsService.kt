package neth.iecal.curbox.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.ScheduledNotificationsConfig
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Holds notifications as they arrive by snoozing them until the next delivery time. The system
 * puts them back by itself when the snooze ends, so nothing is stored here and nothing is lost
 * if this process dies. A single Curbox notification then tells the user how many came in.
 *
 * Shares a process with [AppBlockerService], so no callback may throw.
 * All state is touched only on the main thread.
 */
class ScheduledNotificationsService : NotificationListenerService() {

    companion object {
        private const val CHANNEL_ID = "ScheduledNotificationsChannel"
        private const val PING_NOTIFICATION_ID = 1005
        /** Snoozed notifications come back right at the delivery time. Do not hold them again. */
        private const val RELEASE_WINDOW_MS = 2 * 60_000L
    }

    private val crashLogger by lazy { CrashLogger(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var config = ScheduledNotificationsConfig()
    private var heldCount = 0
    private var pingAtMs = 0L

    private val pingCheck = Runnable {
        runSafely { pingIfDue(System.currentTimeMillis()) }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                DataStoreManager(applicationContext).settings.collectLatest { settings ->
                    config = settings.scheduledNotificationsConfig
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                crashLogger.logNonFatalError(e)
            }
        }
    }

    override fun onListenerConnected() {
        runSafely {
            // After a restart the count is gone, but the held notifications are still snoozed.
            if (heldCount > 0) return
            val now = System.currentTimeMillis()
            val held = snoozedNotifications?.count { !it.isGroupSummary() } ?: 0
            val next = config.nextDeliveryAtMs(now)
            if (held > 0 && next != null) {
                heldCount = held
                schedulePing(next, now)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        runSafely {
            sbn ?: return
            val now = System.currentTimeMillis()
            pingIfDue(now)
            if (!config.isActive || !shouldHold(sbn)) return

            val last = config.lastDeliveryAtMs(now)
            if (last != null && now - last < RELEASE_WINDOW_MS) return
            val next = config.nextDeliveryAtMs(now) ?: return

            snoozeNotification(sbn.key, next - now)
            if (!sbn.isGroupSummary()) heldCount++
            schedulePing(next, now)
        }
    }

    private fun shouldHold(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName || sbn.packageName in config.allowedApps) return false
        if (sbn.isOngoing || !sbn.isClearable) return false
        val notification = sbn.notification ?: return false
        // Calls and alarms need the user right now.
        if (notification.fullScreenIntent != null) return false
        return notification.category != Notification.CATEGORY_CALL &&
            notification.category != Notification.CATEGORY_ALARM
    }

    private fun StatusBarNotification.isGroupSummary() =
        (notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY != 0

    private fun schedulePing(atMs: Long, nowMs: Long) {
        if (pingAtMs == atMs) return
        pingAtMs = atMs
        handler.removeCallbacks(pingCheck)
        handler.postDelayed(pingCheck, (atMs - nowMs).coerceAtLeast(0L))
    }

    /**
     * The handler can run late after deep sleep, so this is also checked whenever a
     * notification arrives. The held ones coming back trigger it on time.
     */
    private fun pingIfDue(nowMs: Long) {
        if (pingAtMs == 0L || nowMs < pingAtMs) return
        handler.removeCallbacks(pingCheck)
        pingAtMs = 0L
        val count = heldCount
        heldCount = 0
        if (count > 0 && config.isActive) showPing(count)
    }

    private fun showPing(count: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.scheduled_notifications),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, FragmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(getString(R.string.scheduled_notifications_ping_title))
            .setContentText(
                resources.getQuantityString(R.plurals.scheduled_notifications_ping_body, count, count)
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        nm.notify(PING_NOTIFICATION_ID, notification)
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            crashLogger.logNonFatalError(e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pingCheck)
        scope.cancel()
        super.onDestroy()
    }
}
