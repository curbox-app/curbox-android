package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.SettingsChangeDelayUtils

/**
 * Warning screen the settings change delay gate pops right after it parks a change that
 * weakens a rule. It gives the user one clear moment to catch an accidental change: undo it
 * on the spot, or let it wait out the countdown. Dismissing it keeps the change waiting.
 */
class PendingChangeReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FIELD = "field"
        const val EXTRA_APPLIES_AT_MS = "applies_at_ms"
        const val EXTRA_REPLACED_EXISTING = "replaced_existing"
        const val EXTRA_TAMPER_GATED = "tamper_gated"
        const val EXTRA_KEPT_TIMER = "kept_timer"
    }

    private var dialog: AlertDialog? = null
    private var fieldName = ""
    private var appliesAtMs = 0L
    private var replacedExisting = false
    private var tamperGated = false
    private var keptTimer = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            // A tamper gated change has no real countdown to wait out; it stays parked until
            // the user turns tamper protection off, so the dialog shouldn't auto dismiss on it
            if (!tamperGated && appliesAtMs - System.currentTimeMillis() <= 0) {
                finish()
                return
            }
            dialog?.setMessage(buildMessage())
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        showDialog()
        lifecycleScope.launch {
            DataStoreManager(applicationContext).settings.collect { settings ->
                val pending = settings.settingsChangeDelayConfig2.pendingChanges
                    .find { it.field == fieldName }
                if (pending == null || pending.appliesAtMs != appliesAtMs) {
                    finish()
                } else {
                    tamperGated = settings.settingsChangeDelayConfig2.requireTamperProtectionOff &&
                        settings.antiUninstallConfig2.isEnabled
                    dialog?.setMessage(buildMessage())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
        dialog?.setMessage(buildMessage())
    }

    private fun readExtras(intent: Intent) {
        fieldName = intent.getStringExtra(EXTRA_FIELD) ?: ""
        appliesAtMs = intent.getLongExtra(EXTRA_APPLIES_AT_MS, 0L)
        replacedExisting = intent.getBooleanExtra(EXTRA_REPLACED_EXISTING, false)
        tamperGated = intent.getBooleanExtra(EXTRA_TAMPER_GATED, false)
        keptTimer = intent.getBooleanExtra(EXTRA_KEPT_TIMER, false)
    }

    private fun buildMessage(): String {
        val label = SettingsChangeDelayUtils.fieldLabel(this, fieldName)
        val remaining = appliesAtMs - System.currentTimeMillis()
        var message = if (remaining > 0) {
            getString(
                R.string.change_delay_review_message,
                label,
                SettingsChangeDelayUtils.formatRemaining(this, remaining)
            )
        } else {
            getString(R.string.change_delay_review_message_no_timer, label)
        }
        if (tamperGated) {
            message += "\n\n" + getString(R.string.change_delay_review_tamper_note)
        }
        if (keptTimer) {
            message += "\n\n" + getString(R.string.change_delay_review_kept_timer_note)
        } else if (replacedExisting) {
            message += "\n\n" + getString(R.string.change_delay_review_replaced_note)
        }
        return message
    }

    private fun showDialog() {
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_delay_review_title)
            .setMessage(buildMessage())
            .setPositiveButton(R.string.change_delay_review_keep) { _, _ -> finish() }
            .setNegativeButton(R.string.change_delay_review_undo) { _, _ -> undoChange() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun undoChange() {
        val field = fieldName
        lifecycleScope.launch {
            try {
                DataStoreManager(applicationContext).cancelPendingSettingsChange(field, appliesAtMs)
                Toast.makeText(applicationContext, R.string.change_delay_cancelled_toast, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(applicationContext).logNonFatalError(e)
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tickRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(tickRunnable)
        dialog?.dismiss()
        dialog = null
    }
}
