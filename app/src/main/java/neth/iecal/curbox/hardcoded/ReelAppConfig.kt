package neth.iecal.curbox.hardcoded

import android.view.accessibility.AccessibilityEvent
import neth.iecal.curbox.data.models.ReelAppData

class ReelAppConfig {
    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val INSTAGRAM_DM_REEL_MARKER = "\u001Fcurbox:instagram_dm_inbox:\u001F"

        private fun isOnScreenFunction() = """
            fn isOnScreen(node) {
                if node == null or not node.visible { return false }
                return node.right > 0 and node.left < screen.width and
                    node.bottom > 0 and node.top < screen.height
            }
        """.trimIndent()

        private fun instagramScript(packageName: String) = """
            ${isOnScreenFunction()}

            # Hidden copies stay in the tree, such as the Reels tab under a reel opened from Explore.
            fn findOnScreen(viewId) {
                for node in findAll(id="$packageName:id/" + viewId) {
                    if isOnScreen(node) { return node }
                }
                return null
            }

            # Searching inside a reel page misses its children, so match window wide nodes by bounds.
            fn findInPage(page, viewId) {
                for node in findAll(id="$packageName:id/" + viewId) {
                    # Ad controls can overflow the page a little, so match by centre.
                    if isOnScreen(node) and node.cy >= page.top and node.cy <= page.bottom {
                        return node
                    }
                }
                return null
            }

            # The pager keeps its neighbours attached, so only the page that fills it is current.
            fn findFullPage(viewer) {
                for overlay in findAll(id="$packageName:id/clips_item_overlay_component") {
                    if overlay.visible and overlay.h >= viewer.h * 0.99 { return overlay }
                }
                return null
            }

            viewer = findOnScreen("clips_viewer_view_pager")
            if viewer == null { return null }

            prefix = ""
            if findOnScreen("reply_bar_container_scroll_view") != null { prefix = "$INSTAGRAM_DM_REEL_MARKER" }

            # A blank result keeps the reel screen active while a swipe is in progress.
            page = findFullPage(viewer)
            if page == null { return prefix }
            if findInPage(page, "clips_ufi_component") == null { return null }

            # The first line must stay the author because the counter dedupes by it.
            author = findInPage(page, "clips_author_username")
            if author == null or author.text == null { return prefix }
            comparator = author.text
            caption = findInPage(page, "clips_caption_component")
            if caption != null { comparator = comparator + "\n" + caption.subtreeText(4, 4000) }
            for countId in ["comment_count", "repost_count", "save_count"] {
                count = findInPage(page, countId)
                if count != null and count.text != null { comparator = comparator + "\n" + count.text }
            }

            # Each lookup is a separate read, so a swipe that started meanwhile can mix two reels.
            if findFullPage(viewer) == null { return prefix }
            return prefix + comparator
        """.trimIndent()

        private fun instagramData(packageName: String) = ReelAppData(
            scriptSource = instagramScript(packageName),
            // Seekbar updates keep content events flowing, so a dropped scroll event cannot hide a reel.
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            pagerClassName = "androidx.viewpager.widget.ViewPager"
        )

        private fun youtubeScript(packageName: String) = """
            ${isOnScreenFunction()}

            viewer = find(id="$packageName:id/reel_recycler")
            if not isOnScreen(viewer) { return null }

            comparator = find(id="$packageName:id/reel_player_page_content")
            if comparator == null { return "" }
            return comparator.subtreeText(32, 20000)
        """.trimIndent()

        val reelData: Map<String, ReelAppData> = mapOf(
            INSTAGRAM_PACKAGE to instagramData(INSTAGRAM_PACKAGE),
            "com.myinsta.android" to instagramData("com.myinsta.android"),

            "com.instagram.lite" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    reel = find(path="android.widget.FrameLayout[0]>androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[0]>android.view.ViewGroup[0]")
                    if not isOnScreen(reel) or reel.h < screen.height * 0.8 { return null }

                    if event.fromIndex < 0 { return "" }
                    return "position:" + str(event.fromIndex)
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                deduplicateComparators = false,
                initialComparator = "position:0"
            ),

            "com.google.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("com.google.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
            "app.revanced.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("app.revanced.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
            "app.morphe.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("app.morphe.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),

            "com.facebook.katana" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    reel = find(desc="Reels tab details")
                    if not isOnScreen(reel) { return null }

                    comparator = reel.parent()
                    if comparator == null { return "" }
                    return comparator.subtreeText(16, 4000)
                """.trimIndent()
            ),

            "com.snapchat.android" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    viewer = find(id="com.snapchat.android:id/spotlight_container")
                    if not isOnScreen(viewer) { return null }

                    comparator = find(id="com.snapchat.android:id/opera_viewer")
                    if comparator == null { return "" }
                    return comparator.subtreeText(32, 20000)
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            ),

            "com.zhiliaoapp.musically" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    viewer = find(id="com.zhiliaoapp.musically:id/view_pager_layout_wrapper")
                    video = find(id="com.zhiliaoapp.musically:id/long_press_layout")
                    # TikTok reports the video node as hidden even while its full screen viewer is visible.
                    if not isOnScreen(viewer) or video == null { return null }
                    if viewer.w < screen.width * 0.9 or viewer.h < screen.height * 0.75 {
                        return null
                    }

                    commentEditor = find(class="android.widget.EditText")
                    if isOnScreen(commentEditor) and commentEditor.top > screen.height * 0.6 {
                        return null
                    }

                    comparator = ""
                    author = find(id="com.zhiliaoapp.musically:id/title")
                    if isOnScreen(author) {
                        comparator = comparator + author.subtreeText(2, 1000)
                    }

                    sound = find(id="com.zhiliaoapp.musically:id/p89")
                    if isOnScreen(sound) {
                        comparator = comparator + sound.subtreeText(4, 2000)
                    }
                    return comparator
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        )

        private fun cleanYouTubeComparator(value: String): String {
            val compact = value.replace("\n", "")
            if (compact.contains("PostPostPostlike") || compact.length <= 15) return ""
            return compact.replace("Video Progress", "")
                .replace("Tap to watch live", "")
                .replace("Go to channel", "")
                .replace("soundVideo ProgressSearchMoreHomeHomeShortsShortsCreateSubscriptions", "")
                .replace("soundSearchMoreHomeHomeShortsShortsCreateSubscriptions", "")
        }
    }
}
