package neth.iecal.curbox.utils

import android.content.Context
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import com.google.gson.Gson
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.Settings
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import kotlin.jvm.java

class GsonSerializer<T>(
    private val gson: Gson,
    private val type: Type,
    override val defaultValue: T
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T {
        return try {
            gson.fromJson(input.readBytes().decodeToString(), type) ?: defaultValue
        } catch (e: Exception) {
            e.printStackTrace()
            defaultValue
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(gson.toJson(t).toByteArray())
    }
}

class DataStoreManager(private val context: Context) {
    private val gson = Gson()

    companion object {
        @Volatile
        private var INSTANCE: androidx.datastore.core.DataStore<Settings>? = null

        fun getSettingsDataStore(context: Context, gson: Gson): androidx.datastore.core.DataStore<Settings> {
            // Double checked locking: the inner re-check is essential. Without it,
            // two threads racing the first access each build a DataStore for the
            // same file, which crashes with "There are multiple DataStores active
            // for the same file". This is easy to hit on a cold start (e.g. right
            // after a reinstall) when several coroutines touch settings at once.
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MultiProcessDataStoreFactory.create(
                    serializer = GsonSerializer(
                        gson = gson,
                        type = Settings::class.java,
                        defaultValue = Settings()
                    ),
                    produceFile = { File(context.applicationContext.filesDir, "datastore/settings.json") }
                ).also { INSTANCE = it }
            }
        }
    }

    private val settingsDataStore = getSettingsDataStore(context, gson)

    val settings = settingsDataStore.data

    suspend fun updateAppGroups(newGroups: List<AppGroup>) {
        settingsDataStore.updateData { it.copy(blockedAppGroups = newGroups) }
    }

    suspend fun updateManualFocusGroups(newGroup: List<ManualFocusGroup>){
        settingsDataStore.updateData { it.copy(manualFocusGroups = newGroup) }
    }

    suspend fun updateAutoDndGroups(newGroups: List<neth.iecal.curbox.data.models.AutoDndGroup>) {
        settingsDataStore.updateData { it.copy(autoDndGroups = newGroups) }
    }
    
    suspend fun setManualFocusStateToActive(focusGroupId:String, durationInMs: Long){
        settingsDataStore.updateData { it.copy(activeManualFocusGroupId = Pair(focusGroupId, System.currentTimeMillis() + durationInMs)) }
    }
    suspend fun setManualFocusStateToInactive(){
        settingsDataStore.updateData { it.copy(activeManualFocusGroupId = Pair(null, 0)) }
    }

    suspend fun updateReelBlockerConfig(config: neth.iecal.curbox.data.models.ReelBlocker) {
        settingsDataStore.updateData { it.copy(reelBlockerConfig = config) }
    }

    suspend fun updateKeywordBlockerConfig(transform: (neth.iecal.curbox.data.models.KeywordBlocker) -> neth.iecal.curbox.data.models.KeywordBlocker) {
        settingsDataStore.updateData { it.copy(keywordBlockerConfig = transform(it.keywordBlockerConfig)) }
    }

    suspend fun updateReelCounterState(isActive: Boolean) {
        settingsDataStore.updateData { it.copy(isReelCounterOn = isActive) }
    }

    suspend fun updateGrayscaleGroups(newGroups: List<neth.iecal.curbox.data.models.GrayscaleGroup>) {
        settingsDataStore.updateData { it.copy(grayscaleGroups = newGroups) }
    }

    suspend fun updateUsageTrackerIgnoredApps(newApps: List<String>) {
        settingsDataStore.updateData { it.copy(usageTrackerIgnoredApps = newApps) }
    }

    suspend fun updateMindfulMessageConfig(config: neth.iecal.curbox.data.models.MindfulMessageConfig) {
        settingsDataStore.updateData { it.copy(mindfulMessageConfig = config) }
    }

    suspend fun updateUiHiderConfig(config: neth.iecal.curbox.data.models.UiHiderConfig) {
        settingsDataStore.updateData { it.copy(uiHiderConfig = config) }
    }

    suspend fun updateReelCounterOverlayConfig(config: neth.iecal.curbox.data.models.ReelCounterOverlayConfig) {
        settingsDataStore.updateData { it.copy(reelCounterOverlayConfig = config) }
    }

    suspend fun updateNextWebsiteRecheckTime(time: Long) {
        settingsDataStore.updateData { it.copy(nextWebsiteRecheckTime = time) }
    }

    suspend fun updateAntiUninstallConfig(transform: (neth.iecal.curbox.data.models.AntiUninstallConfig) -> neth.iecal.curbox.data.models.AntiUninstallConfig) {
        settingsDataStore.updateData { it.copy(antiUninstallConfig = transform(it.antiUninstallConfig)) }
    }

    suspend fun updateServiceProtectionConfig(transform: (neth.iecal.curbox.data.models.ServiceProtectionConfig) -> neth.iecal.curbox.data.models.ServiceProtectionConfig) {
        settingsDataStore.updateData { it.copy(serviceProtectionConfig = transform(it.serviceProtectionConfig)) }
    }
}
