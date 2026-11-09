package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.sip_stretch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.SipStretchConfig
import neth.iecal.curbox.utils.DataStoreManager

class SipStretchViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)

    private val _configState = MutableStateFlow(SipStretchConfig())
    val configState: StateFlow<SipStretchConfig> = _configState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _configState.value = settings.sipStretchConfig
            }
        }
    }

    fun updateIsActive(isActive: Boolean) {
        val current = _configState.value
        if (current.isActive != isActive) updateConfig(current.copy(isActive = isActive))
    }

    fun updateSelectedApps(selectedApps: List<String>) {
        val current = _configState.value
        if (current.selectedApps != selectedApps) updateConfig(current.copy(selectedApps = selectedApps))
    }

    fun updateUsageLimitMinutes(minutes: Int) {
        val current = _configState.value
        if (current.usageLimitMinutes != minutes) updateConfig(current.copy(usageLimitMinutes = minutes))
    }

    fun updateBreakMinutes(minutes: Int) {
        val current = _configState.value
        if (current.breakMinutes != minutes) updateConfig(current.copy(breakMinutes = minutes))
    }

    private fun updateConfig(config: SipStretchConfig) {
        viewModelScope.launch {
            dataStoreManager.updateSipStretchConfig(config)
        }
    }
}
