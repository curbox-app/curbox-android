package neth.iecal.curbox.data.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class SecureKeyStore(context: Context) {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "curbox_sync_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var dekB64: String?
        get() = prefs.getString("dek", null)
        set(v) = prefs.edit().putString("dek", v).apply()

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()

    var cursor: String
        get() = prefs.getString("cursor", "1970-01-01T00:00:00Z")!!
        set(v) = prefs.edit().putString("cursor", v).apply()

    var fcmToken: String?
        get() = prefs.getString("fcm_token", null)
        set(v) = prefs.edit().putString("fcm_token", v).apply()

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    var deviceName: String
        get() = prefs.getString("device_name", "Android")!!
        set(v) = prefs.edit().putString("device_name", v).apply()

    var syncUsageStats: Boolean
        get() = prefs.getBoolean("sync_usage_stats", true)
        set(v) = prefs.edit().putBoolean("sync_usage_stats", v).apply()

    var syncReducerConfigs: Boolean
        get() = prefs.getBoolean("sync_reducer_configs", true)
        set(v) = prefs.edit().putBoolean("sync_reducer_configs", v).apply()

    var usageDeviceIds: Set<String>
        get() = prefs.getStringSet("usage_device_ids", emptySet())?.toSet().orEmpty()
        set(v) = prefs.edit().putStringSet("usage_device_ids", v).apply()

    fun clear() {
        prefs.edit().remove("dek").remove("access_token").remove("refresh_token").remove("cursor").remove("fcm_token").apply()
    }
}
