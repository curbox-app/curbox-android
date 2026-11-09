package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupRestrictionComparatorTest {
    private val oldGroup = group(startHour = 9, usageMinutes = 60)

    @Test
    fun lowerUsageAllowanceIsStricter() {
        assertTrue(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 9, usageMinutes = 30))
            )
        )
    }

    @Test
    fun turningOnAutoAddNewAppsIsStricter() {
        assertTrue(
            RestrictionComparator.appGroups(listOf(oldGroup), listOf(oldGroup.copy(autoAddNewApps = true)))
        )
    }

    @Test
    fun turningOffAutoAddNewAppsIsWeaker() {
        val withAutoAdd = oldGroup.copy(autoAddNewApps = true)
        assertFalse(
            RestrictionComparator.appGroups(listOf(withAutoAdd), listOf(oldGroup))
        )
    }

    @Test
    fun autoAddingAnAppAndAdvancingTheWatermarkIsStricter() {
        val withAutoAdd = oldGroup.copy(autoAddNewApps = true, autoAddCheckedUntilMs = 1L)
        val afterInstall = withAutoAdd.copy(
            selectedPackages = withAutoAdd.selectedPackages + "new.app",
            autoAddCheckedUntilMs = 2L
        )
        assertTrue(RestrictionComparator.appGroups(listOf(withAutoAdd), listOf(afterInstall)))
    }

    @Test
    fun shorterActiveScheduleIsWeaker() {
        assertFalse(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 10, usageMinutes = 60))
            )
        )
    }

    @Test
    fun addingAppDoesNotOffsetShorterActiveSchedule() {
        val proposed = group(startHour = 10, usageMinutes = 60).copy(
            selectedPackages = oldGroup.selectedPackages + "another.app"
        )
        assertFalse(RestrictionComparator.appGroups(listOf(oldGroup), listOf(proposed)))
    }

    @Test
    fun addingAppWithSameScheduleIsStricter() {
        val proposed = oldGroup.copy(
            selectedPackages = oldGroup.selectedPackages + "another.app"
        )
        assertTrue(RestrictionComparator.appGroups(listOf(oldGroup), listOf(proposed)))
    }

    @Test
    fun removingAppIsWeakerEvenWithLongerActiveSchedule() {
        val current = oldGroup.copy(
            selectedPackages = listOf("example.app", "another.app")
        )
        val proposed = group(startHour = 8, usageMinutes = 60)
        assertFalse(
            RestrictionComparator.isSameOrStricter(
                GatedSettingsField.APP_GROUPS,
                Settings(blockedAppGroups = listOf(current)),
                Settings(blockedAppGroups = listOf(proposed))
            )
        )
    }

    @Test
    fun removingAppIsWeakerEvenWithLowerUsageAllowance() {
        val current = oldGroup.copy(
            selectedPackages = listOf("example.app", "another.app")
        )
        val proposed = group(startHour = 8, usageMinutes = 30)
        assertFalse(RestrictionComparator.appGroups(listOf(current), listOf(proposed)))
    }

    @Test
    fun higherUsageAllowanceIsWeaker() {
        assertFalse(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 9, usageMinutes = 90))
            )
        )
    }

    private fun group(startHour: Int, usageMinutes: Long) = AppGroup(
        id = "group",
        selectedPackages = listOf("example.app"),
        config = AppGroupConfig(
            schedule = AppTimeConfig(
                everydayIntervals = mutableListOf(TimeInterval(startHour, 0, 17, 0))
            ),
            usage = AppUsageConfig(uniformLimit = usageMinutes)
        ),
        isActive = true
    )
}
