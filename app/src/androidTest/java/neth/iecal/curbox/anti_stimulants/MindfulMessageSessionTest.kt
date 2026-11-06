package neth.iecal.curbox.anti_stimulants

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.overlay.MindfulMessageOverlayManager
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager
import neth.iecal.curbox.utils.DataStoreManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class MindfulMessageSessionTest {
    private val selectedApp = "com.example.selected"
    private lateinit var message: MindfulMessage
    private lateinit var service: TestService
    private lateinit var overlay: MindfulMessageOverlayManager
    private lateinit var originalView: View
    private lateinit var settings: MutableStateFlow<neth.iecal.curbox.data.models.Settings>
    @Volatile private var remainingUnlockMs = 0L
    @Volatile private var remainingUnlockMillis: (String) -> Long = { remainingUnlockMs }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() = onMain {
        service = TestService(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("Allow display over other apps for the debug app before running these tests",
            Settings.canDrawOverlays(service))
        val config = MindfulMessageConfig(isActive = true, selectedApps = listOf(selectedApp),
            messages = "Session: {live_session_duration}")
        settings = MutableStateFlow(neth.iecal.curbox.data.models.Settings(mindfulMessageConfig = config))
        val dataStore = DataStoreManager(service)
        DataStoreManager::class.java.getDeclaredField("settings").apply { isAccessible = true }
            .set(dataStore, settings)
        BaseBlockingService::class.java.getDeclaredField("dataStoreManager\$delegate")
            .apply { isAccessible = true }.set(service, lazyOf(dataStore))
        service.activePackage = selectedApp
        message = MindfulMessage { remainingUnlockMillis(it) }
        MindfulMessage::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .set(message, scope)
        message.setup(service)
        overlay = MindfulMessage::class.java.getDeclaredField("overlayManager")
            .apply { isAccessible = true }.get(message) as MindfulMessageOverlayManager
        sendEvent(selectedApp)
        assertTrue(overlay.isOverlayVisible)
        originalView = service.addedViews.single()
    }

    @After
    fun tearDown() = onMain {
        if (::overlay.isInitialized) {
            message.onDestroy()
            val overlayScope = MindfulMessageOverlayManager::class.java.getDeclaredField("scope")
                .apply { isAccessible = true }.get(overlay) as CoroutineScope
            overlayScope.cancel()
        }
        scope.cancel()
        if (::service.isInitialized) service.onDestroy()
    }

    @Test
    fun unlockRestoresPromptForTheSameAppWithoutCountdown() = onMain {
        assertTrue(settings.value.blockedAppGroups.isEmpty())
        overlay.removeOverlay()
        assertFalse(overlay.isOverlayVisible)
        val receiver = MindfulMessage::class.java.getDeclaredField("intentReceiver")
            .apply { isAccessible = true }.get(message) as BroadcastReceiver

        receiver.onReceive(service, Intent(Intent.ACTION_USER_PRESENT))

        assertTrue(overlay.isOverlayVisible)
        assertEquals(2, service.addedViews.size)
        assertNotSame(originalView, service.addedViews.last())
    }

    @Test
    fun unlockUsesTheCurrentForegroundApp() = onMain {
        overlay.removeOverlay()
        service.activePackage = "com.example.other"
        val receiver = MindfulMessage::class.java.getDeclaredField("intentReceiver")
            .apply { isAccessible = true }.get(message) as BroadcastReceiver

        receiver.onReceive(service, Intent(Intent.ACTION_USER_PRESENT))

        assertFalse(overlay.isOverlayVisible)
        service.activePackage = selectedApp
        sendEvent(selectedApp)
        assertTrue(overlay.isOverlayVisible)
        assertEquals(2, service.addedViews.size)
    }

    @Test
    fun unlockSwitchesThePromptToAnotherSelectedApp() {
        onMain {
            settings.value = settings.value.copy(mindfulMessageConfig =
                settings.value.mindfulMessageConfig.copy(selectedApps = listOf(selectedApp, "com.example.other")))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain {
            service.activePackage = "com.example.other"
            val receiver = MindfulMessage::class.java.getDeclaredField("intentReceiver")
                .apply { isAccessible = true }.get(message) as BroadcastReceiver

            receiver.onReceive(service, Intent(Intent.ACTION_USER_PRESENT))

            assertTrue(overlay.isOverlayVisible)
            assertEquals(2, service.addedViews.size)
            assertNotSame(originalView, service.addedViews.last())
        }
    }

    @Test
    fun countdownCanRemainWithoutMessagesAndDisappearsAtExpiry() {
        onMain {
            remainingUnlockMs = 60_000L
            settings.value = settings.value.copy(
                mindfulMessageConfig = MindfulMessageConfig(),
                blockedAppGroups = listOf(AppGroup(isActive = true,
                    warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true)))
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain {
            assertTrue(overlay.isOverlayVisible)
            assertEquals(listOf(originalView), service.addedViews)
            assertTrue(service.removedViews.isEmpty())
            remainingUnlockMs = 0L
        }

        assertSame(originalView, service.removals.poll(3, TimeUnit.SECONDS))
        onMain { assertFalse(overlay.isOverlayVisible) }
    }

    @Test
    fun countdownExpiryLeavesTheReelCounterOverlayVisible() {
        val reels = ReelsOverlayManager(service)
        try {
            onMain {
                reels.startDisplaying()
                remainingUnlockMs = 60_000L
                settings.value = settings.value.copy(
                    mindfulMessageConfig = MindfulMessageConfig(),
                    reelBlockerConfig = neth.iecal.curbox.data.models.ReelBlocker(isActive = true,
                        warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true))
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                assertTrue(overlay.isOverlayVisible)
                assertTrue(reels.isOverlayVisible)
                assertEquals(2, service.addedViews.size)
                remainingUnlockMs = 0L
            }
            assertSame(originalView, service.removals.poll(3, TimeUnit.SECONDS))
            onMain {
                assertFalse(overlay.isOverlayVisible)
                assertTrue(reels.isOverlayVisible)
                assertEquals(listOf(originalView), service.removedViews)
            }
        } finally {
            onMain { reels.removeOverlay() }
        }
    }

    @Test
    fun disablingTheLastCountdownHidesItBeforeBlockerSettingsRefresh() {
        val group = AppGroup(isActive = true,
            warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true))
        onMain {
            remainingUnlockMs = 60_000L
            settings.value = settings.value.copy(
                mindfulMessageConfig = MindfulMessageConfig(), blockedAppGroups = listOf(group)
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain {
            assertTrue(overlay.isOverlayVisible)
            settings.value = settings.value.copy(blockedAppGroups = listOf(
                group.copy(warningScreenConfig = group.warningScreenConfig.copy(showUnlockCountdown = false))
            ))
        }
        assertSame(originalView, service.removals.poll(3, TimeUnit.SECONDS))
        onMain { assertFalse(overlay.isOverlayVisible) }
    }

    @Test
    fun websiteCountdownDisappearsAtExpiryWithoutAppGroupsOrMessages() {
        onMain {
            remainingUnlockMs = 60_000L
            settings.value = settings.value.copy(
                mindfulMessageConfig = MindfulMessageConfig(),
                keywordBlockerConfig = neth.iecal.curbox.data.models.KeywordBlocker(isActive = true,
                    keywordGroups = listOf(neth.iecal.curbox.data.models.KeywordGroup(isActive = true,
                        warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true))))
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onMain {
            assertTrue(overlay.isOverlayVisible)
            remainingUnlockMs = 0L
        }
        assertSame(originalView, service.removals.poll(3, TimeUnit.SECONDS))
        onMain { assertFalse(overlay.isOverlayVisible) }
    }

    @Test
    fun countdownAndIntentShareTheExistingOverlay() {
        onMain {
            remainingUnlockMs = 65_000L
            settings.value = settings.value.copy(blockedAppGroups = listOf(AppGroup(isActive = true,
                warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true))))
            val receiver = MindfulMessage::class.java.getDeclaredField("intentReceiver")
                .apply { isAccessible = true }.get(message) as BroadcastReceiver
            receiver.onReceive(service, Intent(MindfulMessage.ADD_NEW_INTENT).apply {
                putExtra("package_name", selectedApp)
                putExtra("intent_text", "Check a calculation")
                putExtra("duration_ms", 65_000L)
            })
        }
        var text = ""
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain { text = originalView.findViewById<TextView>(R.id.mindful_txt).text.toString() }
            if (text.contains("Check a calculation") && text.contains("1m 5s")) break
            Thread.sleep(25)
        }
        assertTrue(text.contains("Check a calculation"))
        assertTrue(text.contains("1m 5s"))
        onMain {
            assertEquals(listOf(originalView), service.addedViews)
            assertTrue(service.removedViews.isEmpty())
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun countdownFollowsAppsInSharedAndSeparateGroups() {
        val blocker = AppBlocker()
        val groups = listOf(
            AppGroup(id = "shared", selectedPackages = listOf(selectedApp, "com.example.shared", "com.example.overlap"),
                config = AppGroupConfig(), isActive = true,
                warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true)),
            AppGroup(id = "separate", selectedPackages = listOf("com.example.separate", "com.example.another", "com.example.overlap"),
                config = AppGroupConfig(), isActive = true,
                warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true)),
            AppGroup(id = "hidden", selectedPackages = listOf("com.example.hidden"),
                config = AppGroupConfig(), isActive = true)
        )
        val entryClass = AppBlocker::class.java.declaredClasses.single { it.simpleName == "AppGroupEntry" }
        val constructor = entryClass.declaredConstructors.single().apply { isAccessible = true }
        val entries = AppBlocker::class.java.getDeclaredField("blockedAppsList")
            .apply { isAccessible = true }.get(blocker) as ConcurrentHashMap<String, MutableList<Any>>
        groups.forEach { group ->
            group.selectedPackages.forEach { pkg ->
                entries.getOrPut(pkg) { mutableListOf() }.add(constructor.newInstance(
                    group.id, group.config, group.selectedPackages, group.warningScreenConfig
                ))
            }
        }
        val now = System.currentTimeMillis()
        val clock = AtomicLong(now)
        val deadlines = mapOf("shared" to now + 90_000L, "separate" to now + 210_000L, "hidden" to now + 30_000L)
        val cooldowns = AppBlocker::class.java.getDeclaredField("cooldownGroupsList")
            .apply { isAccessible = true }.get(blocker) as ConcurrentHashMap<String, Long>
        cooldowns.putAll(deadlines)
        onMain {
            remainingUnlockMillis = { blocker.remainingUnlockMillis(it, clock.get()) }
            settings.value = settings.value.copy(
                mindfulMessageConfig = MindfulMessageConfig(), blockedAppGroups = groups
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        for ((pkg, elapsedMs, expectedTime) in listOf(
            Triple(selectedApp, 0L, "1m 30s"),
            Triple("com.example.shared", 5_000L, "1m 25s"),
            Triple("com.example.separate", 10_000L, "3m 20s"),
            Triple("com.example.another", 15_000L, "3m 15s"),
            Triple("com.example.other", 15_000L, null),
            Triple("com.example.overlap", 20_000L, "1m 10s"),
            Triple(selectedApp, 25_000L, "1m 5s"),
            Triple("com.example.hidden", 26_000L, null),
            Triple("com.example.separate", 30_000L, "3m 0s"),
            Triple("com.example.separate", 91_000L, "1m 59s"),
            Triple(selectedApp, 91_000L, null),
            Triple("com.example.separate", 150_000L, "1m 0s"),
            Triple("com.example.overlap", 150_000L, "1m 0s"),
            Triple("com.example.overlap", 210_000L, null)
        )) {
            onMain {
                clock.set(now + elapsedMs)
                service.activePackage = pkg
                sendEvent(pkg)
            }
            val expectedText = expectedTime?.let {
                service.getString(R.string.remaining_unlock_message).replace("{remaining_unlock_time}", it)
            }
            var text: String? = null
            val timeout = SystemClock.elapsedRealtime() + 3_000L
            while (SystemClock.elapsedRealtime() < timeout) {
                onMain {
                    text = if (overlay.isOverlayVisible) {
                        service.addedViews.last().findViewById<TextView>(R.id.mindful_txt).text.toString()
                    } else {
                        null
                    }
                }
                if (text == expectedText) break
                Thread.sleep(25)
            }
            assertEquals("Wrong countdown for $pkg after ${elapsedMs}ms", expectedText, text)
        }
        assertEquals(deadlines, cooldowns.toMap())
    }

    @Test
    fun backgroundContentEventMustNotRemoveForegroundPrompt() = onMain {
        sendEvent("com.example.background", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        assertTrue(overlay.isOverlayVisible)
        assertTrue(service.removedViews.isEmpty())
        assertSame(originalView, MindfulMessageOverlayManager::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }.get(overlay))
    }

    @Test
    fun arbitraryOverlayStateEventKeepsPrompt() = onMain {
        sendEvent("com.example.overlay")

        assertTrue(overlay.isOverlayVisible)
        assertTrue(service.removedViews.isEmpty())
        assertSame(originalView, MindfulMessageOverlayManager::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }.get(overlay))
    }

    @Test
    fun unavailableActiveWindowKeepsPrompt() = onMain {
        service.activePackage = null
        sendEvent("com.example.overlay", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        assertTrue(overlay.isOverlayVisible)
        assertTrue(service.removedViews.isEmpty())
        assertSame(originalView, MindfulMessageOverlayManager::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }.get(overlay))
    }

    @Test
    fun returningAfterGoingHomeStartsNewPromptSession() = onMain {
        service.activePackage = "com.example.launcher"
        sendEvent("com.example.launcher")
        assertFalse(overlay.isOverlayVisible)
        assertEquals(listOf(originalView), service.removedViews)

        service.activePackage = selectedApp
        sendEvent(selectedApp)

        assertTrue(overlay.isOverlayVisible)
        assertEquals(2, service.addedViews.size)
        assertNotSame(originalView, MindfulMessageOverlayManager::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }.get(overlay))
    }

    @Test
    fun openingCurboxSettingsEndsPromptSession() = onMain {
        service.activePackage = service.packageName
        sendEvent(service.packageName)

        assertFalse(overlay.isOverlayVisible)
        assertEquals(listOf(originalView), service.removedViews)
    }

    @Test
    fun backgroundSelectedAppDoesNotShowPromptOverFocusedApp() = onMain {
        service.activePackage = "com.example.floatingapp"
        sendEvent("com.example.floatingapp")
        assertFalse(overlay.isOverlayVisible)
        sendEvent(selectedApp, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        assertFalse(overlay.isOverlayVisible)
        assertEquals(1, service.addedViews.size)
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
            message.onEvent(event)
        } finally {
            event.recycle()
        }
    }

    private class TestService(context: Context) : BaseBlockingService() {
        var activePackage: String? = null
        val addedViews = mutableListOf<View>()
        val removedViews = mutableListOf<View>()
        val removals = LinkedBlockingQueue<View>()
        private val windowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addView" -> {
                    addedViews.add(args!![0] as View)
                    null
                }
                "removeView", "removeViewImmediate" -> {
                    removedViews.add(args!![0] as View)
                    removals.add(args[0] as View)
                    null
                }
                "updateViewLayout" -> null
                else -> error("Unexpected window manager call: ${method.name}")
            }
        } as WindowManager

        init {
            attachBaseContext(context)
        }

        @Suppress("DEPRECATION")
        override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
            val pkg = activePackage ?: return null
            return AccessibilityNodeInfo.obtain().apply { packageName = pkg }
        }

        override fun getSystemService(name: String): Any? {
            if (name == Context.WINDOW_SERVICE) return windowManager
            return super.getSystemService(name)
        }
    }
}
