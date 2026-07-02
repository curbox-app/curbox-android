package neth.iecal.curbox.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import neth.iecal.curbox.R

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.anti_uninstall_disable_warning)
    }
}
