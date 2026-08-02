package neth.iecal.curbox.hardcoded

import android.view.accessibility.AccessibilityEvent
import neth.iecal.curbox.data.models.ReelAppData
import java.util.UUID

class ReelAppConfig {
    companion object{
        val reelData: Map<String, ReelAppData> = mapOf(
            "com.instagram.android" to ReelAppData(
                viewId = "id:com.instagram.android:id/clips_viewer_view_pager",
                requiresPresent = listOf("id:com.instagram.android:id/clips_ufi_component"),
                dynamicComparator = listOf("id:com.instagram.android:id/clips_captions_component", "id:com.instagram.android:id/clips_author_username")
            ),

            "com.myinsta.android" to ReelAppData(
                viewId = "id:com.myinsta.android:id/clips_viewer_view_pager",
                requiresPresent = listOf("id:com.myinsta.android:id/clips_ufi_component"),
                dynamicComparator = listOf("id:com.myinsta.android:id/clips_captions_component", "id:com.myinsta.android:id/clips_author_username")
            ),

            "com.google.android.youtube" to ReelAppData(
                viewId = "id:com.google.android.youtube:id/reel_recycler",
                requiresPresent = listOf(),
                dynamicComparator = listOf("id:com.google.android.youtube:id/reel_player_page_content"),
                comparsionResultCleanser = {
                    if(it.contains("PostPostPostlike")) return@ReelAppData ""
                    if(it.length <= 15) return@ReelAppData ""
                    it.replace("Video Progress","")
                        .replace("Tap to watch live","")
                        .replace("Go to channel","")
                        .replace("soundVideo ProgressSearchMoreHomeHomeShortsShortsCreateSubscriptions","")
                        .replace("soundSearchMoreHomeHomeShortsShortsCreateSubscriptions","")
                },
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
            "app.revanced.android.youtube" to ReelAppData(
                viewId = "id:app.revanced.android.youtube:id/reel_recycler",
                requiresPresent = listOf(),
                dynamicComparator = listOf("id:app.revanced.android.youtube:id/reel_player_page_content"),
                comparsionResultCleanser = {
                    if(it.contains("PostPostPostlike")) return@ReelAppData ""
                    if(it.length <= 15) return@ReelAppData ""
                    it.replace("Video Progress","")
                        .replace("Tap to watch live","")
                        .replace("Go to channel","")
                        .replace("soundVideo ProgressSearchMoreHomeHomeShortsShortsCreateSubscriptions","")
                        .replace("soundSearchMoreHomeHomeShortsShortsCreateSubscriptions","")
                },
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
            "app.morphe.android.youtube" to ReelAppData(
                viewId = "id:app.morphe.android.youtube:id/reel_recycler",
                requiresPresent = listOf(),
                dynamicComparator = listOf("id:app.morphe.android.youtube:id/reel_player_page_content"),
                comparsionResultCleanser = {
                    if(it.contains("PostPostPostlike")) return@ReelAppData ""
                    if(it.length <= 15) return@ReelAppData ""
                    it.replace("Video Progress","")
                        .replace("Tap to watch live","")
                        .replace("Go to channel","")
                        .replace("soundVideo ProgressSearchMoreHomeHomeShortsShortsCreateSubscriptions","")
                        .replace("soundSearchMoreHomeHomeShortsShortsCreateSubscriptions","")
                },
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
//            "com.reddit.frontpage" to ReelAppData(
//                viewId = "path:android.view.ViewGroup[0]>android.view.View[0]>android.view.View[0]>android.widget.ScrollView[0]>android.view.View[0]>android.view.View[0]>android.view.View[0]>android.view.View[0]>android.view.View[0]>android.view.View[0]>androidx.compose.ui.viewinterop.ViewFactoryHolder[0]",
//                requiresPresent = listOf("path:android.view.ViewGroup[0]>android.view.View[0]>android.view.View[0]>android.widget.ScrollView[0]>android.view.View[0]>android.view.View[0]>android.view.View[0]>android.view.View[0]>android.view.View[1]>android.view.View[0]>android.view.View[0]>android.view.View[0]"),
//                dynamicComparator = listOf("descContains:post creator"),
//                comparsionResultCleanser = {
//                    return@ReelAppData it.replace("[deleted]", UUID.randomUUID().toString())
//                },
//            ),

            "com.facebook.katana" to ReelAppData(
                viewId = "desc:Tap to show video controls",
                requiresPresent = listOf(),
                comparsionResultCleanser = {
                    return@ReelAppData it.replace("Story trayCreate storyCreate storyCreate storyClose import contactsFacebook is better with friendsFacebook is better with friendsSee stories from friends by adding people you know from your contacts.See stories from friends by adding people you know from your contacts.Find friends through contacts","")
                },
                dynamicComparator = listOf("path:android.widget.FrameLayout[0]>android.view.ViewGroup[0]>androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[0]>android.view.ViewGroup[0]>android.widget.Button[0]>android.view.ViewGroup[2]",
                    "path:android.widget.HorizontalScrollView[0]>androidx.viewpager.widget.ViewPager[0]>android.view.ViewGroup[0]>androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[0]>android.view.ViewGroup[0]>android.widget.Button[0]>android.view.ViewGroup[2]>android.view.ViewGroup[0]>android.view.ViewGroup[0]",)),

            "com.snapchat.android" to ReelAppData(
                viewId = "desc:Spotlight;selected:true",
                requiresPresent = listOf(),
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                dynamicComparator = listOf("path:android.widget.FrameLayout[1]>android.widget.FrameLayout[0]>android.widget.TextView[0]","path:android.widget.FrameLayout[1]>android.widget.FrameLayout[0]>android.widget.TextView[1]")
            )

        )

    }
}
