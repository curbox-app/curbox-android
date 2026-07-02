package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.KeywordMatcher
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import java.util.Calendar

class KeywordBlockerViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)

    private val _keywordBlockerConfig = MutableStateFlow(KeywordBlocker())
    val keywordBlockerConfig: StateFlow<KeywordBlocker> = _keywordBlockerConfig

    var currentUsageConfig = AppUsageConfig()
    var currentTimeConfig = AppTimeConfig()
    var warningScrnConfig = AppBlockerWarningScreenConfig()

    /**
     * Time left today before this group hits its usage limit, in millis.
     * Returns null for groups that are not usage based. Usage is the combined
     * total of every keyword in the group across all browsers.
     */
    suspend fun getRemainingUsageMillis(group: KeywordGroup): Long? {
        if (group.blockingType != AppBlockingType.Usage) return null
        val config = runCatching {
            Gson().fromJson(group.setting, AppUsageConfig::class.java)
        }.getOrNull() ?: return null

        val limitMillis = limitForToday(config) * 60_000L
        val patterns = KeywordMatcher.compileKeywords(group.selectedKeywords)
        val date = TimeTools.getCurrentDate()
        val used = withContext(Dispatchers.IO) {
            AppDatabase.getInstance(getApplication()).websiteStatsDao()
                .getStatsForDate(date)
                .filter { KeywordMatcher.matchesPatterns(patterns, it.urlIdentifier) }
                .sumOf { it.totalTime }
        }
        return (limitMillis - used).coerceAtLeast(0L)
    }

    private fun limitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) config.uniformLimit
        else config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
    }

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _keywordBlockerConfig.value = settings.keywordBlockerConfig
            }
        }
    }


    private fun requestKeywordBlockerRefresh() {
        val intent = Intent(neth.iecal.curbox.blockers.KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG)
        getApplication<Application>().sendBroadcast(intent)
    }
    private fun updateConfig(transform: (neth.iecal.curbox.data.models.KeywordBlocker) -> neth.iecal.curbox.data.models.KeywordBlocker) {
        viewModelScope.launch {
            dataStoreManager.updateKeywordBlockerConfig(transform)
            requestKeywordBlockerRefresh()
        }
    }

    fun setIsActive(isActive: Boolean) {
        updateConfig { it.copy(isActive = isActive) }
    }

    fun setBlockAllExceptSupported(enabled: Boolean) {
        updateConfig { it.copy(blockAllExceptSupported = enabled) }
    }

    fun addGroup(group: KeywordGroup) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            groups.add(group)
            config.copy(keywordGroups = groups)
        }
    }

    fun updateGroupById(group: KeywordGroup) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            val index = groups.indexOfFirst { it.id == group.id }
            if (index != -1) {
                groups[index] = group
            }
            config.copy(keywordGroups = groups)
        }
    }

    fun deleteGroup(groupId: String) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            groups.removeAll { it.id == groupId }
            config.copy(keywordGroups = groups)
        }
    }

    fun updateGroupActiveState(groupId: String, isActive: Boolean) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            val index = groups.indexOfFirst { it.id == groupId }
            if (index != -1) {
                groups[index] = groups[index].copy(isActive = isActive)
            }
            config.copy(keywordGroups = groups)
        }
    }
}
