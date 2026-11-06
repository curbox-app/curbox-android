package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChangeDelaySafetyTest {
    @Test
    fun disablingCountdownWeakensTheUnlockConfiguration() {
        val config = AppBlockerWarningScreenConfig(showUnlockCountdown = true)
        assertFalse(RestrictionComparator.warningConfig(
            config, config.copy(showUnlockCountdown = false)
        ))
    }

    @Test
    fun enablingCountdownDoesNotOffsetWeakerUnlockRestrictions() {
        val config = AppBlockerWarningScreenConfig()
        assertTrue(RestrictionComparator.warningConfig(
            config, config.copy(showUnlockCountdown = true)
        ))
        assertFalse(RestrictionComparator.warningConfig(
            config, config.copy(timeInterval = config.timeInterval + 1, showUnlockCountdown = true)
        ))
    }

    private fun group(intervals: List<TimeInterval>, limit: Long = 60) = AppGroup(
        id = "group",
        isActive = true,
        selectedPackages = listOf("example.app"),
        config = AppGroupConfig(
            schedule = AppTimeConfig(everydayIntervals = intervals.toMutableList()),
            usage = AppUsageConfig(uniformLimit = limit)
        )
    )

    private fun stricter(old: AppGroup, new: AppGroup) = RestrictionComparator.isSameOrStricter(
        GatedSettingsField.APP_GROUPS,
        Settings(blockedAppGroups = listOf(old)),
        Settings(blockedAppGroups = listOf(new))
    )

    @Test
    fun splittingUsageWindowIsWeakerBecauseAllowanceResets() {
        val old = group(listOf(TimeInterval(9, 0, 17, 0)))
        val new = group(listOf(TimeInterval(9, 0, 12, 0), TimeInterval(12, 0, 17, 0)))
        assertFalse(stricter(old, new))
    }

    @Test
    fun splittingZeroAllowanceWindowDoesNotWeakenIt() {
        val old = group(listOf(TimeInterval(9, 0, 17, 0)), limit = 0)
        val new = group(listOf(TimeInterval(9, 0, 12, 0), TimeInterval(12, 0, 17, 0)), limit = 0)
        assertTrue(stricter(old, new))
    }

    @Test
    fun removingOvernightCoverageIsWeaker() {
        val old = group(listOf(TimeInterval(22, 0, 2, 0)))
        assertFalse(stricter(old, group(emptyList())))
        assertFalse(stricter(old, group(listOf(TimeInterval(22, 0, 24, 0)))))
    }

    @Test
    fun expandingOvernightWindowIsStricter() {
        val old = group(listOf(TimeInterval(22, 0, 2, 0)))
        assertTrue(stricter(old, group(listOf(TimeInterval(21, 0, 3, 0)))))
    }

    @Test
    fun temporarilyDisabledGroupCannotLoseAppsOrBeDisabledPermanently() {
        val old = group(listOf(TimeInterval(9, 0, 17, 0))).copy(
            isActive = false,
            temporarilyDisabledUntilMs = 1000L,
            selectedPackages = listOf("example.app", "another.app")
        )
        assertTrue(stricter(old, old))
        assertFalse(stricter(old, old.copy(selectedPackages = listOf("example.app"))))
        assertFalse(stricter(old, old.copy(temporarilyDisabledUntilMs = 0L)))
        assertFalse(stricter(old, old.copy(temporarilyDisabledUntilMs = 2000L)))
        assertTrue(stricter(old, old.copy(isActive = true, temporarilyDisabledUntilMs = 0L)))
    }

    @Test
    fun activeGroupCannotAcquireTemporaryDisable() {
        val old = group(listOf(TimeInterval(9, 0, 17, 0)))
        assertFalse(stricter(old, old.copy(isActive = false, temporarilyDisabledUntilMs = 1000L)))
    }

    @Test
    fun addingMorningWindowCannotResetOvernightUsageEarly() {
        val old = group(listOf(TimeInterval(22, 0, 2, 0)))
        val new = group(listOf(TimeInterval(0, 0, 2, 0), TimeInterval(22, 0, 2, 0)))
        assertFalse(stricter(old, new))
    }

    @Test
    fun applyingPendingSnapshotCannotReviveExpiredTemporaryDisable() {
        val pendingGroup = group(listOf(TimeInterval(9, 0, 17, 0))).copy(
            isActive = false, temporarilyDisabledUntilMs = 1000L
        )
        val restored = Settings(blockedAppGroups = listOf(pendingGroup)).restoreExpiredTemporaryDisables(2000L)
        assertTrue(restored.blockedAppGroups.single().isActive)
        assertEquals(0L, restored.blockedAppGroups.single().temporarilyDisabledUntilMs)
        val waiting = Settings(blockedAppGroups = listOf(pendingGroup)).restoreExpiredTemporaryDisables(500L)
        assertFalse(waiting.blockedAppGroups.single().isActive)
    }

    @Test
    fun malformedUsageLimitsCannotPassTheGate() {
        val old = group(listOf(TimeInterval(9, 0, 17, 0)))
        val invalid = old.copy(config = old.config!!.copy(
            usage = AppUsageConfig(isDailyUniform = false, dailyLimits = longArrayOf(30))
        ))
        assertFalse(stricter(old, invalid))
        assertFalse(stricter(old, group(listOf(TimeInterval(9, 0, 17, 0)), limit = -1)))
    }

    @Test
    fun overdueChangeIsScheduledImmediately() {
        val config = SettingsChangeDelayConfig(pendingChanges = listOf(
            PendingSettingsChange(appliesAtMs = 100L),
            PendingSettingsChange(appliesAtMs = 500L)
        ))
        assertEquals(200L, config.nextCheckAtMs(200L, false))
    }

    @Test
    fun tamperBlockedOverdueChangesDoNotCauseImmediateJobLoop() {
        val config = SettingsChangeDelayConfig(
            requireTamperProtectionOff = true,
            pendingChanges = listOf(PendingSettingsChange(appliesAtMs = 100L))
        )
        assertNull(config.nextCheckAtMs(200L, true))
        assertEquals(200L, config.nextCheckAtMs(200L, false))
        assertEquals(500L, config.copy(pendingChanges = config.pendingChanges +
            PendingSettingsChange(appliesAtMs = 500L)).nextCheckAtMs(200L, true))
    }

    @Test
    fun disablingTimerCannotDisableIndependentTamperGateImmediately() {
        val old = SettingsChangeDelayConfig(requireTamperProtectionOff = true)
        assertFalse(RestrictionComparator.changeDelay(old, old.copy(requireTamperProtectionOff = false)))
        assertTrue(RestrictionComparator.changeDelay(old, old.copy(isEnabled = true, delayMinutes = 60)))
    }
}
