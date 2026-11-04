package neth.iecal.curbox.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import org.json.JSONArray
import java.io.File

/**
 * Keeps companion apps running from Curbox's own always on blocker service, so a companion such
 * as Curbox Sync needs no foreground service and notification of its own.
 *
 * The main process (API calls, revokes) writes the list of hosted packages. The blocker process
 * reads it and binds each one's [CurboxApiContract.ACTION_COMPANION] service. A bound service
 * shares the blocker's priority, which is the whole trick: nothing is sent over the binding.
 */
object CompanionHost {

    private const val TAG = "CompanionHost"
    private const val FILE = "api_companions.json"
    const val ACTION_REFRESH = "neth.iecal.curbox.refresh.companions"

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    @Synchronized
    fun hosted(context: Context): Set<String> = try {
        val f = file(context)
        if (!f.exists()) emptySet()
        else JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read companions", e)
        emptySet()
    }

    /** Main process: [packageName] asks to be kept running, or no longer. */
    @Synchronized
    fun request(context: Context, packageName: String, enable: Boolean) {
        val current = hosted(context)
        val next = if (enable) current + packageName else current - packageName
        // Re-sent even when unchanged, so a companion that declined earlier is bound again.
        if (next != current) write(context, next)
        if (enable || next != current) notifyBlocker(context)
    }

    @Synchronized
    fun clear(context: Context) {
        if (hosted(context).isEmpty()) return
        write(context, emptySet())
        notifyBlocker(context)
    }

    private fun write(context: Context, packages: Set<String>) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(JSONArray(packages.sorted()).toString())
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun notifyBlocker(context: Context) {
        context.sendBroadcast(Intent(ACTION_REFRESH).setPackage(context.packageName))
    }

    /** Blocker process: binds the hosted companions while the blocker service runs. */
    class Binder(private val context: Context) {
        private val main = Handler(Looper.getMainLooper())
        private val bound = mutableMapOf<String, ServiceConnection>()
        private var running = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = refresh()
        }

        fun start() {
            if (running) return
            running = true
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_REFRESH), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            refresh()
        }

        fun stop() {
            if (!running) return
            running = false
            runCatching { context.unregisterReceiver(receiver) }
            main.removeCallbacksAndMessages(null)
            bound.keys.toList().forEach(::unbind)
        }

        fun refresh() {
            if (!running) return
            val wanted = hosted(context)
            (bound.keys - wanted).forEach(::unbind)
            (wanted - bound.keys).forEach(::bind)
        }

        private fun bind(pkg: String) {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    Log.i(TAG, "Hosting $pkg")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The companion's process died; Android rebinds it on its own.
                }

                override fun onBindingDied(name: ComponentName?) {
                    // Usually an app update. Bind again once it settles.
                    unbind(pkg)
                    main.postDelayed({ refresh() }, REBIND_DELAY_MS)
                }

                override fun onNullBinding(name: ComponentName?) {
                    // The companion declined, for example because its sync is off.
                    unbind(pkg)
                }
            }
            val ok = try {
                context.bindService(
                    Intent(CurboxApiContract.ACTION_COMPANION).setPackage(pkg),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind $pkg", e)
                false
            }
            if (ok) bound[pkg] = connection
            else runCatching { context.unbindService(connection) }
        }

        private fun unbind(pkg: String) {
            val connection = bound.remove(pkg) ?: return
            runCatching { context.unbindService(connection) }
        }

        private companion object {
            const val REBIND_DELAY_MS = 5_000L
        }
    }
}
