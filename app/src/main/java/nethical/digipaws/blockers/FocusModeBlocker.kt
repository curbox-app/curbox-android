package nethical.digipaws.blockers

import android.os.SystemClock
import android.util.Log
import nethical.digipaws.Constants
import nethical.digipaws.ui.activity.TimedActionActivity
import nethical.digipaws.utils.TimeTools
import java.util.Calendar

class FocusModeBlocker : BaseBlocker() {

    // package-name -> [(start-time, end-time), ...]
    private var autoFocusHours: MutableMap<String, List<Pair<Int, Int>>> = mutableMapOf()

    var focusModeData = FocusModeData()

    /**
     * Check if app app needs to blocked for reasons related to focus mode
     *
     * @param packageName
     * @return
     */
    fun doesAppNeedToBeBlocked(packageName: String): FocusModeResult {


        // responsible for checking if manual focus mode is turned on
        if (focusModeData.isTurnedOn) {
            if (focusModeData.endTime < System.currentTimeMillis()) {
                focusModeData.isTurnedOn = false
                return FocusModeResult(isBlocked = false, isRequestingToUpdateSPData = true)
            }
            when (focusModeData.modeType) {
                Constants.FOCUS_MODE_BLOCK_SELECTED -> {
                    if (focusModeData.selectedApps.contains(packageName)) {
                        return FocusModeResult(
                            isBlocked = true,
                            focusModeEndTime = focusModeData.endTime
                        )
                    }
                }

                Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED -> {
                    if (!focusModeData.selectedApps.contains(packageName)) {
                        return FocusModeResult(
                            isBlocked = true,
                            focusModeEndTime = focusModeData.endTime
                        )
                    }
                }
            }
        }

        // check if app is under auto-focus mode
        val endAutoFocus = getEndTimeInMillis(packageName)
        if (endAutoFocus != null) {
            return FocusModeResult(isBlocked = true, focusModeEndTime = endAutoFocus)
        }

        return FocusModeResult(isBlocked = false)
    }

    /**
     * Check if the package is currently under auto focus hours
     *
     * @param packageName The app's package name.
     * @return Returns null if the app is not under auto focus hours, or the timestamp (uptimeMillis) when it ends .
     */
    private fun getEndTimeInMillis(packageName: String): Long? {
        if (autoFocusHours[packageName] == null) return null

        val currentTime = Calendar.getInstance()
        val currentHour = currentTime.get(Calendar.HOUR_OF_DAY)
        val currentMinute = currentTime.get(Calendar.MINUTE)

        val currentMinutes = TimeTools.convertToMinutesFromMidnight(currentHour, currentMinute)
        val uptimeNow = SystemClock.uptimeMillis()

        autoFocusHours[packageName]?.forEach { (startMinutes, endMinutes) ->
            if ((startMinutes <= endMinutes && currentMinutes in startMinutes until endMinutes) ||
                (startMinutes > endMinutes && (currentMinutes >= startMinutes || currentMinutes < endMinutes))
            ) {

                // Convert endMinutes to uptimeMillis
                val diffMinutes = endMinutes - currentMinutes
                val endTimeMillis = uptimeNow + (diffMinutes * 60 * 1000)

                return endTimeMillis
            }
        }
        return null
    }


    fun refreshCheatHoursData(focusData: List<TimedActionActivity.AutoTimedActionItem>) {
        autoFocusHours.clear()
        focusData.forEach { item ->
            val startTime = item.startTimeInMins
            val endTime = item.endTimeInMins
            val packageNames: ArrayList<String> = item.packages

            packageNames.forEach { packageName ->

                if (autoFocusHours.containsKey(packageName)) {
                    val cheatHourTimeData: List<Pair<Int, Int>>? = autoFocusHours[packageName]
                    val cheatHourNewTimeData: MutableList<Pair<Int, Int>> =
                        cheatHourTimeData!!.toMutableList()

                    cheatHourNewTimeData.add(Pair(startTime, endTime))
                    autoFocusHours[packageName] = cheatHourNewTimeData
                } else {
                    autoFocusHours[packageName] = listOf(Pair(startTime, endTime))
                }
            }
        }
        Log.d("FocusModeBlocker", "Auto Focus Data updated $autoFocusHours")

    }

    /**
     * Stores information related to manual focus mode
     *
     * @property isTurnedOn
     * @property endTime specifies when manual focus hours ends. -1 if not under manual focus hours
     * @property modeType Can either be of type [Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED] or [Constants.FOCUS_MODE_BLOCK_SELECTED].
     * @property selectedApps
     */

    data class FocusModeData(
        var isTurnedOn: Boolean = false,
        val endTime: Long = -1,
        val modeType: Int = Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED,
        var selectedApps: HashSet<String> = hashSetOf()
    )

    /**
     * Focus mode blocker check result
     *
     * @property isBlocked
     * @property focusModeEndTime specifies when focus mode ends. returns -1 if not in focus mode.
     * @property isRequestingToUpdateSPData returns true if focusModeData in shared preference needs to be updated because focus mode has ended
     */
    data class FocusModeResult(
        val isBlocked: Boolean,
        val focusModeEndTime: Long = -1,
        val isRequestingToUpdateSPData: Boolean = false
    )


}