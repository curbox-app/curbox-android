package neth.iecal.curbox.anti_stimulants

import neth.iecal.curbox.data.models.AppGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MindfulMessageVariablesTest {

    @Test
    fun format_replacesSimpleAndAggregateVariables() {
        val values = MindfulMessageVariableValues(
            appName = "Video",
            appUsageTodayMs = 3_600_000L,
            appUsageYesterdayMs = 1_800_000L,
            appOpensToday = 7,
            screenTimeTodayMs = 7_200_000L,
            screenTimeYesterdayMs = 5_400_000L,
            liveSessionMs = 65_000L,
            reelCount = 12,
            todayUsageByPackage = mapOf("video" to 3_600_000L, "chat" to 600_000L)
        )

        val result = MindfulMessageVariables.format(
            template = "{app_name}|{app_usage_today}|{app_usage_yesterday}|" +
                "{app_opens_today}|{screentime_today}|{screentime_yesterday}|" +
                "{mindful_apps_usage_today}|{live_session_duration}|{reel_count}",
            values = values,
            mindfulApps = listOf("video", "chat"),
            appGroups = emptyList()
        )

        assertEquals("Video|1 hr|30 mins|7|2 hr|1 hr 30 mins|1 hr 10 mins|1m 5s|12", result)
    }

    @Test
    fun format_replacesNamedGroupUsageIgnoringCaseAndDuplicatePackages() {
        val groups = listOf(
            AppGroup(name = "Social", selectedPackages = listOf("chat", "video")),
            AppGroup(name = " social ", selectedPackages = listOf("video", "photos"))
        )
        val values = MindfulMessageVariableValues(
            appName = "Chat",
            todayUsageByPackage = mapOf(
                "chat" to 600_000L,
                "video" to 1_200_000L,
                "photos" to 300_000L
            ),
            yesterdayUsageByPackage = mapOf("chat" to 300_000L)
        )

        val result = MindfulMessageVariables.format(
            template = "Today {group_usage_today:sOcIaL}. Yesterday " +
                "{group_usage_yesterday: Social }.",
            values = values,
            mindfulApps = emptyList(),
            appGroups = groups
        )

        assertEquals("Today 35 mins. Yesterday 5 mins.", result)
    }

    @Test
    fun format_keepsUnknownGroupVariableVisible() {
        val result = MindfulMessageVariables.format(
            template = "{group_usage_today:Missing}",
            values = MindfulMessageVariableValues(appName = "App"),
            mindfulApps = emptyList(),
            appGroups = emptyList()
        )

        assertEquals("{group_usage_today:Missing}", result)
    }

    @Test
    fun requirements_onlyRequestNeededData() {
        assertTrue(MindfulMessageVariables.needsTodayUsage("{group_usage_today:Social}"))
        assertTrue(MindfulMessageVariables.needsYesterdayUsage("{app_usage_yesterday}"))
        assertTrue(MindfulMessageVariables.needsReelCount("{reel_count}"))
        assertFalse(MindfulMessageVariables.needsTodayUsage("Stay mindful"))
        assertFalse(MindfulMessageVariables.needsYesterdayUsage("Stay mindful"))
        assertFalse(MindfulMessageVariables.needsReelCount("Stay mindful"))
        assertFalse(MindfulMessageVariables.needsTodayUsage("{remaining_unlock_time}"))
        assertFalse(MindfulMessageVariables.needsYesterdayUsage("{remaining_unlock_time}"))
    }

    @Test
    fun format_showsRemainingUnlockTimeIncludingSeconds() {
        val values = MindfulMessageVariableValues(appName = "App", remainingUnlockMs = 65_000L)
        assertEquals("Time left: 1m 5s", MindfulMessageVariables.format(
            "Time left: {remaining_unlock_time}", values, emptyList(), emptyList()
        ))
        assertEquals("0s", MindfulMessageVariables.format(
            "{remaining_unlock_time}", values.copy(remainingUnlockMs = 0L), emptyList(), emptyList()
        ))
    }
}
