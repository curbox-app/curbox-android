package neth.iecal.curbox.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R

/**
 * One time announcement of the Curbox browser extension.
 *
 * Existing users see it once on the first launch after updating. New users meet
 * the extension inside onboarding instead, which marks it as seen so the dialog
 * does not repeat what they just read.
 */
object ExtensionPromo {

    private const val PREFS = "AppPreferences"
    private const val KEY_SEEN = "extension_promo_seen"

    const val EXTENSION_URL = "https://github.com/curbox-app/curbox-extension"

    /** Shows the announcement if this user has not seen it. Returns true when it was shown. */
    fun maybeShow(activity: AppCompatActivity): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEN, false)) return false

        // Wait for the terms dialog and onboarding to be out of the way.
        if (!prefs.getBoolean("hasAcceptedTerms", false)) return false
        if (!prefs.getBoolean("isFirstLaunchComplete", false)) return false

        show(activity)
        return true
    }

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEEN, true).apply()
    }

    fun show(activity: AppCompatActivity) {
        markSeen(activity)

        val view = activity.layoutInflater.inflate(R.layout.dialog_extension_promo, null)

        MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setPositiveButton(R.string.extension_promo_get) { _, _ -> openUrl(activity) }
            // The extension is mostly installed on a computer, so make it easy to send the link there.
            .setNeutralButton(R.string.extension_promo_share) { _, _ -> shareLink(activity) }
            .setNegativeButton(R.string.extension_promo_later, null)
            .show()
    }

    private fun openUrl(activity: AppCompatActivity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EXTENSION_URL)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shareLink(activity: AppCompatActivity) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.extension_promo_share_message, EXTENSION_URL))
            }
            activity.startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
