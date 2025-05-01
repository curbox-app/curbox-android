package nethical.digipaws

class Constants {
    companion object {
        // available modes for setting up anti-uninstall
        const val ANTI_UNINSTALL_PASSWORD_MODE = 1
        const val ANTI_UNINSTALL_TIMED_MODE = 2

        // available types of warning screen
        const val WARNING_SCREEN_MODE_VIEW_BLOCKER = 1
        const val WARNING_SCREEN_MODE_APP_BLOCKER = 2

        // available types for focus mode
        const val FOCUS_MODE_BLOCK_ALL_EX_SELECTED = 1
        const val FOCUS_MODE_BLOCK_SELECTED = 2

        // available types for focus mode
        const val GRAYSCALE_MODE_ALL = 1 // apply to all apps
        const val GRAYSCALE_MODE_ONLY_SELECTED = 2 // apply to only selected
        const val GRAYSCALE_MODE_ALL_EXCEPT_SELECTED = 3 // apply to all except selected
        const val GRAYSCALE_MODE_OFF = 4 // turned off

        // availabe types for the language selector
        const val EN_SELECTED = 1
        const val FR_SELECTED = 2
        const val HELLO = 3
    }
}
