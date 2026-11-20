package neth.iecal.curbox.data.models

enum class PomodoroPhase {
    FOCUS,
    BREAK
}

data class PomodoroState(
    val isActive: Boolean = false,
    val phase: PomodoroPhase = PomodoroPhase.FOCUS,
    val focusMinutes: Int = DEFAULT_FOCUS_MINUTES,
    val breakMinutes: Int = DEFAULT_BREAK_MINUTES,
    val totalFocusIntervals: Int = DEFAULT_FOCUS_INTERVALS,
    val completedFocusIntervals: Int = 0,
    // Links the focus rounds of this Pomodoro in focus stats.
    val cycleId: String? = null
) {
    val currentFocusInterval: Int
        get() = (completedFocusIntervals.coerceAtLeast(0) + 1).coerceAtMost(
            totalFocusIntervals.coerceIn(MIN_FOCUS_INTERVALS, MAX_FOCUS_INTERVALS)
        )

    fun normalized(): PomodoroState = copy(
        focusMinutes = focusMinutes.coerceIn(MIN_MINUTES, MAX_FOCUS_MINUTES),
        breakMinutes = breakMinutes.coerceIn(MIN_MINUTES, MAX_BREAK_MINUTES),
        totalFocusIntervals = totalFocusIntervals.coerceIn(MIN_FOCUS_INTERVALS, MAX_FOCUS_INTERVALS),
        completedFocusIntervals = completedFocusIntervals.coerceAtLeast(0)
    )

    fun nextPhase(): PomodoroState {
        val current = normalized()
        return when (current.phase) {
            PomodoroPhase.FOCUS -> {
                val completed = current.completedFocusIntervals + 1
                if (completed >= current.totalFocusIntervals) {
                    PomodoroState()
                } else {
                    current.copy(
                        phase = PomodoroPhase.BREAK,
                        completedFocusIntervals = completed
                    )
                }
            }

            PomodoroPhase.BREAK -> current.copy(phase = PomodoroPhase.FOCUS)
        }
    }

    fun currentPhaseDurationMs(): Long {
        val minutes = when (phase) {
            PomodoroPhase.FOCUS -> focusMinutes.coerceIn(MIN_MINUTES, MAX_FOCUS_MINUTES)
            PomodoroPhase.BREAK -> breakMinutes.coerceIn(MIN_MINUTES, MAX_BREAK_MINUTES)
        }
        return minutes * 60_000L
    }

    companion object {
        const val DEFAULT_FOCUS_MINUTES = 25
        const val DEFAULT_BREAK_MINUTES = 5
        const val DEFAULT_FOCUS_INTERVALS = 4
        const val MIN_MINUTES = 1
        const val MAX_FOCUS_MINUTES = 1_440
        const val MAX_BREAK_MINUTES = 120
        const val MIN_FOCUS_INTERVALS = 2
        const val MAX_FOCUS_INTERVALS = 12
    }
}
