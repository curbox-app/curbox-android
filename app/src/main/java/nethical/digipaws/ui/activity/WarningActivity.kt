package nethical.digipaws.ui.activity

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.databinding.DialogWarningOverlayBinding
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.services.ViewBlockerService
import nethical.digipaws.utils.SavedPreferencesLoader


class WarningActivity : AppCompatActivity() {

    private var proceedTimer: CountDownTimer? = null
    private var dialog: AlertDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedPreferencesLoader = SavedPreferencesLoader(this)


        val mode = intent.getIntExtra("mode", 0)
        val warningScreenConfig = if (mode == Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER) {
            savedPreferencesLoader.loadViewBlockerWarningInfo()
        } else {
            savedPreferencesLoader.loadAppBlockerWarningInfo()

        }
        val binding = DialogWarningOverlayBinding.inflate(layoutInflater)
        val isHomePressRequested = intent.getBooleanExtra("is_press_home", false)
        binding.minsPicker.setValue(3)
        binding.minsPicker.minValue = 2
        val isDialogCancelable =
            mode != Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested

        if (warningScreenConfig.isProceedDisabled) {
            binding.btnProceed.visibility = View.GONE
            binding.proceedSeconds.visibility = View.GONE

        } else {
            proceedTimer =
                object : CountDownTimer(warningScreenConfig.proceedDelayInSecs * 1000L, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    binding.proceedSeconds.text =
                        getString(R.string.proceed_in, millisUntilFinished / 1000)
                }

                override fun onFinish() {
                    binding.btnProceed.let { button ->
                        button.isEnabled = true
                        if (warningScreenConfig.isDynamicIntervalSettingAllowed) {
                            binding.minsPicker.visibility = View.VISIBLE
                        }
                        button.setText(R.string.proceed)
                    }
                    binding.proceedSeconds.visibility = View.GONE
                }
            }.start()
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(isDialogCancelable)
            .setOnCancelListener {
                finishAffinity()
            }
            .show()
        binding.warningMsg.text = warningScreenConfig.message
        binding.minsPicker.setValue(warningScreenConfig.timeInterval / 60000)
        binding.btnCancel.setOnClickListener {
            if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested) {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_HOME)
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            dialog?.dismiss()
            finishAffinity()
        }
        binding.btnProceed.setOnClickListener {
            if (mode == Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER) {
                intent.getStringExtra("result_id")
                    ?.let { it1 ->
                        sendRefreshRequest(
                            it1,
                            ViewBlockerService.INTENT_ACTION_REFRESH_VIEW_BLOCKER_COOLDOWN,
                            binding.minsPicker.getValue()
                        )
                    }
            }

            if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER) {
                intent.getStringExtra("result_id")
                    ?.let { it1 ->
                        sendRefreshRequest(
                            it1,
                            AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN,
                            binding.minsPicker.getValue()
                        )
                        val intent = packageManager.getLaunchIntentForPackage(it1)
                        if (intent != null) {
                            startActivity(intent)
                        }
                    }
            }

            dialog?.dismiss()
            finishAffinity()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        proceedTimer?.onFinish()
        dialog?.dismiss()  // Ensure dialog is dismissed before activity is destroyed


    }

    private fun sendRefreshRequest(id: String, action: String, time: Int) {
        val intent = Intent(action)
        intent.putExtra("result_id", id)
        intent.putExtra("selected_time", time * 60_000)
        sendBroadcast(intent)
    }
}