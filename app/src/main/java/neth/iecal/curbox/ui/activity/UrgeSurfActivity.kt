package neth.iecal.curbox.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.transition.Fade
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.CravingSessionEntity
import neth.iecal.curbox.databinding.ActivityUrgeSurfBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Guides the user through riding out a craving: rate it, think about it for a moment,
 * spend 3 minutes on a puzzle or exercise, then rate it again and see the change.
 */
class UrgeSurfActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"

        private const val STEP_RATE_BEFORE = 0
        private const val STEP_REFLECT = 1
        private const val STEP_CHOOSE = 2
        private const val STEP_PUZZLE = 3
        private const val STEP_MOVE = 4
        private const val STEP_RATE_AFTER = 5
        private const val STEP_RESULT = 6

        private const val REFLECT_MS = 20_000L
        private const val ACTIVITY_MS = 3 * 60_000L
        private const val TICK_MS = 100L

        private const val KEY_STEP = "step"
        private const val KEY_RATING_BEFORE = "rating_before"
        private const val KEY_RATING_AFTER = "rating_after"
        private const val KEY_ACTIVITY = "activity"
        private const val KEY_PHASE_ENDS_AT = "phase_ends_at"
        private const val KEY_PROMPT_INDEX = "prompt_index"
        private const val KEY_BOARD = "board"
        private const val KEY_SAVED = "saved"
    }

    private lateinit var binding: ActivityUrgeSurfBinding

    private var step = STEP_RATE_BEFORE
    private var ratingBefore = -1
    private var ratingAfter = -1
    private var activityType = ""
    /** [SystemClock.elapsedRealtime] when the timed part of the current step ends. */
    private var phaseEndsAt = 0L
    private var promptIndex = 0
    private var isSessionSaved = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            onTick()
            if (step == STEP_REFLECT || step == STEP_PUZZLE || step == STEP_MOVE) {
                ticker.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUrgeSurfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The move step is usually done with the phone set down.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prompts = resources.getStringArray(R.array.urge_surf_reflect_prompts)
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt(KEY_STEP, STEP_RATE_BEFORE)
            ratingBefore = savedInstanceState.getInt(KEY_RATING_BEFORE, -1)
            ratingAfter = savedInstanceState.getInt(KEY_RATING_AFTER, -1)
            activityType = savedInstanceState.getString(KEY_ACTIVITY, "")
            phaseEndsAt = savedInstanceState.getLong(KEY_PHASE_ENDS_AT, 0L)
            promptIndex = savedInstanceState.getInt(KEY_PROMPT_INDEX, 0)
            isSessionSaved = savedInstanceState.getBoolean(KEY_SAVED, false)
            binding.quietBlocks.restoreState(savedInstanceState.getIntArray(KEY_BOARD))
        } else {
            promptIndex = prompts.indices.random()
        }
        binding.tvReflectPrompt.text = prompts.getOrElse(promptIndex) { prompts.first() }

        setupRatingButtons()
        binding.btnReflectContinue.setOnClickListener { showStep(STEP_CHOOSE) }
        binding.cardPuzzle.setOnClickListener { startActivityPhase(CravingSessionEntity.ACTIVITY_PUZZLE) }
        binding.cardMove.setOnClickListener { startActivityPhase(CravingSessionEntity.ACTIVITY_MOVE) }
        binding.btnGoHome.setOnClickListener { goHome() }
        binding.btnClose.setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        showStep(step, animate = false)
    }

    override fun onResume() {
        super.onResume()
        restartTicker()
    }

    override fun onPause() {
        ticker.removeCallbacks(tickRunnable)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
        outState.putInt(KEY_RATING_BEFORE, ratingBefore)
        outState.putInt(KEY_RATING_AFTER, ratingAfter)
        outState.putString(KEY_ACTIVITY, activityType)
        outState.putLong(KEY_PHASE_ENDS_AT, phaseEndsAt)
        outState.putInt(KEY_PROMPT_INDEX, promptIndex)
        outState.putBoolean(KEY_SAVED, isSessionSaved)
        outState.putIntArray(KEY_BOARD, binding.quietBlocks.saveState())
    }

    private fun setupRatingButtons() {
        for (rating in 0..10) {
            val button = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = String.format(Locale.getDefault(), "%d", rating)
                contentDescription = getString(R.string.urge_surf_rating_description, rating)
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { onRated(rating) }
            }
            val size = (52 * resources.displayMetrics.density).roundToInt()
            val params = LinearLayout.LayoutParams(0, size, 1f).apply {
                val margin = (4 * resources.displayMetrics.density).roundToInt()
                setMargins(margin, 0, margin, 0)
            }
            val row = if (rating <= 5) binding.rowRatingLow else binding.rowRatingHigh
            row.addView(button, params)
        }
        // Six buttons on top and five below. Half spacers keep the lower row centered
        // with buttons the same width as the top row.
        binding.rowRatingHigh.addView(View(this), 0, LinearLayout.LayoutParams(0, 0, 0.5f))
        binding.rowRatingHigh.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.5f))
    }

    private fun onRated(rating: Int) {
        when (step) {
            STEP_RATE_BEFORE -> {
                ratingBefore = rating
                phaseEndsAt = SystemClock.elapsedRealtime() + REFLECT_MS
                showStep(STEP_REFLECT)
            }
            STEP_RATE_AFTER -> {
                ratingAfter = rating
                showStep(STEP_RESULT)
            }
        }
    }

    private fun startActivityPhase(type: String) {
        activityType = type
        phaseEndsAt = SystemClock.elapsedRealtime() + ACTIVITY_MS
        showStep(if (type == CravingSessionEntity.ACTIVITY_PUZZLE) STEP_PUZZLE else STEP_MOVE)
    }

    private fun showStep(newStep: Int, animate: Boolean = true) {
        step = newStep
        if (animate) TransitionManager.beginDelayedTransition(binding.root, Fade().setDuration(220))

        val isRating = step == STEP_RATE_BEFORE || step == STEP_RATE_AFTER
        binding.stepRate.visibility = if (isRating) View.VISIBLE else View.GONE
        binding.stepReflect.visibility = if (step == STEP_REFLECT) View.VISIBLE else View.GONE
        binding.stepChoose.visibility = if (step == STEP_CHOOSE) View.VISIBLE else View.GONE
        binding.stepPuzzle.visibility = if (step == STEP_PUZZLE) View.VISIBLE else View.GONE
        binding.stepMove.visibility = if (step == STEP_MOVE) View.VISIBLE else View.GONE
        binding.stepResult.visibility = if (step == STEP_RESULT) View.VISIBLE else View.GONE
        binding.btnClose.visibility = if (step == STEP_RESULT) View.GONE else View.VISIBLE
        binding.quietBlocks.isFrozen = step != STEP_PUZZLE

        if (step == STEP_RATE_BEFORE) {
            binding.tvRateTitle.setText(R.string.urge_surf_rate_before_title)
            binding.tvRateBody.setText(R.string.urge_surf_rate_before_body)
        } else if (step == STEP_RATE_AFTER) {
            binding.tvRateTitle.setText(R.string.urge_surf_rate_after_title)
            binding.tvRateBody.setText(R.string.urge_surf_rate_after_body)
        } else if (step == STEP_RESULT) {
            showResult()
        }
        restartTicker()
    }

    private fun restartTicker() {
        ticker.removeCallbacks(tickRunnable)
        ticker.post(tickRunnable)
    }

    private fun onTick() {
        val remaining = phaseEndsAt - SystemClock.elapsedRealtime()
        when (step) {
            STEP_REFLECT -> {
                val progress = 1f - remaining.coerceAtLeast(0L) / REFLECT_MS.toFloat()
                binding.reflectProgress.progress = (progress * binding.reflectProgress.max).roundToInt()
                binding.btnReflectContinue.isEnabled = remaining <= 0L
            }
            STEP_PUZZLE, STEP_MOVE -> {
                if (remaining <= 0L) {
                    if (step == STEP_MOVE) buzz()
                    showStep(STEP_RATE_AFTER)
                    return
                }
                val totalSeconds = (remaining + 999) / 1000
                val text = String.format(
                    Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60
                )
                if (step == STEP_PUZZLE) binding.tvPuzzleCountdown.text = text
                else binding.tvMoveCountdown.text = text
            }
        }
    }

    private fun showResult() {
        binding.tvResultScores.text = getString(R.string.urge_surf_result_scores, ratingBefore, ratingAfter)
        val drop = ratingBefore - ratingAfter
        binding.tvResultMessage.text = when {
            drop > 0 -> resources.getQuantityString(R.plurals.urge_surf_result_down, drop, drop)
            drop == 0 -> getString(R.string.urge_surf_result_same)
            else -> getString(R.string.urge_surf_result_up)
        }

        lifecycleScope.launch {
            try {
                val sessions = withContext(Dispatchers.IO) {
                    val dao = AppDatabase.getInstance(applicationContext).cravingSessionDao()
                    if (!isSessionSaved) {
                        dao.insert(
                            CravingSessionEntity(
                                timestamp = System.currentTimeMillis(),
                                packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "",
                                ratingBefore = ratingBefore,
                                ratingAfter = ratingAfter,
                                activity = activityType
                            )
                        )
                        isSessionSaved = true
                    }
                    dao.getAllSync()
                }
                showUsualDrop(sessions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(applicationContext).logNonFatalError(e)
            }
        }
    }

    private fun showUsualDrop(sessions: List<CravingSessionEntity>) {
        // A single result is not a pattern yet.
        if (sessions.size < 3) return
        val usualDrop = sessions.map { it.ratingBefore - it.ratingAfter }.average().roundToInt()
        if (usualDrop <= 0) return
        TransitionManager.beginDelayedTransition(binding.stepResult, Fade())
        binding.tvResultStats.text =
            resources.getQuantityString(R.plurals.urge_surf_result_usual_drop, usualDrop, usualDrop)
        binding.tvResultStats.visibility = View.VISIBLE
    }

    private fun buzz() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
            }
        } catch (_: Exception) {
        }
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(home)
        } catch (_: Exception) {
        }
        finish()
    }
}
