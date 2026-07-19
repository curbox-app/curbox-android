package neth.iecal.curbox.utils

import android.content.Context
import android.content.Intent
import rikka.shizuku.Shizuku
import neth.iecal.curbox.data.models.FocusBlockMode
import android.util.Log

object AppSuspendHelper {

    // Todo: Sometimes user start focus mode, apps get suspended but in midst of that, they turn off shizuku. This creates a forever suspend bug
    fun suspendApps(packages: List<String>) {
//        executePmCommand(packages, "suspend")
    }

    fun unsuspendApps(packages: List<String>) {
        executePmCommand(packages, "unsuspend")
    }

    fun unsuspendAllApps(context: Context) {
        if (!isShizukuAvailable()) return
        Thread {
            try {
                val allPackages = getInstalledPackagesSafe(context)
                executePmCommand(allPackages, "unsuspend")
            } catch (e: Exception) {
                Log.e("AppSuspendHelper", "Failed to unsuspend all apps", e)
            }
        }.start()
    }

    fun getPackagesToSuspend(
        context: Context,
        blockMode: FocusBlockMode,
        groupPackages: Set<String>,
        essentialPackages: Set<String>
    ): List<String> {
        return if (blockMode == FocusBlockMode.BLOCK_SELECTED) {
            groupPackages.toList()
        } else {
            val allPackages = getInstalledPackagesSafe(context)
            allPackages.filter { it !in groupPackages && it !in essentialPackages }
        }
    }

    private fun getInstalledPackagesSafe(context: Context): List<String> {
        return try {
            context.packageManager.getInstalledPackages(0).map { it.packageName }
        } catch (e: Exception) {
            Log.w("AppSuspendHelper", "getInstalledPackages failed, falling back to queryIntentActivities", e)
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
        }
    }

    private fun executePmCommand(packages: List<String>, commandType: String) {
        if (!isShizukuAvailable() || packages.isEmpty()) return
        Thread {
            packages.chunked(40).forEach { chunk ->
                val command = "pm $commandType ${chunk.joinToString(" ")}"
                ShizukuRunner.executeCommand(command, object : ShizukuRunner.CommandResultListener {
                    override fun onCommandError(error: String) {
                        super.onCommandError(error)
                    }
                })
            }
        }.start()
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}
