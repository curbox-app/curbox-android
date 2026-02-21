package nethical.digipaws.blockers

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import nethical.digipaws.utils.TimeTools
import java.util.Calendar


class ViewBlocker : BaseBlocker() {
    companion object {
        fun findElementById(node: AccessibilityNodeInfo?, id: String?): AccessibilityNodeInfo? {
            if (node == null) return null
            var targetNode: AccessibilityNodeInfo? = null
            try {
                targetNode = node.findAccessibilityNodeInfosByViewId(id!!)[0]
            } catch (e: Exception) {
                //	e.printStackTrace();
            }
            return targetNode
        }

        val TIKTOK_PACKAGE_NAMES = hashSetOf(
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.aweme"
        )
        val BLOCKED_VIEW_ID_LIST = mutableListOf(
            "com.instagram.android:id/root_clips_layout",
            "com.myinsta.android:id/root_clips_layout",
            "com.google.android.youtube:id/reel_recycler",
            "app.revanced.android.youtube:id/reel_recycler"
        )

    }
    private val cooldownViewIdsList = mutableMapOf<String, Long>()
    var screenWidth: Int = 0
    var screenHeight: Int = 0

    var isIGInboxReelAllowed = false
    var isFirstReelInFeedAllowed = false

    var cheatMinuteStartTime: Int? = null
    var cheatMinutesEndTIme: Int? = null

    fun doesViewNeedToBeBlocked(
        node: AccessibilityNodeInfo,
        packageName: String
    ): ViewBlockerResult? {
        if (isCheatHourActive()) {
            return null
        }
        if (TIKTOK_PACKAGE_NAMES.contains(packageName)) {
            if (isCooldownActive(packageName)) {
                return ViewBlockerResult(
                    isReelFoundInCooldownState = true,
                    viewId = packageName,
                    requestHomePressInstead = true
                )
            }
            return ViewBlockerResult(
                isBlocked = true,
                viewId = packageName,
                requestHomePressInstead = true
            )
        }

        if (isIGInboxReelAllowed && isViewOpened(
                node,
                "com.instagram.android:id/reply_bar_container"
            )
        ) {
            return null
        }


        BLOCKED_VIEW_ID_LIST.forEach { viewId ->
            if(isViewOpened(node,viewId)){
                if (isCooldownActive(viewId)) {
                    return ViewBlockerResult(isReelFoundInCooldownState = true, viewId = viewId)
                }
                return ViewBlockerResult(isBlocked = true, viewId = viewId)
            }
        }
        return null
    }

    fun applyCooldown(viewId: String, endTime: Long) {
        cooldownViewIdsList[viewId] = endTime
    }

    private fun isCooldownActive(viewId: String): Boolean {
        val cooldownEnd = cooldownViewIdsList[viewId] ?: return false
        if (SystemClock.uptimeMillis() > cooldownEnd) {
            cooldownViewIdsList.remove(viewId)
            return false
        }
        return true
    }

    private fun isViewOpened(rootNode: AccessibilityNodeInfo, viewId: String): Boolean {
        val viewNode =
            findElementById(rootNode, viewId)
        val nodeRect = Rect()
        viewNode?.getBoundsInScreen(nodeRect)
        val isOffScreenLeft = nodeRect.right <= 0
        val isOffScreenRight = nodeRect.left >= screenWidth
        return (viewNode != null && !isOffScreenLeft && !isOffScreenRight)
    }

    private fun isCheatHourActive(): Boolean {

        val currentTime = Calendar.getInstance()
        val currentHour = currentTime.get(Calendar.HOUR_OF_DAY)
        val currentMinute = currentTime.get(Calendar.MINUTE)

        val currentMinutes = TimeTools.convertToMinutesFromMidnight(currentHour, currentMinute)

        // If cheat hours are not set, treat as inactive
        if (cheatMinuteStartTime == null || cheatMinutesEndTIme == null || cheatMinuteStartTime == -1 || cheatMinutesEndTIme == -1) {
            return false
        }

        return if (cheatMinuteStartTime!! <= cheatMinutesEndTIme!!) {
            // Regular case: start time is before or equal to end time
            currentMinutes in cheatMinuteStartTime!!..cheatMinutesEndTIme!!
        } else {
            // Wraparound case: time range spans midnight
            currentMinutes in cheatMinuteStartTime!!..1439 || currentMinutes in 0..cheatMinutesEndTIme!!
        }
    }
    data class ViewBlockerResult(
        val isBlocked: Boolean = false,
        val requestHomePressInstead: Boolean = false,
        val isReelFoundInCooldownState: Boolean = false,
        val viewId: String = ""
    )

}
