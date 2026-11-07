package neth.iecal.curbox.api

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.utils.DataStoreManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The exported bound service other apps talk to, like Shizuku's service. A client binds this and
 * calls the [ICurboxApi] methods. Every call is checked against the calling app's identity: only
 * apps the user has allowed in [ApiAuthStore] may run commands or read state.
 *
 * This lives in the main process so its DataStore writes and broadcasts behave exactly like an in
 * app change.
 */
class CurboxApiService : Service() {

    private val gson = Gson()
    private val commandMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = RemoteCallbackList<ICurboxListener>()
    private var watchJob: Job? = null

    private val binder = object : ICurboxApi.Stub() {

        override fun apiVersion(): Int = CurboxApiContract.API_VERSION

        override fun isGranted(): Boolean = callerAllowed()

        override fun execute(command: String?, args: Bundle?): String {
            if (!callerAllowed()) return CurboxApiContract.STATUS_DENIED
            if (ApiCommand.fromNameOrNull(command) == null) return CurboxApiContract.STATUS_UNKNOWN_COMMAND
            return try {
                // Capture Binder identity before entering any coroutine.
                val callerPackages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
                val requestedPackage = args?.getString("provider_package")
                val callerPackage = callerPackages.firstOrNull {
                    (requestedPackage == null || requestedPackage == it) &&
                        ApiAuthStore.isAnyGranted(applicationContext, arrayOf(it))
                } ?: return CurboxApiContract.STATUS_DENIED
                val revision = ApiAuthStore.revision(applicationContext)
                runBlocking {
                    commandMutex.withLock {
                        if (revision != ApiAuthStore.revision(applicationContext) ||
                            !ApiAuthStore.isAnyGranted(applicationContext, arrayOf(callerPackage))) {
                            CurboxApiContract.STATUS_DENIED
                        } else {
                            val applied = CurboxApiCommands.runCommand(
                                applicationContext, command, args ?: Bundle(), callerPackage
                            )
                            if (applied) CurboxApiContract.STATUS_OK else CurboxApiContract.STATUS_FAILED
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "execute failed for $command", e)
                CurboxApiContract.STATUS_FAILED
            }
        }

        override fun query(state: String?): String? {
            if (!callerAllowed()) return null
            return try {
                val values = runBlocking {
                    CurboxApiCommands.queryState(applicationContext, state)
                } ?: return null
                gson.toJson(values)
            } catch (e: Exception) {
                Log.e(TAG, "query failed for $state", e)
                null
            }
        }

        override fun list(kind: String?): String? {
            if (!callerAllowed()) return null
            return try {
                val data = runBlocking {
                    CurboxApiCommands.list(applicationContext, kind)
                } ?: return null
                gson.toJson(data)
            } catch (e: Exception) {
                Log.e(TAG, "list failed for $kind", e)
                null
            }
        }

        override fun addListener(listener: ICurboxListener?) {
            if (listener == null || !callerAllowed()) return
            val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
            listeners.register(listener, packages)
            watch()
        }

        override fun removeListener(listener: ICurboxListener?) {
            if (listener != null) listeners.unregister(listener)
        }
    }

    /** Watches focus while anyone listens. DataStore is multi process, so blocker changes show too. */
    @Synchronized
    private fun watch() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            val settings = DataStoreManager(applicationContext).settings
            launch {
                settings
                    .map { s -> Triple(s.activeManualFocusGroupId, s.activePomodoroState, s.manualFocusGroups.map { it.groupId to it.groupName }) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { notifyListeners(CHANGED_FOCUS) }
            }
            // Version 7: a group's websites, mode or exit rule changed.
            launch {
                settings
                    .map { s -> s.manualFocusGroups.map { listOf(it.groupId, it.groupName, it.keywords, it.blockMode, it.exitable, it.packages) } }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { notifyListeners(CHANGED_FOCUS_GROUPS) }
            }
        }
    }

    @Synchronized
    private fun notifyListeners(what: String) {
        val count = listeners.beginBroadcast()
        try {
            for (i in 0 until count) {
                val packages = listeners.getBroadcastCookie(i) as? Array<*>
                val allowed = ApiAuthStore.isAnyGranted(
                    applicationContext, packages?.filterIsInstance<String>()?.toTypedArray()
                )
                if (!allowed) continue
                runCatching { listeners.getBroadcastItem(i).onChanged(what) }
            }
        } finally {
            listeners.finishBroadcast()
        }
    }

    override fun onDestroy() {
        listeners.kill()
        scope.cancel()
        super.onDestroy()
    }

    private fun callerAllowed(): Boolean {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid())
        return ApiAuthStore.isAnyGranted(applicationContext, packages)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "CurboxApiService"
        private const val CHANGED_FOCUS = "FOCUS"
        private const val CHANGED_FOCUS_GROUPS = "FOCUS_GROUPS"
    }
}
