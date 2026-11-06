package neth.iecal.curbox.anti_stimulants

import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.utils.TimeTools

data class MindfulMessageVariableValues(
    val appName: String,
    val appUsageTodayMs: Long = 0L,
    val appUsageYesterdayMs: Long = 0L,
    val appOpensToday: Int = 0,
    val screenTimeTodayMs: Long = 0L,
    val screenTimeYesterdayMs: Long = 0L,
    val liveSessionMs: Long = 0L,
    val remainingUnlockMs: Long = 0L,
    val reelCount: Int = 0,
    val todayUsageByPackage: Map<String, Long> = emptyMap(),
    val yesterdayUsageByPackage: Map<String, Long> = emptyMap()
)

object MindfulMessageVariables {
    private val groupUsageTodayPattern = Regex("\\{group_usage_today:([^{}]+)\\}")
    private val groupUsageYesterdayPattern = Regex("\\{group_usage_yesterday:([^{}]+)\\}")

    fun format(
        template: String,
        values: MindfulMessageVariableValues,
        mindfulApps: Collection<String>,
        appGroups: List<AppGroup>
    ): String {
        val mindfulPackages = mindfulApps.toSet()
        val mindfulAppsUsageToday = values.todayUsageByPackage
            .filterKeys { it in mindfulPackages }
            .values
            .sum()

        return template
            .replace("{app_name}", values.appName)
            .replace("{app_usage_today}", formatUsage(values.appUsageTodayMs))
            .replace("{app_usage_yesterday}", formatUsage(values.appUsageYesterdayMs))
            .replace("{app_opens_today}", values.appOpensToday.toString())
            .replace("{screentime_today}", formatUsage(values.screenTimeTodayMs))
            .replace("{screentime_yesterday}", formatUsage(values.screenTimeYesterdayMs))
            .replace("{mindful_apps_usage_today}", formatUsage(mindfulAppsUsageToday))
            .replace("{live_session_duration}", formatSession(values.liveSessionMs))
            .replace("{remaining_unlock_time}", formatSession(values.remainingUnlockMs))
            .replace("{reel_count}", values.reelCount.toString())
            .replaceGroupUsage(groupUsageTodayPattern, values.todayUsageByPackage, appGroups)
            .replaceGroupUsage(
                groupUsageYesterdayPattern,
                values.yesterdayUsageByPackage,
                appGroups
            )
    }

    fun needsTodayUsage(template: String): Boolean = listOf(
        "{app_usage_today}",
        "{app_opens_today}",
        "{screentime_today}",
        "{mindful_apps_usage_today}"
    ).any(template::contains) || groupUsageTodayPattern.containsMatchIn(template)

    fun needsYesterdayUsage(template: String): Boolean = listOf(
        "{app_usage_yesterday}",
        "{screentime_yesterday}"
    ).any(template::contains) || groupUsageYesterdayPattern.containsMatchIn(template)

    fun needsReelCount(template: String): Boolean = template.contains("{reel_count}")

    private fun String.replaceGroupUsage(
        pattern: Regex,
        usageByPackage: Map<String, Long>,
        appGroups: List<AppGroup>
    ): String = pattern.replace(this) { match ->
        val requestedName = match.groupValues[1].trim()
        val matchingGroups = appGroups.filter { it.name.trim().equals(requestedName, ignoreCase = true) }
        if (matchingGroups.isEmpty()) {
            match.value
        } else {
            val packages = matchingGroups.flatMap { it.selectedPackages }.toSet()
            formatUsage(packages.sumOf { usageByPackage[it] ?: 0L })
        }
    }

    private fun formatUsage(durationMs: Long): String =
        TimeTools.formatTime(durationMs.coerceAtLeast(0L), false).ifEmpty { "0 mins" }

    private fun formatSession(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
