package neth.iecal.curbox.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ServiceProtectionManager
import kotlin.lazy

@SuppressLint("AccessibilityPolicy")
open class BaseBlockingService : AccessibilityService() {

    val dataStoreManager  by lazy {
        DataStoreManager(this)
    }

    /** Overridden as true by AppBlockerService so the heartbeat knows which side it is. */
    protected open val isAppBlockerService: Boolean = false

    private val protectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastHeartbeatMs = 0L

    var lastBackPressTimeStamp: Long =
        SystemClock.uptimeMillis() // prevents repetitive global actions

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundService()
        protectionScope.launch { setupProtectionOnConnect() }
    }

    private suspend fun setupProtectionOnConnect() {
        try {
            val config = dataStoreManager.settings.first().serviceProtectionConfig
            if (config.isEnabled) {
                ServiceWatchdogJob.schedule(this)
                ServiceProtectionManager.reinforceBackgroundExecution(this)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Writes this service's "alive" stamp and, if its partner in the other process has been switched
     * off, repairs both through Shizuku. Throttled so it runs at most twice a minute.
     */
    private fun maybeHeartbeat() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHeartbeatMs < 30_000L) return
        lastHeartbeatMs = now
        protectionScope.launch { runHeartbeat() }
    }

    private suspend fun runHeartbeat() {
        try {
            val config = dataStoreManager.settings.first().serviceProtectionConfig
            if (!config.isEnabled) return

            val nowMs = System.currentTimeMillis()
            dataStoreManager.updateServiceProtectionConfig {
                if (isAppBlockerService) it.copy(appBlockerLastAliveMs = nowMs)
                else it.copy(usageTrackerLastAliveMs = nowMs)
            }

            val partnerEnabled = if (isAppBlockerService) {
                ServiceProtectionManager.isUsageTrackerEnabled(this)
            } else {
                ServiceProtectionManager.isAppBlockerEnabled(this)
            }
            if (!partnerEnabled && config.selfHealWithShizuku && ServiceProtectionManager.canSelfHeal()) {
                ServiceProtectionManager.healNow(this)
            }
        } catch (_: Exception) {
        }
    }

    private fun startForegroundService() {
        val channelId = "blocking_service_channel"
        val channelName = getString(R.string.blocking_service_channel_name)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.blocking_service_channel_description)
        }
        notificationManager.createNotificationChannel(channel)

        val className = this::class.simpleName
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.blocking_service_notification_title, className))
            .setContentText(getString(R.string.blocking_service_notification_text))
            .setSmallIcon(R.drawable.icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        val notificationId = this.javaClass.simpleName.hashCode()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        maybeHeartbeat()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            protectionScope.cancel()
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
    }


    fun isDelayOver( delay: Int): Boolean {
        val currentTime = SystemClock.uptimeMillis().toFloat()
        return currentTime - lastBackPressTimeStamp > delay
    }

    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        lastBackPressTimeStamp = SystemClock.uptimeMillis()
    }

    fun pressBack() {
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBackPressTimeStamp = SystemClock.uptimeMillis()

    }
}
