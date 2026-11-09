package neth.iecal.curbox.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.Constants
import neth.iecal.curbox.nfc.NfcUnlockUtils
import neth.iecal.curbox.R
import neth.iecal.curbox.api.ApiAuthStore
import neth.iecal.curbox.api.ApiRestrictionPolicy
import neth.iecal.curbox.api.ExternalUnlockAttempt
import neth.iecal.curbox.api.ExternalUnlockProviders
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.models.ExternalUnlockCondition
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
import neth.iecal.curbox.data.models.UrgeSurfConfig

class WarningActivity : AppCompatActivity() {

    private val externalAttempt = ExternalUnlockAttempt()
    private var externalWarningConfig: AppBlockerWarningScreenConfig? = null
    private var externalApproval = false
    private var externalCheckInProgress = false
    private var externalCondition: ExternalUnlockCondition? = null
    private val externalLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val condition = externalCondition
        val revision = ApiAuthStore.revision(this)
        val valid = externalAttempt.consume(
            runCatching { result.data?.getStringExtra(ExternalUnlockProviders.EXTRA_REQUEST_ID) }.getOrNull(),
            result.resultCode == RESULT_OK,
            SystemClock.elapsedRealtime(),
            revision
        )
        lifecycleScope.launch {
            try {
                if (valid && condition != null &&
                    externalWarningStillCurrent(condition) &&
                    revision == ApiAuthStore.revision(this@WarningActivity) &&
                    ExternalUnlockProviders.isAvailable(this@WarningActivity, condition)) {
                    externalApproval = true
                    if (binding.btnProceed.isEnabled) binding.btnProceed.performClick()
                    externalApproval = false
                } else {
                    Toast.makeText(this@WarningActivity, R.string.warning_external_denied, Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(applicationContext).logNonFatalError(e)
                Toast.makeText(this@WarningActivity, R.string.warning_external_unavailable, Toast.LENGTH_LONG).show()
            } finally {
                externalCheckInProgress = false
                externalApproval = false
            }
        }
    }

    private suspend fun externalWarningStillCurrent(condition: ExternalUnlockCondition): Boolean {
        if (!ExternalUnlockProviders.isRegistered(this, condition)) return false
        val settings = DataStoreManager(this).settings.first()
        val target = intent.getStringExtra("result_id") ?: return false
        val expected = externalWarningConfig ?: return false
        return ApiRestrictionPolicy.warningStillCurrent(settings, intent.getIntExtra("mode", 0), target, expected)
    }

    private fun checkExternalRequirement(condition: ExternalUnlockCondition) {
        if (externalCheckInProgress) return
        externalCheckInProgress = true
        lifecycleScope.launch {
            try {
                if (!externalWarningStillCurrent(condition)) {
                    externalCheckInProgress = false
                    Toast.makeText(this@WarningActivity, R.string.warning_external_unavailable, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val requestId = java.util.UUID.randomUUID().toString()
                externalAttempt.start(requestId, SystemClock.elapsedRealtime(), ApiAuthStore.revision(this@WarningActivity))
                externalLauncher.launch(ExternalUnlockProviders.checkIntent(condition, requestId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                externalAttempt.clear()
                externalCheckInProgress = false
                CrashLogger(applicationContext).logNonFatalError(e)
                Toast.makeText(this@WarningActivity, R.string.warning_external_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

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
    private var mathRetryTimer: CountDownTimer? = null
    private var mathRetryEndsAtMs = 0L
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
        mathRetryEndsAtMs = savedInstanceState?.getLong(KEY_MATH_RETRY_ENDS_AT_MS) ?: 0L
        sendWarningScreenVisibility(true)

        val mode = intent.getIntExtra("mode", 0)

        val warningScreenConfig = Gson().fromJson<AppBlockerWarningScreenConfig>(
            intent.getStringExtra("warning_config"),
            AppBlockerWarningScreenConfig::class.java
        )

        externalCondition = warningScreenConfig.externalUnlockCondition
        externalWarningConfig = warningScreenConfig
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
                            } else if (warningScreenConfig.externalUnlockCondition != null) {
                                button.setText(R.string.warning_external_check)
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
                resetUnlockStreak()
                finishAffinity()
            }
            .show()

        binding.warningMsg.text = warningScreenConfig.message.random()

        setupUrgeSurf(mode)

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
            resetUnlockStreak()
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

            warningScreenConfig.externalUnlockCondition?.let { condition ->
                if (!externalApproval) {
                    checkExternalRequirement(condition)
                    return@setOnClickListener
                }
                externalApproval = false
            }

            if (warningScreenConfig.proceedLimitEnabled && targetId.isNotEmpty()) {
                val limitPrefs = getSharedPreferences("proceed_limits", Context.MODE_PRIVATE)
                val historyString = limitPrefs.getString("proceeds_$targetId", "") ?: ""
                val history = historyString.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
                val nowTime = System.currentTimeMillis()
                val windowMillis = warningScreenConfig.proceedsTimeWindowMn * 60_000L
                val validHistory = history.filter { nowTime - it < windowMillis }.toMutableList()
                
                if (validHistory.size >= warningScreenConfig.allowedProceeds) {
                    binding.btnProceed.isEnabled = false
                    return@setOnClickListener
                }
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

            recordUnlock()

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
                        binding.btnProceed.isEnabled = false
                        sendRefreshRequest(
                            it1,
                            AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN,
                            finalTime,
                            onDelivered = {
                                try {
                                    val launchPackage = intent.getStringExtra("launch_package") ?: it1
                                    packageManager.getLaunchIntentForPackage(launchPackage)
                                        ?.let(::startActivity)
                                } finally {
                                    closeWarningScreen()
                                }
                            }
                        )
                        return@setOnClickListener
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

            closeWarningScreen()
        }
    }

    /** Unlocks in a row without the user backing out. Strong cravings show up as long streaks. */
    private fun unlockStreak(): Int =
        getSharedPreferences(URGE_SURF_PREFS, Context.MODE_PRIVATE).getInt(KEY_UNLOCK_STREAK, 0)

    private fun recordUnlock() {
        getSharedPreferences(URGE_SURF_PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_UNLOCK_STREAK, unlockStreak() + 1)
        }
    }

    private fun resetUnlockStreak() {
        getSharedPreferences(URGE_SURF_PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_UNLOCK_STREAK, 0)
        }
    }

    private fun setupUrgeSurf(mode: Int) {
        binding.btnStruggling.setOnClickListener {
            resetUnlockStreak()
            val blockedPackage = if (mode == Constants.WARNING_SCREEN_MODE_APP_BLOCKER) {
                intent.getStringExtra("launch_package") ?: ""
            } else {
                ""
            }
            try {
                // Leave the blocked app first so it is not waiting behind the exercise.
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                startActivity(
                    Intent(this, UrgeSurfActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(UrgeSurfActivity.EXTRA_PACKAGE_NAME, blockedPackage)
                )
            } catch (e: Exception) {
                CrashLogger(applicationContext).logNonFatalError(e)
            }
            sendWarningScreenVisibility(false)
            dialog?.dismiss()
            finish()
        }

        lifecycleScope.launch {
            try {
                val config = DataStoreManager(applicationContext).settings.first().urgeSurfConfig
                val isRepeated = config.isAutoOfferEnabled &&
                    unlockStreak() >= UrgeSurfConfig.AUTO_OFFER_UNLOCK_COUNT
                if (!config.isButtonEnabled && !isRepeated) return@launch
                TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
                binding.urgeSurfOffer.visibility = if (isRepeated) View.VISIBLE else View.GONE
                binding.btnStruggling.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(applicationContext).logNonFatalError(e)
            }
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
        if (isMathRetryWaiting()) startMathRetryCountdown()
    }

    private fun submitAdaptiveMathAnswer() {
        if (isMathRetryWaiting()) return
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
            mathRetryEndsAtMs = SystemClock.elapsedRealtime() + MATH_RETRY_DELAY_MS
            startMathRetryCountdown()
        }
        showAdaptiveMathProblem(challenge)
    }

    private fun isMathRetryWaiting(): Boolean = SystemClock.elapsedRealtime() < mathRetryEndsAtMs

    private fun startMathRetryCountdown() {
        mathRetryTimer?.cancel()
        val remainingMs = (mathRetryEndsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        setPrimaryUnlockActionReady(false)
        showMathRetryRemaining(remainingMs)
        mathRetryTimer = object : CountDownTimer(remainingMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                showMathRetryRemaining(millisUntilFinished)
            }

            override fun onFinish() {
                mathRetryEndsAtMs = 0L
                mathRetryTimer = null
                binding.btnProceed.setText(R.string.warning_math_check_answer)
                setPrimaryUnlockActionReady(true)
            }
        }.start()
    }

    private fun showMathRetryRemaining(remainingMs: Long) {
        val seconds = ((remainingMs + 999L) / 1000L).toInt()
        binding.btnProceed.text = resources.getQuantityString(
            R.plurals.warning_math_retry_in,
            seconds,
            seconds
        )
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
            !isMathRetryWaiting() &&
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_MATH_RETRY_ENDS_AT_MS, mathRetryEndsAtMs)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        externalAttempt.clear()
        sendWarningScreenVisibility(false)
        super.onDestroy()
        stopNfcUnlockScan()
        proceedTimer?.cancel()
        mathRetryTimer?.cancel()
        vibrator?.cancel()
        dialog?.dismiss()
    }

    private fun sendRefreshRequest(
        id: String,
        action: String,
        time: Int,
        onDelivered: (() -> Unit)? = null
    ) {
        val intent = Intent(action).setPackage(packageName)
        intent.putExtra("result_id", id)
        intent.putExtra("selected_time", time * 60_000L)
        if (onDelivered == null) {
            sendBroadcast(intent)
            return
        }

        sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    onDelivered()
                }
            },
            null,
            RESULT_OK,
            null,
            null
        )
    }

    private fun closeWarningScreen() {
        sendWarningScreenVisibility(false)
        dialog?.dismiss()
        finishAffinity()
    }

    private fun sendWarningScreenVisibility(isVisible: Boolean) {
        sendBroadcast(
            Intent(AppBlocker.INTENT_ACTION_WARNING_SCREEN_VISIBILITY)
                .setPackage(packageName)
                .putExtra(AppBlocker.EXTRA_WARNING_SCREEN_VISIBLE, isVisible)
        )
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
        const val KEY_MATH_RETRY_ENDS_AT_MS = "math_retry_ends_at_ms"
        const val URGE_SURF_PREFS = "urge_surf"
        const val KEY_UNLOCK_STREAK = "unlock_streak"
        const val MATH_RETRY_DELAY_MS = 15_000L
        const val MIN_ADAPTIVE_MATH_QUESTIONS = 1
        const val MAX_ADAPTIVE_MATH_QUESTIONS = 10
        const val MIN_ADAPTIVE_MATH_LEVEL = 1
        const val MAX_ADAPTIVE_MATH_LEVEL = 10
        const val MIN_FOCUS_GOAL_MINUTES = 15
        const val MAX_FOCUS_GOAL_MINUTES = 24 * 60
    }
}
