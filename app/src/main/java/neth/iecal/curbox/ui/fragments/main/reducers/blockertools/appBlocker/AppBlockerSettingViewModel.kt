package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.UsageStatsHelper
import java.util.Calendar

class AppBlockerSettingViewModel(application: Application) : AndroidViewModel(application) {
    var currentUsageConfig: AppUsageConfig = AppUsageConfig()
    var currentTimeConfig: AppTimeConfig = AppTimeConfig()
    var warningScrnConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig()

    private val dataStoreManager = DataStoreManager(application)
    private val usageStats = UsageStatsHelper(application)

    /**
     * Time left today before this group hits its usage limit, in millis.
     * Returns null for groups that are not usage based. Usage is the combined
     * total of every app in the group, matching how the blocker compares it.
     */
    suspend fun getRemainingUsageMillis(group: AppGroup): Long? {
        if (group.blockingType != AppBlockingType.Usage) return null
        val config = runCatching {
            Gson().fromJson(group.setting, AppUsageConfig::class.java)
        }.getOrNull() ?: return null

        val limitMillis = limitForToday(config) * 60_000L
        val packages = group.selectedPackages.map { it.trim() }.toSet()
        val used = withContext(Dispatchers.IO) {
            usageStats.getForegroundStatsByRelativeDay(0)
                .filter { it.packageName in packages }
                .sumOf { it.totalTime }
        }
        return (limitMillis - used).coerceAtLeast(0L)
    }

    private fun limitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) config.uniformLimit
        else config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
    }

    private val _groups = MutableStateFlow<List<AppGroup>>(emptyList())
    val groups: StateFlow<List<AppGroup>> = _groups
    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _groups.value = settings.blockedAppGroups
            }
        }
    }

    private fun requestAppBlockerRefresh() {
        val intent = Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER)
        application.sendBroadcast(intent)
    }

    fun updateGroups(newGroups: List<AppGroup>) {
        viewModelScope.launch {
            dataStoreManager.updateAppGroups(newGroups)
            requestAppBlockerRefresh()
        }
    }
    
    fun addGroup(group: AppGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val updatedGroups = currentSettings.blockedAppGroups.toMutableList().apply { add(group) }
            updateGroups(updatedGroups)
        }
    }

    fun updateGroupById(updatedGroup: AppGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val updatedGroups = currentSettings.blockedAppGroups.toMutableList()
            val index = updatedGroups.indexOfFirst { it.id == updatedGroup.id }
            if (index != -1) {
                updatedGroups[index] = updatedGroup
                updateGroups(updatedGroups)
            }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val updatedGroups = currentSettings.blockedAppGroups.toMutableList()
            updatedGroups.removeAll { it.id == groupId }
            updateGroups(updatedGroups)
        }
    }

    fun updateGroupActiveState(index: Int, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val updatedGroups = currentSettings.blockedAppGroups.toMutableList()
            if (index in updatedGroups.indices) {
                updatedGroups[index] = updatedGroups[index].copy(isActive = isActive)
                updateGroups(updatedGroups)
            }
        }
    }
}