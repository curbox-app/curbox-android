package neth.iecal.curbox.blockers

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.ScheduledUsageConfig
import neth.iecal.curbox.databinding.FragmentWarningConfigBinding
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFormController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

@RunWith(AndroidJUnit4::class)
@Suppress("UNCHECKED_CAST")
class BlockerUnlockCountdownTest {
    @Test
    fun appCountdownUsesEarliestTimedUnlockForTheSelectedPackage() {
        val blocker = AppBlocker()
        val now = System.currentTimeMillis()
        val entryClass = AppBlocker::class.java.declaredClasses.single { it.simpleName == "AppGroupEntry" }
        val constructor = entryClass.declaredConstructors.single().apply { isAccessible = true }
        val entries = AppBlocker::class.java.getDeclaredField("blockedAppsList")
            .apply { isAccessible = true }.get(blocker) as ConcurrentHashMap<String, MutableList<Any>>
        entries["com.example.selected"] = mutableListOf(
            constructor.newInstance("long", AppGroupConfig(), listOf("com.example.selected"),
                AppBlockerWarningScreenConfig(showUnlockCountdown = true)),
            constructor.newInstance("short", AppGroupConfig(), listOf("com.example.selected"),
                AppBlockerWarningScreenConfig(showUnlockCountdown = true)),
            constructor.newInstance("session", AppGroupConfig(), listOf("com.example.selected"),
                AppBlockerWarningScreenConfig(isOnOpenConfig = true, showUnlockCountdown = true)),
            constructor.newInstance("hidden", AppGroupConfig(), listOf("com.example.selected"),
                AppBlockerWarningScreenConfig()),
            constructor.newInstance("inactive-schedule", AppGroupConfig(
                schedule = AppTimeConfig(everydayIntervals = mutableListOf())
            ), listOf("com.example.selected"), AppBlockerWarningScreenConfig(showUnlockCountdown = true))
        )
        val cooldowns = AppBlocker::class.java.getDeclaredField("cooldownGroupsList")
            .apply { isAccessible = true }.get(blocker) as ConcurrentHashMap<String, Long>
        cooldowns.putAll(mapOf("long" to now + 6_000L, "short" to now + 4_000L,
            "session" to now + 1_000L, "inactive-schedule" to now + 500L,
            "hidden" to now + 100L, "unrelated" to now + 100L))

        assertEquals(4_000L, blocker.remainingUnlockMillis("com.example.selected", now))
        assertEquals(2_000L, blocker.remainingUnlockMillis("com.example.selected", now + 4_000L))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.other", now))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.selected", now + 6_000L))
        entries["com.example.selected"] = mutableListOf(constructor.newInstance(
            "short", AppGroupConfig(), listOf("com.example.selected"), AppBlockerWarningScreenConfig()
        ))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.selected", now))
    }

    @Test
    fun websiteCountdownOnlyIncludesGroupsMatchingTheCurrentWebsite() {
        val blocker = KeywordBlocker()
        val now = System.currentTimeMillis()
        val groups = listOf(
            KeywordGroup(id = "long", selectedKeywords = listOf("/restricted"),
                config = ScheduledUsageConfig(), isActive = true,
                warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true)),
            KeywordGroup(id = "short", selectedKeywords = listOf("/restricted"),
                config = ScheduledUsageConfig(), isActive = true,
                warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true)),
            KeywordGroup(id = "hidden", selectedKeywords = listOf("/restricted"),
                config = ScheduledUsageConfig(), isActive = true),
            KeywordGroup(id = "other", selectedKeywords = listOf("/unrelated"),
                config = ScheduledUsageConfig(), isActive = true,
                warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true))
        )
        KeywordBlocker::class.java.getDeclaredField("isTurnedOn").apply { isAccessible = true }
            .set(blocker, true)
        KeywordBlocker::class.java.getDeclaredField("activeGroups").apply { isAccessible = true }
            .set(blocker, groups)
        KeywordBlocker::class.java.getDeclaredField("groupPatternMap").apply { isAccessible = true }
            .set(blocker, groups.associate { it.id to blocker.compileKeywords(it.selectedKeywords) }.toMutableMap())
        val observation = KeywordBlocker::class.java.getDeclaredField("lastWebsiteObservation")
            .apply { isAccessible = true }
        observation.set(blocker, WebsiteObservation("com.example.browser", "example.test", "example.test/restricted"))
        val cooldowns = KeywordBlocker::class.java.getDeclaredField("cooldownGroupsList")
            .apply { isAccessible = true }.get(blocker) as ConcurrentHashMap<String, Long>
        cooldowns.putAll(mapOf("long" to now + 6_000L, "short" to now + 4_000L,
            "hidden" to now + 50L, "other" to now + 100L))

        assertEquals(4_000L, blocker.remainingUnlockMillis("com.example.browser", now))
        assertEquals(2_000L, blocker.remainingUnlockMillis("com.example.browser", now + 4_000L))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.other", now))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.browser", now + 6_000L))
        KeywordBlocker::class.java.getDeclaredField("activeGroups").apply { isAccessible = true }
            .set(blocker, listOf(groups.first().copy(warningScreenConfig = AppBlockerWarningScreenConfig())))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.browser", now))
        KeywordBlocker::class.java.getDeclaredField("activeGroups").apply { isAccessible = true }
            .set(blocker, groups)
        observation.set(blocker, WebsiteObservation("com.example.browser", "example.test", "example.test/normal"))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.browser", now))
    }

    @Test
    fun reelCountdownIgnoresExpiredExcludedAndDisabledUnlocks() {
        val blocker = ReelBlocker()
        val config = ReelBlocker::class.java.getDeclaredField("reelBlockerConfig")
            .apply { isAccessible = true }
        val enabled = neth.iecal.curbox.data.models.ReelBlocker(
            isActive = true, excludedPackages = listOf("com.example.excluded"),
            warningScreenConfig = AppBlockerWarningScreenConfig(showUnlockCountdown = true)
        )
        config.set(blocker, enabled)
        val cooldowns = ReelBlocker::class.java.getDeclaredField("cooldownViewIdsList")
            .apply { isAccessible = true }.get(blocker) as ConcurrentHashMap<String, Long>
        cooldowns["com.example.selected"] = 6_000L
        cooldowns["com.example.excluded"] = 6_000L

        assertEquals(5_000L, blocker.remainingUnlockMillis("com.example.selected", 1_000L))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.excluded", 1_000L))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.other", 1_000L))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.selected", 6_000L))
        config.set(blocker, enabled.copy(warningScreenConfig = AppBlockerWarningScreenConfig()))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.selected", 1_000L))
        config.set(blocker, enabled.copy(isActive = false))
        assertEquals(0L, blocker.remainingUnlockMillis("com.example.selected", 1_000L))
    }

    @Test
    fun unlockEditorShowsAndSavesCountdownForTimedUnlockModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val task = FutureTask {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Curbox)
            val fragment = object : Fragment() {
                override fun getContext(): Context = context
            }
            val binding = FragmentWarningConfigBinding.inflate(LayoutInflater.from(context))
            val controller = WarningConfigFormController(fragment, binding) {}
            val gson = Gson()
            val defaultConfig = gson.fromJson("{}", AppBlockerWarningScreenConfig::class.java)
            assertFalse(defaultConfig.showUnlockCountdown)
            for (config in listOf(
                defaultConfig,
                defaultConfig.copy(isDynamicIntervalSettingAllowed = true),
                defaultConfig.copy(isIntentRequirementEnabled = true),
                defaultConfig.copy(isQrUnlockRequirementEnabled = true),
                defaultConfig.copy(isNfcUnlockRequirementEnabled = true)
            )) {
                controller.bind(config, isNew = false, supportsOnEachOpen = true)
                assertEquals(android.view.View.VISIBLE, binding.switchUnlockCountdown.visibility)
                assertFalse(binding.switchUnlockCountdown.isChecked)
                binding.switchUnlockCountdown.isChecked = true
                val saved = controller.createConfig(emptyMap(), emptyMap())
                assertTrue(saved.showUnlockCountdown)
                val restored = gson.fromJson(gson.toJson(saved), AppBlockerWarningScreenConfig::class.java)
                controller.bind(restored, isNew = false, supportsOnEachOpen = true)
                assertTrue(binding.switchUnlockCountdown.isChecked)
                binding.switchUnlockCountdown.isChecked = false
                assertFalse(controller.createConfig(emptyMap(), emptyMap()).showUnlockCountdown)
            }
            controller.bind(defaultConfig.copy(isOnOpenConfig = true), isNew = false, supportsOnEachOpen = true)
            assertEquals(android.view.View.GONE, binding.switchUnlockCountdown.visibility)
            controller.bind(defaultConfig.copy(isProceedDisabled = true), isNew = false, supportsOnEachOpen = true)
            assertEquals(android.view.View.GONE, binding.switchUnlockCountdown.visibility)
            controller.bind(defaultConfig, isNew = true, supportsOnEachOpen = true)
            assertEquals(android.view.View.GONE, binding.switchUnlockCountdown.visibility)
        }
        instrumentation.runOnMainSync(task)
        task.get()
    }
}
