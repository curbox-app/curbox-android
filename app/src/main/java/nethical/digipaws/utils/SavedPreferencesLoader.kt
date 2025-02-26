package nethical.digipaws.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import nethical.digipaws.blockers.FocusModeBlocker
import nethical.digipaws.services.UsageTrackingService.AttentionSpanVideoItem
import nethical.digipaws.ui.activity.MainActivity
import nethical.digipaws.ui.activity.TimedActionActivity

class SavedPreferencesLoader(private val context: Context) {

    fun loadPinnedApps(): Set<String> {
        val sharedPreferences =
            context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        return sharedPreferences.getStringSet("pinned_apps", emptySet()) ?: emptySet()
    }


    fun loadIgnoredAppUsageTracker(): Set<String> {
        val sharedPreferences =
            context.getSharedPreferences("app_usage_tracker", Context.MODE_PRIVATE)
        return sharedPreferences.getStringSet("ignored_apps", emptySet()) ?: emptySet()
    }

    fun loadBlockedApps(): Set<String> {
        val sharedPreferences =
            context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        return sharedPreferences.getStringSet("blocked_apps", emptySet()) ?: emptySet()
    }

    fun loadBlockedKeywords(): Set<String> {
        val sharedPreferences =
            context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        return sharedPreferences.getStringSet("blocked_keywords", emptySet()) ?: emptySet()
    }

    fun savePinned(pinnedApps: Set<String>) {
        val sharedPreferences =
            context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        sharedPreferences.edit().putStringSet("pinned_apps", pinnedApps).apply()
    }


    fun saveBlockedApps(pinnedApps: Set<String>) {
        val sharedPreferences =
            context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        sharedPreferences.edit().putStringSet("blocked_apps", pinnedApps).apply()
    }


    fun saveIgnoredAppUsageTracker(ignoredApps: Set<String>) {
        val sharedPreferences =
            context.getSharedPreferences("app_usage_tracker", Context.MODE_PRIVATE)
        sharedPreferences.edit().putStringSet("ignored_apps", ignoredApps).apply()
    }


