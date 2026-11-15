package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.scheduled_notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.ScheduledNotificationsConfig
import neth.iecal.curbox.utils.DataStoreManager

class ScheduledNotificationsViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)

    private val _configState = MutableStateFlow(ScheduledNotificationsConfig())
    val configState: StateFlow<ScheduledNotificationsConfig> = _configState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _configState.value = settings.scheduledNotificationsConfig
            }
        }
    }

    fun updateIsActive(isActive: Boolean) {
        val current = _configState.value
        if (current.isActive != isActive) updateConfig(current.copy(isActive = isActive))
    }

    fun updateAllowedApps(allowedApps: List<String>) {
        val current = _configState.value
        if (current.allowedApps != allowedApps) updateConfig(current.copy(allowedApps = allowedApps))
    }

    fun addDeliveryTime(minutes: Int) {
        val current = _configState.value
        if (minutes in current.deliveryMinutes) return
        if (current.deliveryMinutes.size >= ScheduledNotificationsConfig.MAX_DELIVERY_TIMES) return
        updateConfig(current.copy(deliveryMinutes = (current.deliveryMinutes + minutes).sorted()))
    }

    fun removeDeliveryTime(minutes: Int) {
        val current = _configState.value
        // The last time stays, or held notifications would never come back.
        if (minutes !in current.deliveryMinutes || current.deliveryMinutes.size <= 1) return
        updateConfig(current.copy(deliveryMinutes = current.deliveryMinutes - minutes))
    }

    private fun updateConfig(config: ScheduledNotificationsConfig) {
        viewModelScope.launch {
            dataStoreManager.updateScheduledNotificationsConfig(config)
        }
    }
}
