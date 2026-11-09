package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivitySipStretchBreakBinding
import neth.iecal.curbox.utils.DataStoreManager
import java.util.Locale

class SipStretchBreakActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BREAK_ENDS_AT_MS = "break_ends_at_ms"
    }

    private lateinit var binding: ActivitySipStretchBreakBinding
    private var breakEndsAtMs = 0L

    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val remainingMs = breakEndsAtMs - System.currentTimeMillis()
            if (remainingMs <= 0) {
                finish()
                return
            }
            val totalSeconds = (remainingMs + 999) / 1000
            binding.tvCountdown.text = String.format(
                Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60
            )
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySipStretchBreakBinding.inflate(layoutInflater)
        setContentView(binding.root)

        breakEndsAtMs = intent.getLongExtra(EXTRA_BREAK_ENDS_AT_MS, 0L)
        showRandomTip()

        binding.btnGoHome.setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        lifecycleScope.launch {
            try {
                DataStoreManager(applicationContext).settings.collect { settings ->
                    val endsAt = settings.sipStretchBreakEndsAtMs
                    if (!settings.sipStretchConfig.isActive || endsAt <= System.currentTimeMillis()) {
                        finish()
                    } else if (endsAt != breakEndsAtMs) {
                        breakEndsAtMs = endsAt
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(applicationContext).logNonFatalError(e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val endsAt = intent.getLongExtra(EXTRA_BREAK_ENDS_AT_MS, 0L)
        if (endsAt > 0L) breakEndsAtMs = endsAt
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tickRunnable)
        ticker.post(tickRunnable)
    }

    override fun onPause() {
        ticker.removeCallbacks(tickRunnable)
        super.onPause()
    }

    private fun showRandomTip() {
        val tips = resources.getStringArray(R.array.sip_stretch_tips)
        if (tips.isNotEmpty()) binding.tvTip.text = tips.random()
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
