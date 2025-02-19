package nethical.digipaws.ui.activity

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.databinding.DialogWarningOverlayBinding
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.services.ViewBlockerService


class WarningActivity : AppCompatActivity() {

    private var proceedTimer: CountDownTimer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getIntExtra("mode", 0)
        val binding = DialogWarningOverlayBinding.inflate(layoutInflater)
        val isHomePressRequested = intent.getBooleanExtra("is_press_home", false)
        binding.minsPicker.setValue(3)
        binding.minsPicker.minValue = 2
        val isDialogCancelable =
            mode != Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested

        if (intent.getBooleanExtra("is_proceed_disabled", false)) {
            binding.btnProceed.visibility = View.GONE
            binding.proceedSeconds.visibility = View.GONE

        } else {
            proceedTimer = object : CountDownTimer(15000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    binding.proceedSeconds.text =
                        getString(R.string.proceed_in, millisUntilFinished / 1000)
                }

                override fun onFinish() {
                    binding.btnProceed.let { button ->
                        button.isEnabled = true
                        if (intent.getBooleanExtra("is_dynamic_timing", false)) {
                            binding.minsPicker.visibility = View.VISIBLE
                        }
                        button.setText(R.string.proceed)
                    }
                    binding.proceedSeconds.visibility = View.GONE
                }
            }.start()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(isDialogCancelable)
            .setOnCancelListener {
                finishAffinity()
            }
            .show()
        binding.warningMsg.text = intent.getStringExtra("warning_message")
        binding.minsPicker.setValue(intent.getIntExtra("default_cooldown", 1))
        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
            if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested) {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_HOME)
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
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

            dialog.dismiss()
            finishActivity(0)
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        proceedTimer?.onFinish()

    }

    private fun sendRefreshRequest(id: String, action: String, time: Int) {
        val intent = Intent(action)
        intent.putExtra("result_id", id)
        intent.putExtra("selected_time", time * 60000)
        sendBroadcast(intent)
    }
}