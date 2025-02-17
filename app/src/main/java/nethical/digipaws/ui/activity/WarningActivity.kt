package nethical.digipaws.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.databinding.DialogWarningOverlayBinding
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.services.ViewBlockerService

class WarningActivity : AppCompatActivity() {
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var binding: DialogWarningOverlayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogWarningOverlayBinding.inflate(layoutInflater)

        val mode = intent.getIntExtra("mode", 0)
        val isHomePressRequested = intent.getBooleanExtra("is_press_home", false)
        binding.minsPicker.setValue(3)
        binding.minsPicker.minValue = 2
        val isDialogCancelable = mode != Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested

        // Configurar visibilidad de elementos
        binding.proceedSeconds.visibility = View.GONE
        if (intent.getBooleanExtra("is_proceed_disabled", false)) {
            binding.btnProceed.visibility = View.GONE
        } else {
            binding.btnProceed.isEnabled = true
            if (intent.getBooleanExtra("is_dynamic_timing", false)) {
                binding.minsPicker.visibility = View.VISIBLE
            }
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(isDialogCancelable)
            .setOnCancelListener {
                cleanupAndFinish()
            }
            .setOnDismissListener {
                cleanupAndFinish()
            }
            .show()

        binding.warningMsg.text = intent.getStringExtra("warning_message")
        binding.minsPicker.setValue(intent.getIntExtra("default_cooldown", 1))

        binding.btnCancel.setOnClickListener {
            if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested) {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_HOME)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
            cleanupAndFinish()
        }

        binding.btnProceed.setOnClickListener {
            when (mode) {
                Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER -> {
                    intent.getStringExtra("result_id")?.let { resultId ->
                        sendRefreshRequest(
                            resultId,
                            ViewBlockerService.INTENT_ACTION_REFRESH_VIEW_BLOCKER_COOLDOWN,
                            binding.minsPicker.getValue()
                        )
                    }
                }
                Constants.WARNING_SCREEN_MODE_APP_BLOCKER -> {
                    intent.getStringExtra("result_id")?.let { resultId ->
                        sendRefreshRequest(
                            resultId,
                            AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN,
                            binding.minsPicker.getValue()
                        )
                        packageManager.getLaunchIntentForPackage(resultId)?.let { appIntent ->
                            startActivity(appIntent)
                        }
                    }
                }
            }
            cleanupAndFinish()
        }
    }

    private fun cleanupAndFinish() {
        dialog?.dismiss()
        dialog = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        dialog = null
    }

    private fun sendRefreshRequest(id: String, action: String, time: Int) {
        val intent = Intent(action)
        intent.putExtra("result_id", id)
        intent.putExtra("selected_time", time * 60000)
        sendBroadcast(intent)
    }
}