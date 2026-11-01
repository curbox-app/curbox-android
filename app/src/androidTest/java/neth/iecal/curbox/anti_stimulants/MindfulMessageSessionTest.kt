package neth.iecal.curbox.anti_stimulants

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.overlay.MindfulMessageOverlayManager
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

@RunWith(AndroidJUnit4::class)
class MindfulMessageSessionTest {
    private val selectedApp = "com.example.selected"
    private lateinit var message: MindfulMessage
    private lateinit var service: TestService
    private lateinit var overlay: MindfulMessageOverlayManager
    private lateinit var originalView: View
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() = onMain {
        service = TestService(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("Allow display over other apps for the debug app before running these tests",
            Settings.canDrawOverlays(service))
        val config = MindfulMessageConfig(isActive = true, selectedApps = listOf(selectedApp),
            messages = "Session: {live_session_duration}")
        val settings = DataStoreManager(service)
        DataStoreManager::class.java.getDeclaredField("settings").apply { isAccessible = true }
            .set(settings, flowOf(neth.iecal.curbox.data.models.Settings(mindfulMessageConfig = config)))
        BaseBlockingService::class.java.getDeclaredField("dataStoreManager\$delegate")
            .apply { isAccessible = true }.set(service, lazyOf(settings))
        service.activePackage = selectedApp
        message = MindfulMessage()
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
