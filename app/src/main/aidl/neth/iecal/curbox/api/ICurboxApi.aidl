// Public Curbox API. Copy this file (keeping the package) to generate the client stub.
// Integration guide: CURBOX_API.md.
package neth.iecal.curbox.api;

import neth.iecal.curbox.api.ICurboxListener;

interface ICurboxApi {
    // Version of this contract, so clients can check compatibility.
    int apiVersion();

    // True when the calling app has been allowed by the user.
    boolean isGranted();

    // Runs an action. command is one of the names in CurboxApiContract (for example
    // "START_FOCUS"). args carries the parameters that command needs ("target",
    // "enable", "minutes"). Returns a status string: OK, DENIED, UNKNOWN_COMMAND or FAILED.
    String execute(String command, in Bundle args);

    // Reads a state. state is one of FOCUS_ACTIVE, SCREENTIME_TODAY or REELS_TODAY.
    // Returns a JSON object of values, or null when not allowed or the state is unknown.
    String query(String state);

    // Lists things Curbox knows about, so a client can discover ids before acting on them.
    // kind is one of FOCUS_GROUPS, APP_BLOCKER_GROUPS, KEYWORD_GROUPS, GRAYSCALE_GROUPS,
    // AUTO_DND_GROUPS, UI_HIDER_SCRIPTS, UNLOCK_CONDITIONS (v3) or STATUS. Returns a JSON array (or object for STATUS),
    // or null when not allowed or the kind is unknown.
    String list(String kind);

    // Since version 6: calls listener.onChanged when focus or the focus groups change. Only allowed
    // apps are registered. A listener is dropped when its app dies or loses access.
    void addListener(ICurboxListener listener);

    void removeListener(ICurboxListener listener);
}
