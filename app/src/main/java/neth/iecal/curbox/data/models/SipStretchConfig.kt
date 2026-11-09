package neth.iecal.curbox.data.models

data class SipStretchConfig(
    val isActive: Boolean = false,
    val selectedApps: List<String> = emptyList(),
    val usageLimitMinutes: Int = 30,
    val breakMinutes: Int = 5
) {
    companion object {
        const val MIN_USAGE_LIMIT_MINUTES = 5
        const val MAX_USAGE_LIMIT_MINUTES = 120
        const val MIN_BREAK_MINUTES = 1
        const val MAX_BREAK_MINUTES = 30
    }
}