    fun saveBlockedKeywords(pinnedApps: Set<String>) {
        val sharedPreferences =
            context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        sharedPreferences.edit().putStringSet("blocked_keywords", pinnedApps).apply()
    }
    fun saveAppBlockerCheatHoursList(cheatHoursList: MutableList<TimedActionActivity.AutoTimedActionItem>) {
        val sharedPreferences = context.getSharedPreferences("cheat_hours", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(cheatHoursList)

        editor.putString("cheatHoursList", json)
        editor.apply()
    }

    fun loadAppBlockerCheatHoursList(): MutableList<TimedActionActivity.AutoTimedActionItem> {
        val sharedPreferences = context.getSharedPreferences("cheat_hours", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("cheatHoursList", null)

        if (json.isNullOrEmpty()) return mutableListOf()

        val type =
            object : TypeToken<MutableList<TimedActionActivity.AutoTimedActionItem>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveAutoFocusHoursList(cheatHoursList: MutableList<TimedActionActivity.AutoTimedActionItem>) {
        val sharedPreferences =
            context.getSharedPreferences("auto_focus_hours", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(cheatHoursList)

        editor.putString("auto_focus_list", json)
        editor.apply()
    }

    fun loadAutoFocusHoursList(): MutableList<TimedActionActivity.AutoTimedActionItem> {
        val sharedPreferences =
            context.getSharedPreferences("auto_focus_hours", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("auto_focus_list", null)

        if (json.isNullOrEmpty()) return mutableListOf()

        val type =
            object : TypeToken<MutableList<TimedActionActivity.AutoTimedActionItem>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveAppBlockerWarningInfo(warningData: MainActivity.WarningData) {
        val sharedPreferences = context.getSharedPreferences("warning_data", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(warningData)

        editor.putString("app_blocker", json)
        editor.apply()
    }

    fun loadAppBlockerWarningInfo(): MainActivity.WarningData {
        val sharedPreferences = context.getSharedPreferences("warning_data", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("app_blocker", null)

        if (json.isNullOrEmpty()) return MainActivity.WarningData()

        val type = object : TypeToken<MainActivity.WarningData>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveViewBlockerWarningInfo(warningData: MainActivity.WarningData) {
        val sharedPreferences = context.getSharedPreferences("warning_data", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(warningData)

        editor.putString("view_blocker", json)
        editor.apply()
    }

    fun saveCheatHoursForViewBlocker(startTime: Int, endTime: Int) {
        val sharedPreferences = context.getSharedPreferences("cheat_hours", Context.MODE_PRIVATE)
        val edit = sharedPreferences.edit()
        edit.putInt("view_blocker_start_time", startTime)
        edit.putInt("view_blocker_end_time", endTime)
        edit.apply()
    }

    fun loadViewBlockerWarningInfo(): MainActivity.WarningData {
        val sharedPreferences = context.getSharedPreferences("warning_data", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("view_blocker", null)

        if (json.isNullOrEmpty()) return MainActivity.WarningData()

        val type = object : TypeToken<MainActivity.WarningData>() {}.type
        return gson.fromJson(json, type)
    }


    fun saveUsageHoursAttentionSpanData(attentionSpanListData: MutableMap<String, MutableList<AttentionSpanVideoItem>>) {
        val sharedPreferences =
            context.getSharedPreferences("attention_span_data", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(attentionSpanListData)

        editor.putString("attention_data", json)
        editor.apply()
    }

    fun loadUsageHoursAttentionSpanData(): MutableMap<String, MutableList<AttentionSpanVideoItem>> {
        val sharedPreferences =
            context.getSharedPreferences("attention_span_data", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("attention_data", null)

        if (json.isNullOrEmpty()) return mutableMapOf()

        val type =
            object : TypeToken<MutableMap<String, MutableList<AttentionSpanVideoItem>>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveReelsScrolled(reelsData: MutableMap<String, Int>) {
        val sharedPreferences =
            context.getSharedPreferences("attention_span_data", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(reelsData)

        editor.putString("reels_data", json)
        editor.apply()
    }

    fun getReelsScrolled(): MutableMap<String, Int> {
        val sharedPreferences =
            context.getSharedPreferences("attention_span_data", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("reels_data", null)

        if (json.isNullOrEmpty()) return mutableMapOf()

        val type =
            object : TypeToken<MutableMap<String, Int>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveFocusModeData(focusModeData: FocusModeBlocker.FocusModeData) {
        val sharedPreferences =
            context.getSharedPreferences("focus_mode", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(focusModeData)

        editor.putString("focus_mode", json)
        editor.apply()
    }


    fun getFocusModeData(): FocusModeBlocker.FocusModeData {

        val sharedPreferences =
            context.getSharedPreferences("focus_mode", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("focus_mode", null)

        if (json.isNullOrEmpty()) return FocusModeBlocker.FocusModeData()

        val type =
            object : TypeToken<FocusModeBlocker.FocusModeData>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveFocusModeSelectedApps(appList: List<String>) {
        val sharedPreferences =
            context.getSharedPreferences("focus_mode", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(appList)

        editor.putString("selected_apps", json)
        editor.apply()
    }

    fun getFocusModeSelectedApps(): List<String> {
        val sharedPreferences =
            context.getSharedPreferences("focus_mode", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("selected_apps", null)

        if (json.isNullOrEmpty()) return listOf()

        val type =
            object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveKeywordBlockerIgnoredApps(appList: List<String>) {
        val sharedPreferences =
            context.getSharedPreferences("Keyword_blocker_ignored_apps", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()

        val json = gson.toJson(appList)

        editor.putString("selected_apps", json)
        editor.apply()
    }

    fun getKeywordBlockerIgnoredApps(): List<String> {
        val sharedPreferences =
            context.getSharedPreferences("Keyword_blocker_ignored_apps", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = sharedPreferences.getString("selected_apps", null)

        if (json.isNullOrEmpty()) return listOf()

        val type =
            object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }


    fun setOverlayApps(selectedApps: Set<String>) {
        val sharedPreferences =
            context.getSharedPreferences("overlay_apps", Context.MODE_PRIVATE)
        sharedPreferences.edit().putStringSet("apps", selectedApps).apply()
    }
    fun getOverlayApps():Set<String>{
        val sharedPreferences =
            context.getSharedPreferences("overlay_apps", Context.MODE_PRIVATE)
        return sharedPreferences.getStringSet("apps", emptySet()) ?: emptySet()
    }


    fun loadGrayScaleApps(): Set<String> {
        val sharedPreferences =
            context.getSharedPreferences("grayscale", Context.MODE_PRIVATE)
        return sharedPreferences.getStringSet("apps", emptySet()) ?: emptySet()
    }

    fun saveGrayScaleApps(apps: Set<String>) {
        val sharedPreferences =
            context.getSharedPreferences("grayscale", Context.MODE_PRIVATE)
        sharedPreferences.edit().putStringSet("apps", apps).apply()
    }
}