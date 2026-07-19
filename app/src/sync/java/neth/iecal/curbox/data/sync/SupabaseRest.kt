package neth.iecal.curbox.data.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SupabaseRest(
    private val baseUrl: String = "https://pdixkzhncuuxuxwhdwdh.supabase.co",
    private val anonKey: String = ANON_KEY,
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    /** Thrown when the auth endpoint itself rejects a request, carrying the HTTP status. */
    class AuthHttpException(val code: Int, message: String) : IOException(message)

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val email: String?,
        val expiresAt: Long,
    )

    data class VaultRow(val saltB64: String, val paramsJson: String, val wrappedB64: String)

    data class SyncRow(
        val namespace: String,
        val recordKey: String,
        val deviceId: String?,
        val ciphertext: String,
        val version: Long,
        val deleted: Boolean,
        val updatedAt: String,
    )

    data class DeviceRow(val id: String, val platform: String, val label: String, val lastSeen: String?)

    private fun parseSession(body: JsonObject): Session? {
        val token = body.get("access_token")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val user = body.getAsJsonObject("user")
        val expiresIn = body.get("expires_in")?.asLong ?: 3600
        return Session(
            accessToken = token,
            refreshToken = body.get("refresh_token").asString,
            userId = user.get("id").asString,
            email = user.get("email")?.takeIf { !it.isJsonNull }?.asString,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
        )
    }

    private fun postAuth(path: String, payload: JsonObject): JsonObject {
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/$path")
            .header("apikey", anonKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = if (text.isBlank()) JsonObject() else gson.fromJson(text, JsonObject::class.java)
            if (!resp.isSuccessful) {
                val msg = obj.get("error_description")?.asString ?: obj.get("msg")?.asString
                ?: obj.get("error")?.asString ?: "request failed (${resp.code})"
                throw AuthHttpException(resp.code, msg)
            }
            return obj
        }
    }

    fun signUp(email: String, password: String): Session? {
        val body = JsonObject().apply { addProperty("email", email); addProperty("password", password) }
        return parseSession(postAuth("signup", body))
    }

    fun signIn(email: String, password: String): Session {
        val body = JsonObject().apply { addProperty("email", email); addProperty("password", password) }
        return parseSession(postAuth("token?grant_type=password", body))
            ?: throw IOException("sign in did not return a session")
    }

    fun refresh(refreshToken: String): Session {
        val body = JsonObject().apply { addProperty("refresh_token", refreshToken) }
        return parseSession(postAuth("token?grant_type=refresh_token", body))
            ?: throw IOException("could not refresh session")
    }

    fun verifyOtp(email: String, token: String, type: String): Session {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("token", token)
            addProperty("type", type)
        }
        return parseSession(postAuth("verify", body)) ?: throw IOException("that code did not work")
    }

    fun resend(email: String, type: String) {
        val body = JsonObject().apply { addProperty("email", email); addProperty("type", type) }
        postAuth("resend", body)
    }

    fun recover(email: String) {
        postAuth("recover", JsonObject().apply { addProperty("email", email) })
    }

    fun updatePassword(session: Session, newPassword: String) {
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .put(JsonObject().apply { addProperty("password", newPassword) }.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("could not update password (${resp.code})")
        }
    }

    private fun authedGet(session: Session, path: String): String {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/$path")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("read failed (${resp.code}): $text")
            return text
        }
    }

    private fun authedPost(session: Session, path: String, jsonBody: String, prefer: String) {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/$path")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .header("Prefer", prefer)
            .post(jsonBody.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("write failed (${resp.code}): ${resp.body?.string()}")
        }
    }

    fun getVault(session: Session): VaultRow? {
        val text = authedGet(session, "vault?user_id=eq.${session.userId}&select=kdf_salt,kdf_params,wrapped_dek")
        val arr = gson.fromJson(text, com.google.gson.JsonArray::class.java)
        if (arr.size() == 0) return null
        val row = arr[0].asJsonObject
        return VaultRow(
            saltB64 = row.get("kdf_salt").asString,
            paramsJson = row.get("kdf_params").toString(),
            wrappedB64 = row.get("wrapped_dek").asString,
        )
    }

    fun insertVault(session: Session, saltB64: String, paramsJson: String, wrappedB64: String) {
        val obj = JsonObject().apply {
            addProperty("user_id", session.userId)
            addProperty("kdf_salt", saltB64)
            add("kdf_params", gson.fromJson(paramsJson, JsonObject::class.java))
            addProperty("wrapped_dek", wrappedB64)
        }
        authedPost(session, "vault", obj.toString(), "return=minimal")
    }

    fun upsertDevice(session: Session, id: String, platform: String, label: String, fcmToken: String? = null) {
        val obj = JsonObject().apply {
            addProperty("id", id)
            addProperty("user_id", session.userId)
            addProperty("platform", platform)
            addProperty("label", label)
            if (fcmToken != null) addProperty("fcm_token", fcmToken)
        }
        authedPost(session, "devices?on_conflict=id", "[$obj]", "resolution=merge-duplicates,return=minimal")
    }

    fun devices(session: Session): List<DeviceRow> {
        val text = authedGet(session, "devices?user_id=eq.${session.userId}&select=id,platform,label,last_seen&order=last_seen.desc")
        return gson.fromJson(text, com.google.gson.JsonArray::class.java).map { el ->
            val o = el.asJsonObject
            DeviceRow(
                id = o.get("id").asString,
                platform = o.get("platform")?.asString ?: "device",
                label = o.get("label")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                lastSeen = o.get("last_seen")?.takeIf { !it.isJsonNull }?.asString,
            )
        }
    }

    fun clearDeviceToken(session: Session, id: String) {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/devices?id=eq.$id")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .patch(JsonObject().apply { add("fcm_token", com.google.gson.JsonNull.INSTANCE) }.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { }
    }

    fun pull(session: Session, cursor: String): List<SyncRow> {
        val encodedCursor = java.net.URLEncoder.encode(cursor, "UTF-8")
        val path = "sync_records?user_id=eq.${session.userId}&updated_at=gt.$encodedCursor" +
            "&order=updated_at.asc&select=namespace,record_key,device_id,ciphertext,version,deleted,updated_at"
        val text = authedGet(session, path)
        val arr = gson.fromJson(text, com.google.gson.JsonArray::class.java)
        return arr.map { el ->
            val o = el.asJsonObject
            SyncRow(
                namespace = o.get("namespace").asString,
                recordKey = o.get("record_key").asString,
                deviceId = o.get("device_id")?.takeIf { !it.isJsonNull }?.asString,
                ciphertext = o.get("ciphertext").asString,
                version = o.get("version").asLong,
                deleted = o.get("deleted").asBoolean,
                updatedAt = o.get("updated_at").asString,
            )
        }
    }

    fun upsertRecord(session: Session, namespace: String, recordKey: String, deviceId: String, ciphertextB64: String, version: Long, deleted: Boolean = false) {
        val obj = JsonObject().apply {
            addProperty("user_id", session.userId)
            addProperty("namespace", namespace)
            addProperty("record_key", recordKey)
            addProperty("device_id", deviceId)
            addProperty("ciphertext", ciphertextB64)
            addProperty("version", version)
            addProperty("deleted", deleted)
        }
        authedPost(
            session,
            "sync_records?on_conflict=user_id,namespace,record_key",
            "[$obj]",
            "resolution=merge-duplicates,return=minimal",
        )
    }

    companion object {
        const val ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBkaXhremhuY3V1eHV4d2hkd2RoIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODIyNzA2MjIsImV4cCI6MjA5Nzg0NjYyMn0.FfDMzEV6W5_IVuVmm_ld1zUx9wjrTE6Vuj415wHSAas"
    }
}
