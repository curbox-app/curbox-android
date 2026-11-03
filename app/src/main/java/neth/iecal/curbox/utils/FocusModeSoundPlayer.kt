package neth.iecal.curbox.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R

class FocusModeSoundPlayer(context: Context) {

    enum class Effect(val soundResId: Int) {
        FOCUS_COMPLETE(R.raw.focus_complete),
        BREAK_STARTED(R.raw.break_started),
        FOCUS_RESUMED(R.raw.focus_resumed),
        POMODORO_COMPLETE(R.raw.pomodoro_complete)
    }

    private val appContext = context.applicationContext
    private val crashLogger = CrashLogger(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null

    // Alarm usage so the chime is still heard while focus mode keeps Do Not Disturb on.
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun play(effect: Effect) {
        scope.launch {
            try {
                stopCurrent()
                val player = MediaPlayer.create(
                    appContext,
                    effect.soundResId,
                    audioAttributes,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                ) ?: return@launch
                player.setOnCompletionListener {
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                }
                mediaPlayer = player
                player.start()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to play focus mode sound", t)
                crashLogger.logNonFatalError(Exception(t))
            }
        }
    }

    fun release() {
        scope.cancel()
        stopCurrent()
    }

    private fun stopCurrent() {
        mediaPlayer?.let { runCatching { it.release() } }
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "FocusModeSound"
    }
}
