package neth.iecal.curbox.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.data.models.ExternalUnlockCondition
import neth.iecal.curbox.utils.DataStoreManager

object ExternalUnlockProviders {
    const val ACTION_CHECK = "neth.iecal.curbox.api.CHECK_UNLOCK_CONDITION"
    const val EXTRA_CONDITION_ID = "condition_id"
    const val EXTRA_REQUEST_ID = "request_id"

    suspend fun register(context: Context, packageName: String, args: Bundle) {
        val id = args.getString("id").orEmpty()
        // Strip invisible and direction control characters so a label cannot disguise the package name shown next to it.
        val label = args.getString("label").orEmpty()
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim()
        val activity = args.getString("activity").orEmpty()
        require(id.matches(Regex("[A-Za-z0-9_.]{1,100}")))
        require(label.isNotEmpty() && label.length <= 100)
        require(activity.isNotBlank() && activity.length <= 300)
        val component = ComponentName(packageName,
            if (activity.startsWith(".")) packageName + activity else activity)
        val info = context.packageManager.getActivityInfo(component, 0)
        require(info.exported && info.enabled && info.applicationInfo.enabled && info.permission == null)
        val condition = ExternalUnlockCondition(packageName, id, label, component.className,
            ApiPackageIdentity.signingCertificates(context, packageName))
        require(condition.signingCertificates.isNotEmpty())
        DataStoreManager(context).registerExternalUnlockCondition(condition)
    }

    fun isAvailable(context: Context, condition: ExternalUnlockCondition): Boolean = try {
        val info = context.packageManager.getActivityInfo(
            ComponentName(condition.packageName, condition.activityName), 0)
        ApiAuthStore.isAnyGranted(context, arrayOf(condition.packageName)) &&
            info.exported && info.enabled && info.applicationInfo.enabled && info.permission == null &&
            condition.signingCertificates.isNotEmpty() &&
            condition.signingCertificates == ApiPackageIdentity.signingCertificates(context, condition.packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    suspend fun isRegistered(context: Context, condition: ExternalUnlockCondition): Boolean =
        condition in DataStoreManager(context).settings.first().externalUnlockConditions &&
            isAvailable(context, condition)

    fun checkIntent(condition: ExternalUnlockCondition, requestId: String): Intent =
        Intent(ACTION_CHECK)
            .setComponent(ComponentName(condition.packageName, condition.activityName))
            .putExtra(EXTRA_CONDITION_ID, condition.id)
            .putExtra(EXTRA_REQUEST_ID, requestId)

}
