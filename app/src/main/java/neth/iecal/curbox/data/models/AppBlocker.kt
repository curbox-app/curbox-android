package neth.iecal.curbox.data.models

data class AppGroup(
    val id: String = "",
    val name: String = "name",
    val selectedPackages: List<String> = listOf(),
    val config: AppGroupConfig? = null,
    val isActive: Boolean = false,
    /** Non-zero only while the group is using the opt-in temporary-disable escape hatch. */
    val temporarilyDisabledUntilMs: Long = 0L,
    val warningScreenConfig : AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig(),
    val autoAddNewApps: Boolean = false,
    // Advanced after every check, so an auto-added app the user removes stays removed.
    val autoAddCheckedUntilMs: Long = 0L,
    // Kept only so settings written by older versions can be migrated.
    @Deprecated("Use config")
    val blockingType: AppBlockingType = AppBlockingType.Usage, // "USAGE" or "TIME"
    @Deprecated("Use config")
    val setting:String = "",
    @Deprecated("Use config")
    val linkedTimeGroupId: String? = null,
)

enum class AppBlockingType{
    Usage, Timed, OnOpen
}

data class ScheduledUsageConfig(
    val schedule: AppTimeConfig = AppTimeConfig.allDay(),
    val usage: AppUsageConfig = AppUsageConfig()
)

typealias AppGroupConfig = ScheduledUsageConfig

data class AppTimeConfig(
    var isEveryday: Boolean = true,
    var everydayIntervals: MutableList<TimeInterval> = mutableListOf(TimeInterval()),
    var dailyIntervals: MutableMap<Int, MutableList<TimeInterval>> = mutableMapOf()
) {
    companion object {
        fun allDay() = AppTimeConfig(
            isEveryday = true,
            everydayIntervals = mutableListOf(TimeInterval(0, 0, 24, 0))
        )
    }
}


data class AppUsageConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Long = 60,
    val dailyLimits: LongArray = LongArray(7) { 0 } // 0=Sunday
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppUsageConfig

        if (isDailyUniform != other.isDailyUniform) return false
        if (uniformLimit != other.uniformLimit) return false
        if (!dailyLimits.contentEquals(other.dailyLimits)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isDailyUniform.hashCode()
        result = 31 * result + uniformLimit.hashCode()
        result = 31 * result + dailyLimits.contentHashCode()
        return result
    }
}
