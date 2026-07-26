package neth.iecal.curbox.hardcoded

import neth.iecal.curbox.data.models.UiHiderConfig
import neth.iecal.curbox.data.models.UiHiderScript

/**
 * Starter scripts shipped (disabled) with UIHider, including a worked Instagram example. View-id
 * selectors may need adjusting as target apps update their layouts.
 *
 * Notes on the selectors used here:
 *  - `appString("res_name")` resolves a string resource by name from the target app, so scripts that
 *    match localized content descriptions stay locale-independent.
 *  - A class-index hierarchy walk can be expressed with the `find(path="ClassName[0]>...")` selector,
 *    which descends to the n-th child of a given class.
 */
val DEFAULT_UIHIDER_SCRIPTS: List<UiHiderScript> = listOf(

    UiHiderScript(
        id = "ig_feed",
        packageName = "com.instagram.android",
        label = "Instagram: hide home feed except following tab",
        isEnabled = false,
        source = """
            # The stories view
            top = find(desc="reels tray container")
            nav = find(id="com.instagram.android:id/feed_tab")

            if nav != null and nav.visible and nav.selected {
                y = load("feed_top")
                if top != null and top.visible {
                    y = top.bottom
                    save("feed_top", y)
                }
                if y != null {
                    height = nav.top - y
                    if height > 0 {
                        draw(0, y, screen.width, height)
                    }
                }
            }
        """.trimIndent()
    ),

    UiHiderScript(
        id = "ig_explore",
        packageName = "com.instagram.android",
        label = "Instagram: hide explore feed",
        isEnabled = false,
        source = """
            # The the first grid item
            view = find(id="com.instagram.android:id/grid_card_layout_container")
            nav = find(id="com.instagram.android:id/feed_tab")

            if view != null and view.visible and nav != null and nav.visible {
                # Ensure anchors are within the visible screen bounds
                    y = view.top
                    height = nav.top - y
                    if height > 0 {
                        draw(0, y, screen.width, height)
                    }
                
            }
        """.trimIndent()
    ),

    UiHiderScript(
        id = "yt_video_thingies",
        packageName = "com.google.android.youtube",
        label = "YouTube: hide everything except the video",
        isEnabled = false,
        source = """
            watch = find(id="com.google.android.youtube:id/watch_list")
            if watch != null {
                hide(watch)
            }
        """.trimIndent()
    ),

    UiHiderScript(
        id = "yt_video_everything_except_results",
        packageName = "com.google.android.youtube",
        label = "YouTube: hide feed, allow search results",
        isEnabled = false,
        source = """
            filterBar = appString("accessibility_feed_filter_bar_content_description")
            query = find(id="com.google.android.youtube:id/search_query")
            logo = find(id="com.google.android.youtube:id/youtube_logo")
            if(logo!=null){
                results = find(id="com.google.android.youtube:id/results")
                if results != null {
                    hide(results)
                }
            }
        """.trimIndent()
    ),

    UiHiderScript(
        id = "x_feed_but_allow_following",
        packageName = "com.twitter.android",
        label = "X: hide 'For You', allow Following",
        isEnabled = false,
        source = """
            if app != "com.twitter.android" {
                return
            }
            forYou = appString("guide_tab_title_for_you")
            if forYou != null and find(desc=forYou, selected=true) != null {
                list = find(id="android:id/list")
                if list != null {
                    hide(list)
                }
            }
        """.trimIndent()
    ),

    UiHiderScript(
        id = "snapchat_stories",
        packageName = "com.snapchat.android",
        label = "Snapchat: Hide stories",
        isEnabled = false,
        source = """
            tab_title = find(path="android.widget.TextView[0]")
            if tab_title != null and tab_title.text == appString("plus_default_tab_stories_title"){
                back()
            }
        """.trimIndent()
    )
)

val DEFAULT_UIHIDER_SCRIPT_IDS: Set<String> = DEFAULT_UIHIDER_SCRIPTS.mapTo(HashSet()) { it.id }

fun isPresetUiHiderScript(id: String): Boolean = id in DEFAULT_UIHIDER_SCRIPT_IDS

/**
 * Older versions persisted the preset scripts inside [UiHiderConfig.scripts]. Strip them out,
 * keeping only their enabled state, so presets always take their source from code.
 */
fun UiHiderConfig.normalized(): UiHiderConfig {
    val legacyPresets = scripts.filter { it.id in DEFAULT_UIHIDER_SCRIPT_IDS }
    if (legacyPresets.isEmpty()) return this
    return copy(
        scripts = scripts.filterNot { it.id in DEFAULT_UIHIDER_SCRIPT_IDS },
        enabledPresetIds = (enabledPresetIds + legacyPresets.filter { it.isEnabled }.map { it.id }).distinct()
    )
}

/** Preset scripts (with the user's enabled state) followed by the user's own scripts. */
fun UiHiderConfig.allScripts(): List<UiHiderScript> {
    val config = normalized()
    return DEFAULT_UIHIDER_SCRIPTS.map { it.copy(isEnabled = it.id in config.enabledPresetIds) } + config.scripts
}
