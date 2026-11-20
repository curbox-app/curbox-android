package neth.iecal.curbox.nfc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.FocusStatsEntity
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Turns an NFC tag into a focus-mode change.
 *
 * Tags carry an NDEF URI in the form:
 *   curbox://focus/<action>[?group=<groupId>&mins=<minutes>]
 * where <action> is one of: toggle, start, stop.
 *
 * Writes state through DataStoreManager and fires the focus refresh broadcast, exactly like the
 * in-app and API paths, so NFC-driven changes stay consistent with them.
 */
object NfcFocusHandler {

    private const val TAG = "NfcFocusHandler"

    const val SCHEME = "curbox"
    const val HOST = "focus"

    private const val PREFS = "AppPreferences"
    private const val KEY_LAST_GROUP = "lastFocusGroupId"
    private const val KEY_LAST_DURATION = "lastFocusDuration"
    private const val KEY_LAST_WRITE = "lastFocusTagWriteAt"
    private const val WRITE_DEBOUNCE_MS = 4000L

    /** Result of handling a tag, used to build a user-facing message. */
    sealed class Result {
        data class Started(val groupName: String, val minutes: Int) : Result()
        object Stopped : Result()
        object NoGroup : Result()
        object NotExitable : Result()
        object Invalid : Result()
        object Debounced : Result()
    }

    /** Records that a focus tag was just written, so an immediate re-scan is debounced. */
    fun markTagWritten(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_WRITE, System.currentTimeMillis()).apply()
    }

    /** The action + parameters decoded from a tag URI. */
    data class Request(val action: String, val groupId: String?, val minutes: Int?)

    /** True if the URI is one this handler understands. */
    fun matches(uri: Uri?): Boolean =
        uri != null && uri.scheme == SCHEME && uri.host == HOST

    /**
     * Parses a `curbox://focus/<action>[?group=&mins=]` URI into a [Request], or null if it isn't a
     * Curbox focus URI.
     */
    fun parse(raw: String?): Request? {
        if (raw == null) return null
        val uri = try { java.net.URI(raw) } catch (e: Exception) { return null }
        if (uri.scheme != SCHEME || uri.host != HOST) return null

        val query = parseQuery(uri.rawQuery)
        val action = uri.path?.trim('/')?.substringBefore('/')?.lowercase()?.ifBlank { null }
            ?: query["action"]?.lowercase()
            ?: "toggle"
        return Request(action, query["group"], query["mins"]?.toIntOrNull())
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = decode(part.substring(0, idx))
            val value = decode(part.substring(idx + 1))
            key to value
        }.toMap()
    }

    private fun decode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    suspend fun handle(context: Context, uri: Uri?): Result {
        val request = parse(uri.toString()) ?: return Result.Invalid
        val app = context.applicationContext

        val lastWrite = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_WRITE, 0L)
        if (System.currentTimeMillis() - lastWrite < WRITE_DEBOUNCE_MS) return Result.Debounced

        val dataStore = DataStoreManager(app)
        val settings = dataStore.settings.first()

        return try {
            when (request.action) {
                "stop" -> stop(app, dataStore, settings)
                "start" -> start(app, dataStore, settings, request.groupId, request.minutes)
                "toggle" ->
                    if (isFocusActive(settings)) stop(app, dataStore, settings)
                    else start(app, dataStore, settings, request.groupId, request.minutes)
                else -> Result.Invalid
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle NFC focus action '${request.action}'", e)
            Result.Invalid
        }
    }

    private fun isFocusActive(settings: neth.iecal.curbox.data.models.Settings): Boolean {
        val (id, endTime) = settings.activeManualFocusGroupId
        return id != null && endTime > System.currentTimeMillis()
    }

    private fun isNonExitableActive(settings: neth.iecal.curbox.data.models.Settings): Boolean {
        val (activeId, endTime) = settings.activeManualFocusGroupId
        if (activeId == null || endTime <= System.currentTimeMillis()) return false
        val activeGroup = settings.manualFocusGroups.firstOrNull { it.groupId == activeId }
        return activeGroup != null && !activeGroup.exitable
    }

    private suspend fun start(
        context: Context,
        dataStore: DataStoreManager,
        settings: neth.iecal.curbox.data.models.Settings,
        groupId: String?,
        minutes: Int?
    ): Result {
        if (isNonExitableActive(settings)) return Result.NotExitable

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Resolve group: explicit tag value -> last used -> the only group if just one exists
        val group = when {
            groupId != null -> settings.manualFocusGroups.firstOrNull { it.groupId == groupId }
            else -> settings.manualFocusGroups.firstOrNull {
                it.groupId == prefs.getString(KEY_LAST_GROUP, null)
            } ?: settings.manualFocusGroups.singleOrNull()
        } ?: return Result.NoGroup

        val mins = (minutes ?: prefs.getInt(KEY_LAST_DURATION, 25)).coerceAtLeast(1)
        val durationMs = mins * 60_000L
        val now = System.currentTimeMillis()
        val endTimeInMillis = now + durationMs

        val statsDao = AppDatabase.getInstance(context).focusStatsDao()
        // A tag can replace a running session, so close it first or both would count.
        for (session in statsDao.getRunningSessions()) {
            statsDao.update(session.stoppedAt(now))
        }
        statsDao.insert(
            FocusStatsEntity(
                groupId = group.groupId,
                startTimeInMillis = now,
                estimatedEndTimeInMillis = endTimeInMillis,
                actualEndTimeInMillis = 0L,
                status = 0
            )
        )
        dataStore.setManualFocusStateToActive(
            group.groupId,
            durationMs,
            endTimeInMillis = endTimeInMillis
        )
        broadcast(context)

        prefs.edit()
            .putString(KEY_LAST_GROUP, group.groupId)
            .putInt(KEY_LAST_DURATION, mins)
            .apply()

        return Result.Started(group.groupName, mins)
    }

    private suspend fun stop(
        context: Context,
        dataStore: DataStoreManager,
        settings: neth.iecal.curbox.data.models.Settings
    ): Result {
        if (isNonExitableActive(settings)) return Result.NotExitable

        val statsDao = AppDatabase.getInstance(context).focusStatsDao()
        val now = System.currentTimeMillis()
        for (session in statsDao.getRunningSessions()) {
            statsDao.update(session.stoppedAt(now))
        }
        dataStore.setManualFocusStateToInactive()
        broadcast(context)
        return Result.Stopped
    }

    private fun broadcast(context: Context) {
        context.sendBroadcast(
            Intent(FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE).setPackage(context.packageName)
        )
    }
}
