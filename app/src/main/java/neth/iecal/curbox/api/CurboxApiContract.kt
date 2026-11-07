package neth.iecal.curbox.api

/**
 * The public contract for the Curbox API.
 *
 * Other apps bind to [CurboxApiService] over AIDL ([ICurboxApi]) and drive Curbox with the command
 * and state names below, the same way apps talk to Shizuku. Everything here is part of the public
 * surface, so keep the string values stable. Bump [API_VERSION] when the contract changes.
 */
object CurboxApiContract {

    const val API_VERSION = 7

    // Argument keys placed in the Bundle passed to execute().
    const val ARG_TARGET = "target"
    const val ARG_ENABLE = "enable"
    const val ARG_MINUTES = "minutes"
    /** Since version 4: optional exact epoch millis end for START_FOCUS. Wins over [ARG_MINUTES]. */
    const val ARG_ENDS_AT = "ends_at"
    /**
     * Since version 7: optional Pomodoro cycle for START_FOCUS. A positive [ARG_POMODORO_ROUNDS]
     * makes the session a cycle; the phase ("FOCUS" or "BREAK") and completed rounds let a client
     * join a cycle another device is already running. The session's end is the current phase's end.
     */
    const val ARG_POMODORO_PHASE = "pomodoro_phase"
    const val ARG_POMODORO_FOCUS_MINUTES = "pomodoro_focus_minutes"
    const val ARG_POMODORO_BREAK_MINUTES = "pomodoro_break_minutes"
    const val ARG_POMODORO_ROUNDS = "pomodoro_rounds"
    const val ARG_POMODORO_COMPLETED = "pomodoro_completed"
    /** Since version 7: SAVE_FOCUS_GROUP fields. [ARG_WEBSITES] and [ARG_APPS] are String ArrayLists. */
    const val ARG_NAME = "name"
    const val ARG_WEBSITES = "websites"
    const val ARG_APPS = "apps"
    const val ARG_BLOCK_MODE = "block_mode"
    const val ARG_EXITABLE = "exitable"

    // Keys used in the JSON returned by query().
    const val VAR_FOCUS_GROUP = "focus_group"
    const val VAR_FOCUS_REMAINING = "focus_remaining"
    /** Since version 4: the active group's id and exact end time in epoch millis. */
    const val VAR_FOCUS_GROUP_ID = "focus_group_id"
    const val VAR_FOCUS_ENDS_AT = "focus_ends_at"
    /** Since version 7: Pomodoro state of the active session. Phase is "" without a cycle. */
    const val VAR_FOCUS_PHASE = "focus_phase"
    const val VAR_POMODORO_FOCUS_MINUTES = "pomodoro_focus_minutes"
    const val VAR_POMODORO_BREAK_MINUTES = "pomodoro_break_minutes"
    const val VAR_POMODORO_ROUNDS = "pomodoro_rounds"
    const val VAR_POMODORO_COMPLETED = "pomodoro_completed"
    const val VAR_SCREENTIME = "screentime"
    const val VAR_REELS = "reels"

    /**
     * Since version 6: an allowed app that declares a service for this action can ask Curbox to
     * keep it running with HOST_COMPANION. Curbox's blocker service binds it, so the app needs no
     * foreground service of its own. Return null from onBind to decline.
     */
    const val ACTION_COMPANION = "neth.iecal.curbox.api.COMPANION"

    // Status strings returned by execute().
    const val STATUS_OK = "OK"
    const val STATUS_DENIED = "DENIED"
    const val STATUS_UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
    const val STATUS_FAILED = "FAILED"
}

/** Actions a client can run through execute(). Each command's args are documented in CURBOX_API.md. */
enum class ApiCommand {
    REGISTER_UNLOCK_CONDITION,
    REMOVE_UNLOCK_CONDITION,
    START_FOCUS,
    STOP_FOCUS,
    SET_APP_BLOCKER_GROUP,
    SET_KEYWORD_BLOCKER,
    SET_KEYWORD_GROUP,
    SET_REEL_BLOCKER,
    SET_UI_HIDER,
    SET_GRAYSCALE_GROUP,
    SET_REEL_COUNTER,
    SET_DND,
    /** Since version 6: enable true asks Curbox to keep the caller's companion service bound. */
    HOST_COMPANION,
    /** Since version 7: creates or updates a focus group's name, websites, mode and exit rule. */
    SAVE_FOCUS_GROUP,
    /** Since version 7: removes a focus group that is not in use. */
    DELETE_FOCUS_GROUP;

    companion object {
        fun fromNameOrNull(name: String?): ApiCommand? = entries.firstOrNull { it.name == name }
    }
}

/** States a client can read through query(). */
enum class ApiState {
    FOCUS_ACTIVE,
    SCREENTIME_TODAY,
    REELS_TODAY;

    companion object {
        fun fromNameOrNull(name: String?): ApiState? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Kinds a client can enumerate through list(). The group kinds return the ids a client needs to
 * target a specific group with execute(); STATUS returns a snapshot of every global toggle plus the
 * active focus session.
 */
enum class ApiList {
    UNLOCK_CONDITIONS,
    FOCUS_GROUPS,
    APP_BLOCKER_GROUPS,
    KEYWORD_GROUPS,
    GRAYSCALE_GROUPS,
    AUTO_DND_GROUPS,
    UI_HIDER_SCRIPTS,
    STATUS,
    /** Since version 5: daily app and website totals for the retained days. */
    USAGE_HISTORY,
    /** Since version 7: focus groups with their website lists, for companions that share them. */
    FOCUS_GROUP_DETAILS;

    companion object {
        fun fromNameOrNull(name: String?): ApiList? = entries.firstOrNull { it.name == name }
    }
}
