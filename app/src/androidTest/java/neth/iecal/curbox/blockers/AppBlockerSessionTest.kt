package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.TimerNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Suppress("UNCHECKED_CAST")
class AppBlockerSessionTest {
    private val blockedApp = "com.example.blocked"
    private val groupId = "test-group"
    private lateinit var blocker: AppBlocker
    private lateinit var service: TestService
    private lateinit var timer: TimerNotification
    private lateinit var cooldowns: ConcurrentHashMap<String, Long>
    private var expiresAt = 0L

    @Before
    fun setUp() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        service = TestService(context).apply {
            activePackage = blockedApp
            lastBackPressTimeStamp = -10_000L
        }
        timer = TimerNotification(service, 1_234_567)
        TimerNotification::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .set(timer, CoroutineScope(Job().apply { cancel() }))
        blocker = AppBlocker()
        AppBlocker::class.java.getDeclaredField("service").apply { isAccessible = true }
            .set(blocker, service)
        AppBlocker::class.java.getDeclaredField("notificationManager").apply { isAccessible = true }
            .set(blocker, timer)
        AppBlocker::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(blocker, context.getSharedPreferences("app_blocker_session_test", Context.MODE_PRIVATE))

        val entryClass = AppBlocker::class.java.declaredClasses.single { it.simpleName == "AppGroupEntry" }
        val entry = entryClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(groupId, AppGroupConfig(), listOf(blockedApp),
                AppBlockerWarningScreenConfig(isOnOpenConfig = true))
        val blockedApps = AppBlocker::class.java.getDeclaredField("blockedAppsList")
            .apply { isAccessible = true }.get(blocker) as ConcurrentHashMap<String, MutableList<Any>>
        blockedApps[blockedApp] = mutableListOf(entry)
        cooldowns = AppBlocker::class.java.getDeclaredField("cooldownGroupsList")
            .apply { isAccessible = true }.get(blocker) as ConcurrentHashMap<String, Long>
        expiresAt = System.currentTimeMillis() + 86_400_000
        cooldowns[groupId] = expiresAt
    }

    @After
    fun tearDown() = onMain {
        if (::blocker.isInitialized) {
            val handler = AppBlocker::class.java.getDeclaredField("handler")
                .apply { isAccessible = true }.get(blocker) as Handler
            handler.removeCallbacksAndMessages(null)
        }
        if (::timer.isInitialized) timer.release()
        if (::service.isInitialized) service.onDestroy()
    }

    @Test
    fun contentEventFromAnotherPackageMustNotEndForegroundSession() = onMain {
        sendEvent(blockedApp)
        sendEvent("com.example.background", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        assertEquals(expiresAt, cooldowns[groupId])
    }

    @Test
    fun stateEventFromArbitraryOverlayPreservesUnlock() = onMain {
        sendEvent(blockedApp)
        sendEvent("com.example.overlay")
        sendEvent(blockedApp)

        assertEquals(expiresAt, cooldowns[groupId])
    }

    @Test
    fun unavailableActiveWindowDoesNotRevokeUnlock() = onMain {
        sendEvent(blockedApp)
        service.activePackage = null
        sendEvent("com.example.overlay")

        assertEquals(expiresAt, cooldowns[groupId])
    }

    @Test
    fun leavingBlockedAppForAnotherAppEndsUnlock() = onMain {
        sendEvent(blockedApp)
        service.activePackage = "com.example.otherapp"
        sendEvent("com.example.otherapp")

        assertFalse(cooldowns.containsKey(groupId))
    }

    @Test
    fun returningAfterGoingHomeRequiresAnotherUnlock() {
        onMain {
            sendEvent(blockedApp)
            service.activePackage = "com.example.launcher"
            sendEvent("com.example.launcher")
            service.activePackage = blockedApp
            sendEvent(blockedApp)

            assertFalse(cooldowns.containsKey(groupId))
        }
        val warning = checkNotNull(service.activities.poll(5, TimeUnit.SECONDS)) {
            "No activity was requested"
        }
        assertEquals(WarningActivity::class.java.name, warning.component?.className)
        assertEquals(blockedApp, warning.getStringExtra("launch_package"))
        assertEquals(groupId, warning.getStringExtra("result_id"))
    }

    @Test
    fun backgroundBlockedAppDoesNotInterruptFocusedApp() = onMain {
        sendEvent(blockedApp)
        service.activePackage = "com.example.floatingapp"
        sendEvent("com.example.floatingapp")
        sendEvent(blockedApp, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        assertEquals(-10_000L, service.lastBackPressTimeStamp)
        val warningVisible = AppBlocker::class.java.getDeclaredField("isWarningScreenVisible")
            .apply { isAccessible = true }.getBoolean(blocker)
        assertFalse(warningVisible)
    }

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    @Suppress("DEPRECATION")
    private fun sendEvent(packageName: String, type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        val event = AccessibilityEvent.obtain(type).apply { this.packageName = packageName }
        try {
            blocker.doAppBlockerCheck(event)
        } finally {
            event.recycle()
        }
    }

    private class TestService(context: Context) : BaseBlockingService() {
        var activePackage: String? = null
        val activities = LinkedBlockingQueue<Intent>()

        init {
            attachBaseContext(context)
        }

        @Suppress("DEPRECATION")
        override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
            val pkg = activePackage ?: return null
            return AccessibilityNodeInfo.obtain().apply { packageName = pkg }
        }

        override fun startActivity(intent: Intent) {
            activities.add(Intent(intent))
        }
    }
}
