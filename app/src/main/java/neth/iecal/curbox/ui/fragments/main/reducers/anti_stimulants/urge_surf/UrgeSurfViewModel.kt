package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.urge_surf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.CravingSessionEntity
import neth.iecal.curbox.data.models.UrgeSurfConfig
import neth.iecal.curbox.utils.DataStoreManager

class UrgeSurfViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)
    private val sessionDao = AppDatabase.getInstance(application).cravingSessionDao()

    private val _configState = MutableStateFlow(UrgeSurfConfig())
    val configState: StateFlow<UrgeSurfConfig> = _configState.asStateFlow()

    private val _sessions = MutableStateFlow<List<CravingSessionEntity>>(emptyList())
    val sessions: StateFlow<List<CravingSessionEntity>> = _sessions.asStateFlow()

    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _configState.value = settings.urgeSurfConfig
            }
        }
        viewModelScope.launch {
            sessionDao.getAll().collectLatest { _sessions.value = it }
        }
    }

    fun updateButtonEnabled(isEnabled: Boolean) {
        val current = _configState.value
        if (current.isButtonEnabled != isEnabled) updateConfig(current.copy(isButtonEnabled = isEnabled))
    }

    fun updateAutoOfferEnabled(isEnabled: Boolean) {
        val current = _configState.value
        if (current.isAutoOfferEnabled != isEnabled) updateConfig(current.copy(isAutoOfferEnabled = isEnabled))
    }

    fun clearHistory() {
        viewModelScope.launch { sessionDao.deleteAll() }
    }

    private fun updateConfig(config: UrgeSurfConfig) {
        viewModelScope.launch {
            dataStoreManager.updateUrgeSurfConfig(config)
        }
    }
}
