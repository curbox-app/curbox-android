package neth.iecal.curbox.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import neth.iecal.curbox.Constants
import neth.iecal.curbox.nfc.NfcUnlockUtils
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.databinding.DialogWarningOverlayBinding
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.FocusGoalProgress
import java.util.Calendar
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import neth.iecal.curbox.anti_stimulants.MindfulMessage

class WarningActivity : AppCompatActivity() {

    private var proceedTimer: CountDownTimer? = null
    private var dialog: AlertDialog? = null

    private var vibrator: Vibrator? = null

    private var isQrScanned = false
    private var isNfcScanned = false
    private var nfcReaderActive = false
    private var nfcTapDialog: AlertDialog? = null
    private var scannedValidDuration = -1L
    private var adaptiveMathChallenge: AdaptiveMathChallenge? = null
    private var isAdaptiveMathComplete = false
    private var isFocusGoalRequired = false
    private var isFocusGoalVerified = true
    private var isPrimaryUnlockActionReady = false

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
                
                setPrimaryUnlockActionReady(true)
                binding.btnProceed.setText(R.string.proceed)
                
                if (scannedValidDuration == -1L && !warningScreenConfig.isOnOpenConfig) {
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
                val expirationTime = if (windowMillis > Long.MAX_VALUE - oldestProceed) {
                    Long.MAX_VALUE
                } else {
                    oldestProceed + windowMillis
                }
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
        isFocusGoalRequired = warningScreenConfig.isFocusGoalRequirementEnabled
        isFocusGoalVerified = !isFocusGoalRequired
        if (isFocusGoalRequired &&
            !warningScreenConfig.isProceedDisabled &&
            !isProceedLimitExceeded
        ) {
            setupFocusGoalRequirement(warningScreenConfig)
        }
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
                            if (!warningScreenConfig.isOnOpenConfig &&
                                !warningScreenConfig.isQrUnlockRequirementEnabled &&
                                !warningScreenConfig.isNfcUnlockRequirementEnabled &&
                                warningScreenConfig.isDynamicIntervalSettingAllowed
                            ) {
                                binding.minsPicker.visibility = View.VISIBLE
                            }

                            if (warningScreenConfig.isIntentRequirementEnabled) {
                                binding.intentInputLayout.visibility = View.VISIBLE
                                setPrimaryUnlockActionReady(false)
                                button.setText(R.string.proceed)

                                val minLength = warningScreenConfig.minIntentLength.coerceAtLeast(1)
                                fun updateIntentInputState(length: Int) {
                                    setPrimaryUnlockActionReady(length >= minLength)
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
                                // Text pasted while configuring the challenge may contain CRLF
                                // line endings, while Android keyboards insert LF. Treat them as
                                // the same so visually identical multiline text can be unlocked.
                                val requiredSentence = warningScreenConfig.typingSentence
                                    .replace("\r\n", "\n")
                                    .replace('\r', '\n')
                                binding.typingTargetSentence.visibility = View.VISIBLE
                                binding.typingTargetSentence.text =
                                    getString(R.string.warning_typing_quote, requiredSentence)
                                binding.typingInputLayout.visibility = View.VISIBLE
                                setPrimaryUnlockActionReady(false)
                                button.setText(R.string.proceed)

                                val mismatchColor = MaterialColors.getColor(
                                    binding.typingInputEdit,
                                    com.google.android.material.R.attr.colorError
                                )
                                binding.typingInputEdit.doAfterTextChanged { s ->
                                    val entered = s ?: return@doAfterTextChanged
                                    entered.getSpans(
                                        0,
                                        entered.length,
                                        ForegroundColorSpan::class.java
                                    ).forEach(entered::removeSpan)

                                    entered.indices.forEach { index ->
                                        if (
                                            index >= requiredSentence.length ||
                                            entered[index] != requiredSentence[index]
                                        ) {
                                            entered.setSpan(
                                                ForegroundColorSpan(mismatchColor),
                                                index,
                                                index + 1,
                                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                            )
                                        }
                                    }
                                    setPrimaryUnlockActionReady(
                                        entered.toString() == requiredSentence
                                    )
                                }
                            } else if (warningScreenConfig.isAdaptiveMathRequirementEnabled) {
                                setupAdaptiveMathChallenge(
                                    warningScreenConfig.adaptiveMathQuestionCount,
                                    warningScreenConfig.adaptiveMathStartingLevel
                                )
                            } else if (warningScreenConfig.isQrUnlockRequirementEnabled && !isQrScanned) {
                                button.text = getString(R.string.warning_scan_qr_code)
                                setPrimaryUnlockActionReady(true)
                            } else if (warningScreenConfig.isNfcUnlockRequirementEnabled && !isNfcScanned) {
                                button.text = getString(R.string.warning_scan_nfc_tag)
                                setPrimaryUnlockActionReady(true)
                            } else {
                                button.setText(R.string.proceed)
                                setPrimaryUnlockActionReady(true)
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

        binding.warningMsg.text = warningScreenConfig.message.random()

        if (warningScreenConfig.isOnOpenConfig) {
            binding.minsPicker.visibility = View.GONE
        } else {
            binding.minsPicker.setValue(
                (warningScreenConfig.timeInterval / 60_000L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
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
            if (isFocusGoalRequired &&
                !isFocusGoalVerified
            ) {
                return@setOnClickListener
            }

            if (warningScreenConfig.isAdaptiveMathRequirementEnabled &&
                !isAdaptiveMathComplete
            ) {
                submitAdaptiveMathAnswer()
                return@setOnClickListener
            }

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

            if (warningScreenConfig.isNfcUnlockRequirementEnabled && !isNfcScanned) {
                startNfcUnlockScan(warningScreenConfig)
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
                        val finalTime = if ((warningScreenConfig.isQrUnlockRequirementEnabled || warningScreenConfig.isNfcUnlockRequirementEnabled) && scannedValidDuration != -1L) {
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
                        val hasUnlockChallenge = warningScreenConfig.isQrUnlockRequirementEnabled ||
                            warningScreenConfig.isNfcUnlockRequirementEnabled
                        val finalTime = when {
                            warningScreenConfig.isOnOpenConfig -> 1440
                            hasUnlockChallenge && scannedValidDuration != -1L -> (scannedValidDuration / 60000).toInt()
                            hasUnlockChallenge -> binding.minsPicker.getValue()
                            else -> binding.minsPicker.getValue()
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
                        val finalTime = if ((warningScreenConfig.isQrUnlockRequirementEnabled || warningScreenConfig.isNfcUnlockRequirementEnabled) && scannedValidDuration != -1L) {
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

    private fun setupAdaptiveMathChallenge(questionCount: Int, startingLevel: Int) {
        val challenge = AdaptiveMathChallenge(
            requiredAnswers = questionCount.coerceIn(
                MIN_ADAPTIVE_MATH_QUESTIONS,
                MAX_ADAPTIVE_MATH_QUESTIONS
            ),
            startingDifficulty = startingLevel.coerceIn(
                MIN_ADAPTIVE_MATH_LEVEL,
                MAX_ADAPTIVE_MATH_LEVEL
            )
        )
        adaptiveMathChallenge = challenge
        binding.mathProgress.visibility = View.VISIBLE
        binding.mathProblem.visibility = View.VISIBLE
        binding.mathInputLayout.visibility = View.VISIBLE
        setPrimaryUnlockActionReady(true)
        binding.btnProceed.setText(R.string.warning_math_check_answer)
        binding.mathInputEdit.doAfterTextChanged {
            binding.mathInputLayout.error = null
        }
        binding.mathInputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnProceed.performClick()
                true
            } else {
                false
            }
        }
        showAdaptiveMathProblem(challenge)
    }

    private fun submitAdaptiveMathAnswer() {
        val challenge = adaptiveMathChallenge ?: return
        val answer = binding.mathInputEdit.text
            ?.toString()
            ?.trim()
            ?.replace(',', '.')
            ?.toBigDecimalOrNull()
        if (answer == null) {
            binding.mathInputLayout.error = getString(R.string.warning_math_answer_required)
            return
        }

        val result = challenge.submit(answer)
        if (result.isComplete) {
            isAdaptiveMathComplete = true
            binding.mathProblem.visibility = View.GONE
            binding.mathInputLayout.visibility = View.GONE
            binding.mathProgress.text = getString(
                R.string.warning_math_progress,
                result.solvedCount,
                challenge.requiredAnswers
            )
            binding.btnProceed.setText(R.string.proceed)
            return
        }

        binding.mathInputEdit.text?.clear()
        if (!result.wasCorrect) {
            binding.mathInputLayout.error = getString(R.string.warning_math_incorrect)
        }
        showAdaptiveMathProblem(challenge)
    }

    private fun showAdaptiveMathProblem(challenge: AdaptiveMathChallenge) {
        binding.mathProgress.text = getString(
            R.string.warning_math_progress,
            challenge.solvedCount,
            challenge.requiredAnswers
        )
        binding.mathProblem.text = challenge.currentProblem.expression
    }

    private fun setPrimaryUnlockActionReady(isReady: Boolean) {
        isPrimaryUnlockActionReady = isReady
        binding.btnProceed.isEnabled = isReady &&
            (!isFocusGoalRequired || isFocusGoalVerified)
    }

    private fun setupFocusGoalRequirement(config: AppBlockerWarningScreenConfig) {
        binding.focusGoalStatus.visibility = View.VISIBLE
        binding.focusGoalStatus.setText(R.string.warning_focus_goal_checking)
        setPrimaryUnlockActionReady(isPrimaryUnlockActionReady)

        lifecycleScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dayStartCalendar = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayStart = dayStartCalendar.timeInMillis
                val dayEnd = (dayStartCalendar.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis

                val (groupName, sessions) = withContext(Dispatchers.IO) {
                    val settings = DataStoreManager(applicationContext).settings.first()
                    val group = settings.manualFocusGroups.firstOrNull {
                        it.groupId == config.focusGoalGroupId
                    }
                    val matchingSessions = if (group == null) {
                        emptyList()
                    } else {
                        AppDatabase.getInstance(applicationContext)
                            .focusStatsDao()
                            .getSessionsOverlappingDay(group.groupId, dayStart, dayEnd)
                    }
                    group?.groupName to matchingSessions
                }

                if (groupName == null) {
                    restrictFocusGoal(getString(R.string.warning_focus_goal_unavailable))
                    return@launch
                }

                val focusedDuration = FocusGoalProgress.durationWithinDay(
                    sessions = sessions,
                    dayStart = dayStart,
                    dayEnd = dayEnd,
                    now = now
                )
                val requiredDuration = config.focusGoalRequiredMinutes
                    .coerceIn(MIN_FOCUS_GOAL_MINUTES, MAX_FOCUS_GOAL_MINUTES) * 60_000L
                if (focusedDuration >= requiredDuration) {
                    isFocusGoalVerified = true
                    binding.focusGoalStatus.text = getString(
                        R.string.warning_focus_goal_complete,
                        groupName
                    )
                    setPrimaryUnlockActionReady(isPrimaryUnlockActionReady)
                } else {
                    val remainingMinutes =
                        (requiredDuration - focusedDuration + 59_999L) / 60_000L
                    restrictFocusGoal(
                        getString(
                            R.string.warning_focus_goal_not_met,
                            remainingMinutes,
                            groupName
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restrictFocusGoal(getString(R.string.warning_focus_goal_check_failed))
            }
        }
    }

    private fun restrictFocusGoal(message: String) {
        binding.focusGoalStatus.text = message
        binding.btnProceed.visibility = View.GONE
        binding.btnCancel.setText(R.string.okay)
    }

    private fun startNfcUnlockScan(warningScreenConfig: AppBlockerWarningScreenConfig) {
        if (!NfcUnlockUtils.isNfcReady(this)) {
            Toast.makeText(this, R.string.nfc_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        val enabled = NfcUnlockUtils.enableReader(this) { tag ->
            NfcUnlockUtils.feedback(this)
            val match = NfcUnlockUtils.keysFromTag(tag)
                .firstOrNull { warningScreenConfig.nfcKeys.containsKey(it) }
            if (match != null) {
                isNfcScanned = true
                scannedValidDuration = warningScreenConfig.nfcKeys[match] ?: -1L
                setPrimaryUnlockActionReady(true)
                binding.btnProceed.setText(R.string.proceed)
                binding.minsPicker.visibility =
                    if (scannedValidDuration == -1L &&
                        !warningScreenConfig.isOnOpenConfig
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                stopNfcUnlockScan()
            } else {
                Toast.makeText(this, R.string.warning_invalid_nfc, Toast.LENGTH_LONG).show()
            }
        }
        if (!enabled) {
            Toast.makeText(this, R.string.nfc_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        nfcReaderActive = true

        nfcTapDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nfc_tap_title)
            .setMessage(R.string.nfc_tap_message)
            .setNegativeButton(R.string.cancel) { _, _ -> stopNfcUnlockScan() }
            .setOnDismissListener { stopNfcUnlockScan() }
            .show()
    }

    private fun stopNfcUnlockScan() {
        if (nfcReaderActive) {
            NfcUnlockUtils.disableReader(this)
            nfcReaderActive = false
        }
        nfcTapDialog?.dismiss()
        nfcTapDialog = null
    }

    override fun onPause() {
        super.onPause()
        stopNfcUnlockScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNfcUnlockScan()
        proceedTimer?.cancel()
        vibrator?.cancel()
        dialog?.dismiss()
    }

    private fun sendRefreshRequest(id: String, action: String, time: Int) {
        val intent = Intent(action)
        intent.putExtra("result_id", id)
        intent.putExtra("selected_time", time * 60_000L)
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

    private companion object {
        const val MIN_ADAPTIVE_MATH_QUESTIONS = 1
        const val MAX_ADAPTIVE_MATH_QUESTIONS = 10
        const val MIN_ADAPTIVE_MATH_LEVEL = 1
        const val MAX_ADAPTIVE_MATH_LEVEL = 10
        const val MIN_FOCUS_GOAL_MINUTES = 15
        const val MAX_FOCUS_GOAL_MINUTES = 24 * 60
    }
}
