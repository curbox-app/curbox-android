package neth.iecal.curbox.utils

import com.google.gson.Gson
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.ReelBlockingType
import neth.iecal.curbox.data.models.ReelUsageConfig
import neth.iecal.curbox.data.models.upgradeLegacyConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelBlockerRestrictionComparatorTest {

    @Test
    fun oldStoredConfigGetsEmptyExcludedPackages() {
        val config = Gson().fromJson("""{"isActive":true}""", ReelBlocker::class.java)

        assertTrue(config.excludedPackages.isEmpty())
        assertFalse(config.allowInstagramReelsFromDmInbox)
    }

    @Test
    fun addingExcludedPackageIsWeaker() {
        val old = ReelBlocker(isActive = true)
        val new = old.copy(excludedPackages = listOf("com.instagram.android"))

        assertFalse(RestrictionComparator.reelBlocker(old, new))
    }

    @Test
    fun legacyUsageConfigKeepsItsUsageLimitAndAllowsAllHours() {
        val legacy = ReelBlocker(
            blockingType = ReelBlockingType.USAGE,
            settings = Gson().toJson(ReelUsageConfig(uniformLimit = 30))
        )

        val config = legacy.upgradeLegacyConfig().config!!

        assertTrue(config.schedule.everydayIntervals.single().endHour == 24)
        assertTrue(config.usage.uniformLimit == 30L)
    }

    @Test
    fun removingExcludedPackageIsStricter() {
        val old = ReelBlocker(
            isActive = true,
            excludedPackages = listOf("com.instagram.android")
        )
        val new = old.copy(excludedPackages = emptyList())

        assertTrue(RestrictionComparator.reelBlocker(old, new))
    }

    @Test
    fun allowingInstagramReelsFromDmInboxIsWeaker() {
        val old = ReelBlocker(isActive = true)

        assertFalse(
            RestrictionComparator.reelBlocker(
                old,
                old.copy(allowInstagramReelsFromDmInbox = true)
            )
        )
    }

    @Test
    fun disallowingInstagramReelsFromDmInboxIsStricter() {
        val old = ReelBlocker(
            isActive = true,
            allowInstagramReelsFromDmInbox = true
        )

        assertTrue(
            RestrictionComparator.reelBlocker(
                old,
                old.copy(allowInstagramReelsFromDmInbox = false)
            )
        )
    }
    @Test
    fun expandingAllowedHoursIsWeaker() {
        val old = scheduledReels(9, 10)
        val expanded = scheduledReels(0, 24)
        assertFalse(RestrictionComparator.reelBlocker(old, expanded))
        assertTrue(RestrictionComparator.reelBlocker(expanded, old))
    }

    @Test
    fun movingAllowedHoursIsIncomparable() {
        assertFalse(RestrictionComparator.reelBlocker(scheduledReels(9, 10), scheduledReels(10, 11)))
    }

    @Test
    fun zeroUsageAndCountLimitsAreUnlimited() {
        val unlimited = scheduledReels(0, 24)
        val usageLimited = unlimited.copy(config = unlimited.config!!.copy(
            usage = ReelUsageConfig(uniformLimit = 30)
        ))
        val countLimited = unlimited.copy(config = unlimited.config!!.copy(
            reelCount = neth.iecal.curbox.data.models.ReelCountConfig(uniformLimit = 10)
        ))
        assertFalse(RestrictionComparator.reelBlocker(usageLimited, unlimited))
        assertFalse(RestrictionComparator.reelBlocker(countLimited, unlimited))
        assertTrue(RestrictionComparator.reelBlocker(unlimited, usageLimited))
        assertTrue(RestrictionComparator.reelBlocker(unlimited, countLimited))
    }

    @Test
    fun dailyLimitsCannotBeRemoved() {
        val old = scheduledReels(0, 24)
        val limited = old.copy(config = old.config!!.copy(
            usage = ReelUsageConfig(isDailyUniform = false, dailyLimits = LongArray(7) { 30 }),
            reelCount = neth.iecal.curbox.data.models.ReelCountConfig(
                isDailyUniform = false, dailyLimits = IntArray(7) { 10 }
            )
        ))
        assertFalse(RestrictionComparator.reelBlocker(limited, old))
        assertTrue(RestrictionComparator.reelBlocker(old, limited))
    }

    private fun scheduledReels(start: Int, end: Int) = ReelBlocker(
        isActive = true,
        config = neth.iecal.curbox.data.models.ReelBlockerConfig(
            schedule = neth.iecal.curbox.data.models.ReelTimeConfig(
                everydayIntervals = mutableListOf(neth.iecal.curbox.data.models.TimeInterval(start, 0, end, 0))
            )
        )
    )
}
