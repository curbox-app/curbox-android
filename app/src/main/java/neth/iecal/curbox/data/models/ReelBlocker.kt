package neth.iecal.curbox.data.models

import com.google.gson.Gson

data class ReelBlocker(
    val warningScreenConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig(),
    val config: ReelBlockerConfig? = null,
    @Deprecated("Use config")
    val blockingType: ReelBlockingType = ReelBlockingType.TIMED,
    @Deprecated("Use config")
    val settings: String = "",
    val isActive: Boolean = false,
    val temporarilyDisabledUntilMs: Long = 0L,
    val excludedPackages: List<String> = emptyList(),
    val allowInstagramReelsFromDmInbox: Boolean = false
)

data class ReelBlockerConfig(
    val schedule: ReelTimeConfig = ReelTimeConfig(),
    val usage: ReelUsageConfig = ReelUsageConfig(uniformLimit = 0),
    val reelCount: ReelCountConfig = ReelCountConfig(uniformLimit = 0)
)

@Suppress("DEPRECATION")
fun ReelBlocker.upgradeLegacyConfig(gson: Gson = Gson()): ReelBlocker {
    if (config != null) return this

    val legacyConfig = when (blockingType) {
        ReelBlockingType.TIMED -> ReelBlockerConfig(
            schedule = gson.fromJsonOrNull<ReelTimeConfig>(settings) ?: ReelTimeConfig()
        )
        ReelBlockingType.USAGE -> ReelBlockerConfig(
            schedule = ReelTimeConfig.allDay(),
            usage = gson.fromJsonOrNull<ReelUsageConfig>(settings) ?: ReelUsageConfig(uniformLimit = 0)
        )
        ReelBlockingType.REEL_COUNT -> ReelBlockerConfig(
            schedule = ReelTimeConfig.allDay(),
            reelCount = gson.fromJsonOrNull<ReelCountConfig>(settings) ?: ReelCountConfig(uniformLimit = 0)
        )
    }
    return copy(config = legacyConfig, settings = "")
}

enum class ReelBlockingType{
    TIMED, USAGE, REEL_COUNT
}

data class ReelTimeConfig(
    var isEveryday: Boolean = true,
    var everydayIntervals: MutableList<TimeInterval> = mutableListOf(),
    var dailyIntervals: MutableMap<Int, MutableList<TimeInterval>> = mutableMapOf()
) {
    companion object {
        fun allDay() = ReelTimeConfig(
            everydayIntervals = mutableListOf(TimeInterval(0, 0, 24, 0))
        )
    }
}


data class ReelUsageConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Long = 0,
    val dailyLimits: LongArray = LongArray(7) { 0 } // 0=Sunday
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReelUsageConfig

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

data class ReelCountConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Int = 10,
    val dailyLimits: IntArray = IntArray(7) { 0 } // 0=Sunday
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReelCountConfig

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
