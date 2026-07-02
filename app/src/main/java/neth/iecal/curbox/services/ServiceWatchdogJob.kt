package neth.iecal.curbox.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.advanced.ServiceProtectionFragment
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ServiceProtectionManager
import java.util.concurrent.TimeUnit

/**
 * The backstop watchdog. It wakes up on a schedule even when both accessibility services are dead,
 * checks whether they are still on, and either heals them silently through Shizuku or, if it can't,
 * nudges the user with a notification. It is scheduled with setPersisted so it survives a reboot.
 *
 * It cannot beat a force stop, which also cancels this job until the app is opened again.
 */
class ServiceWatchdogJob : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            try {
                val config = DataStoreManager(applicationContext).settings.first().serviceProtectionConfig
                if (config.isEnabled) {
                    if (config.selfHealWithShizuku && ServiceProtectionManager.canSelfHeal()) {
                        ServiceProtectionManager.healNow(applicationContext)
                    } else if (!ServiceProtectionManager.areBothServicesEnabled(applicationContext)) {
                        postServicesDownNotification(applicationContext)
                    } else {
                        // Services are healthy again, take the reminder down and reset the cooldown.
                        clearServicesDownNotification(applicationContext)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "watchdog run failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "ServiceWatchdog"
        private const val JOB_ID = 7711
        private const val CHANNEL_ID = "service_protection_channel"
        private const val NOTIFICATION_ID = 7712

        // Stops the reminder from buzzing every watchdog run. We nudge at most once every few hours.
        private const val PREFS = "AppPreferences"
        private const val KEY_LAST_NOTIFIED = "service_protection_last_notified"
        private val NOTIFY_COOLDOWN_MS = TimeUnit.HOURS.toMillis(6)

        /** Schedules the periodic watchdog. Safe to call repeatedly, it just refreshes the schedule. */
        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val job = JobInfo.Builder(
                    JOB_ID,
                    ComponentName(context, ServiceWatchdogJob::class.java)
                )
                    .setPersisted(true)
                    .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                    .setRequiresDeviceIdle(false)
                    .setRequiresCharging(false)
                    .build()
                scheduler.schedule(job)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog", e)
            }
        }

        private fun postServicesDownNotification(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastNotified = prefs.getLong(KEY_LAST_NOTIFIED, 0L)
                if (System.currentTimeMillis() - lastNotified < NOTIFY_COOLDOWN_MS) return

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.service_protection_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)

                val openIntent = Intent(context, FragmentActivity::class.java).apply {
                    putExtra("fragment", ServiceProtectionFragment.FRAGMENT_ID)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pending = PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.service_protection_down_title))
                    .setContentText(context.getString(R.string.service_protection_down_text))
                    .setSmallIcon(R.drawable.icon)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()

                nm.notify(NOTIFICATION_ID, notification)
                prefs.edit().putLong(KEY_LAST_NOTIFIED, System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post notification", e)
            }
        }

        private fun clearServicesDownNotification(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_LAST_NOTIFIED).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear notification", e)
            }
        }
    }
}
