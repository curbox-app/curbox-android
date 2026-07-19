package neth.iecal.curbox.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.databinding.DialogWarningOverlayBinding
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import neth.iecal.curbox.anti_stimulants.MindfulMessage

class WarningActivity : AppCompatActivity() {

    private var proceedTimer: CountDownTimer? = null
    private var dialog: AlertDialog? = null

    private var vibrator: Vibrator? = null

    private var isQrScanned = false
    private var scannedValidDuration = -1L

    private lateinit var binding: DialogWarningOverlayBinding
    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result ->
        if (result.contents == null) {
            Toast.makeText(this@WarningActivity, R.string.warning_cancelled, Toast.LENGTH_LONG).show()
        } else {
            val warningScreenConfig = Gson().fromJson<AppBlockerWarningScreenConfig>(
                intent.getStringExtra("warning_config"),
                AppBlockerWarningScreenConfig::class.java
            )
            if (warningScreenConfig.qrKeys.containsKey(result.contents)) {
                isQrScanned = true
                scannedValidDuration = warningScreenConfig.qrKeys[result.contents] ?: -1L
                
                binding.btnProceed.isEnabled = true
                binding.btnProceed.setText(R.string.proceed)
                
                if (scannedValidDuration == -1L) {
                    binding.minsPicker.visibility = View.VISIBLE
                } else {
                    binding.minsPicker.visibility = View.GONE
                }
            } else {
                 Toast.makeText(this@WarningActivity, R.string.warning_invalid_qr, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getIntExtra("mode", 0)

        val warningScreenConfig = Gson().fromJson<AppBlockerWarningScreenConfig>(
            intent.getStringExtra("warning_config"),
            AppBlockerWarningScreenConfig::class.java
        )

        val targetId = intent.getStringExtra("result_id") ?: ""
        var isProceedLimitExceeded = false
        var timeUntilNextProceedMn = 0L
        var proceedsLeft = -1

        if (warningScreenConfig.proceedLimitEnabled && targetId.isNotEmpty()) {
            val limitPrefs = getSharedPreferences("proceed_limits", Context.MODE_PRIVATE)
            val historyString = limitPrefs.getString("proceeds_$targetId", "") ?: ""
            val history = historyString.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()

            val nowTime = System.currentTimeMillis()
            val windowMillis = warningScreenConfig.proceedsTimeWindowMn * 60_000L
            val validHistory = history.filter { nowTime - it < windowMillis }

            if (validHistory.size >= warningScreenConfig.allowedProceeds) {
                isProceedLimitExceeded = true
                val oldestProceed = validHistory.minOrNull() ?: nowTime
                val expirationTime = oldestProceed + windowMillis
                timeUntilNextProceedMn = (expirationTime - nowTime + 59_999) / 60_000L
            } else {
                proceedsLeft = warningScreenConfig.allowedProceeds - validHistory.size
            }
        }

        if (warningScreenConfig.vibrateAndIncBrightness) {
            val layoutParams = window.attributes
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            window.attributes = layoutParams

            triggerRandomizedVibration(maxOf(3000L, (warningScreenConfig.proceedDelayInSecs / 2) * 1000L))
        }

        binding = DialogWarningOverlayBinding.inflate(layoutInflater)
        val isHomePressRequested = intent.getBooleanExtra("is_press_home", false)
        binding.minsPicker.setValue(3)
        binding.minsPicker.minValue = 2
        val isDialogCancelable =
            mode != Constants.WARNING_SCREEN_MODE_APP_BLOCKER || isHomePressRequested

        if (warningScreenConfig.isProceedDisabled || isProceedLimitExceeded) {
            binding.btnProceed.visibility = View.GONE
            binding.btnCancel.setText(R.string.okay)
            if (isProceedLimitExceeded) {
                binding.proceedSeconds.visibility = View.VISIBLE
                binding.proceedSeconds.text = getString(R.string.warning_proceed_limit_reached, warningScreenConfig.allowedProceeds, warningScreenConfig.proceedsTimeWindowMn, timeUntilNextProceedMn)
            } else {
                binding.proceedSeconds.visibility = View.GONE
            }

        } else {
            if (proceedsLeft >= 0) {
                binding.proceedsLeft.visibility = View.VISIBLE
                binding.proceedsLeft.text = getString(R.string.warning_proceeds_left, proceedsLeft, warningScreenConfig.allowedProceeds)
            }
            proceedTimer =
                object : CountDownTimer(warningScreenConfig.proceedDelayInSecs * 1000L, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        binding.proceedSeconds.text =
                            getString(R.string.proceed_in, millisUntilFinished / 1000)
                    }

                    override fun onFinish() {
                        binding.btnProceed.let { button ->
                            if (!warningScreenConfig.isQrUnlockRequirementEnabled && warningScreenConfig.isDynamicIntervalSettingAllowed) {
                                binding.minsPicker.visibility = View.VISIBLE
                            }

                            if (warningScreenConfig.isIntentRequirementEnabled) {
                                binding.intentInputLayout.visibility = View.VISIBLE
                                button.isEnabled = false
                                button.setText(R.string.proceed)

                                val minLength = warningScreenConfig.minIntentLength.coerceAtLeast(1)
                                fun updateIntentInputState(length: Int) {
                                    button.isEnabled = length >= minLength
                                    binding.intentInputLayout.helperText = if (length < minLength) {
                                        resources.getQuantityString(
                                            R.plurals.warning_intent_chars_needed,
                                            minLength,
                                            minLength,
                                            minLength - length
                                        )
                                    } else {
                                        null
                                    }
                                }
                                binding.intentInputEdit.doAfterTextChanged { s ->
                                    updateIntentInputState(s?.toString()?.trim()?.length ?: 0)
                                }
                                updateIntentInputState(binding.intentInputEdit.text.toString().trim().length)
                            } else if (warningScreenConfig.isTypingRequirementEnabled) {
                                binding.typingTargetSentence.visibility = View.VISIBLE
                                binding.typingTargetSentence.text = getString(R.string.warning_typing_quote, warningScreenConfig.typingSentence)
                                binding.typingInputLayout.visibility = View.VISIBLE
                                button.isEnabled = false
                                button.setText(R.string.proceed)
                                
                                binding.typingInputEdit.doAfterTextChanged { s ->
                                    button.isEnabled = s?.toString() == warningScreenConfig.typingSentence
                                }
                            } else if (warningScreenConfig.isQrUnlockRequirementEnabled && !isQrScanned) {
                                button.text = getString(R.string.warning_scan_qr_code)
                                button.isEnabled = true
                            } else {
                                button.setText(R.string.proceed)
                                button.isEnabled = true
                            }
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

        if (warningScreenConfig.isOnOpenConfig) {
            binding.minsPicker.visibility = View.GONE
        } else {
            binding.minsPicker.setValue(warningScreenConfig.timeInterval / 60000)
        }

        binding.btnCancel.setOnClickListener {
            if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER || mode == Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER || isHomePressRequested) {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_HOME)
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            dialog?.dismiss()
            finishAffinity()
        }

        binding.btnProceed.setOnClickListener {
            if (warningScreenConfig.isQrUnlockRequirementEnabled && !isQrScanned) {
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                options.setPrompt("Scan a QR Code to unlock")
                options.setCameraId(0) // Use a specific camera of the device
                options.setBeepEnabled(false)
                options.setBarcodeImageEnabled(true)
                options.setCaptureActivity(neth.iecal.curbox.ui.activity.PortraitCaptureActivity::class.java)
                barcodeLauncher.launch(options)
                return@setOnClickListener
            }

            if (warningScreenConfig.proceedLimitEnabled && targetId.isNotEmpty()) {
                val limitPrefs = getSharedPreferences("proceed_limits", Context.MODE_PRIVATE)
                val historyString = limitPrefs.getString("proceeds_$targetId", "") ?: ""
                val history = historyString.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
                val nowTime = System.currentTimeMillis()
                val windowMillis = warningScreenConfig.proceedsTimeWindowMn * 60_000L
                val validHistory = history.filter { nowTime - it < windowMillis }.toMutableList()
                
                validHistory.add(nowTime)
                limitPrefs.edit { putString("proceeds_$targetId", validHistory.joinToString(",")) }
            }

            if (warningScreenConfig.isIntentRequirementEnabled) {
                val intentText = binding.intentInputEdit.text.toString().trim()
                if (intentText.length < warningScreenConfig.minIntentLength.coerceAtLeast(1)) {
                    return@setOnClickListener
                }
                val pkg = if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER) {
                    intent.getStringExtra("launch_package") ?: targetId
                } else {
                    targetId
                }
                val time = binding.minsPicker.getValue() * 60_000L
                
                CoroutineScope(Dispatchers.IO).launch {
                    val log = neth.iecal.curbox.data.db.IntentLogEntity(
                        timestamp = System.currentTimeMillis(),
                        packageName = pkg,
                        intentText = intentText,
                        unlockedDurationMs = time
                    )
                    neth.iecal.curbox.data.db.AppDatabase.getInstance(this@WarningActivity).intentLogDao().insert(log)
                }

                val broadcastIntent = Intent(MindfulMessage.ADD_NEW_INTENT)
                broadcastIntent.putExtra("package_name", pkg)
                broadcastIntent.putExtra("intent_text", intentText)
                broadcastIntent.putExtra("duration_ms", time)
                sendBroadcast(broadcastIntent)
            }

            if (mode == Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER) {
                intent.getStringExtra("result_id")
                    ?.let { it1 ->
                        val finalTime = if (warningScreenConfig.isQrUnlockRequirementEnabled && scannedValidDuration != -1L) {
                            (scannedValidDuration / 60000).toInt()
                        } else {
                            binding.minsPicker.getValue()
                        }
                        sendRefreshRequest(
                            it1,
                            ReelBlocker.INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN,
                            finalTime
                        )
                    }
            }

            if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER) {
                intent.getStringExtra("result_id")
                    ?.let { it1 ->
                        val finalTime = if (warningScreenConfig.isOnOpenConfig) {
                            1440
                        } else if (warningScreenConfig.isQrUnlockRequirementEnabled && scannedValidDuration != -1L) {
                            (scannedValidDuration / 60000).toInt()
                        } else {
                            binding.minsPicker.getValue()
                        }
                        sendRefreshRequest(
                            it1,
                            AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN,
                            finalTime
                        )
                        val launchPackage = intent.getStringExtra("launch_package") ?: it1
                        val intent = packageManager.getLaunchIntentForPackage(launchPackage)
                        if (intent != null) {
                            startActivity(intent)
                        }
                    }
            }

            if (mode == Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER) {
                intent.getStringExtra("result_id")
                    ?.let { it1 ->
                        val finalTime = if (warningScreenConfig.isQrUnlockRequirementEnabled && scannedValidDuration != -1L) {
                            (scannedValidDuration / 60000).toInt()
                        } else {
                            binding.minsPicker.getValue()
                        }
                        sendRefreshRequest(
                            it1,
                            KeywordBlocker.INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN,
                            finalTime
                        )
                    }
            }

            dialog?.dismiss()
            finishAffinity()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        proceedTimer?.cancel()
        vibrator?.cancel()
        dialog?.dismiss()
    }

    private fun sendRefreshRequest(id: String, action: String, time: Int) {
        val intent = Intent(action)
        intent.putExtra("result_id", id)
        intent.putExtra("selected_time", time * 60_000)
        sendBroadcast(intent)
    }

    // Jagged rhythm prevents habituation and breaks the habit loop.
    private fun triggerRandomizedVibration(durationMillis: Long) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        vibrator?.let { currentVibrator ->
            if (currentVibrator.hasVibrator()) {
                val patternList = mutableListOf<Long>()
                patternList.add(0L)

                var elapsedTime = 0L

                while (elapsedTime < durationMillis) {
                    val vibrateDuration = Random.nextLong(40, 250)
                    val pauseDuration = Random.nextLong(40, 150)

                    if (elapsedTime + vibrateDuration >= durationMillis) {
                        patternList.add(durationMillis - elapsedTime)
                        break
                    }
                    patternList.add(vibrateDuration)
                    elapsedTime += vibrateDuration

                    if (elapsedTime + pauseDuration >= durationMillis) {
                        patternList.add(durationMillis - elapsedTime)
                        break
                    }
                    patternList.add(pauseDuration)
                    elapsedTime += pauseDuration
                }

                val jaggedPattern = patternList.toLongArray()

                currentVibrator.vibrate(VibrationEffect.createWaveform(jaggedPattern, -1))
            }
        }
    }
}
