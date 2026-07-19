package neth.iecal.curbox.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*


class TimerNotification(
    private val context: Context,
    private val notificationId: Int = 1001
) {

    enum class TimerState { IDLE, RUNNING, FINISHED }

    private val _timerState = MutableStateFlow(TimerState.IDLE)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    companion object {
        private const val TAG = "NotificationTimerMgr"
        private const val CHANNEL_ID = "TimerNotificationChannel"
    }

    private val notificationManager: NotificationManager by lazy {
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(context.applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Swap for your actual app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    private val timerDispatcher = Dispatchers.Default
    private val scope = CoroutineScope(SupervisorJob() + timerDispatcher)
    private var timerJob: Job? = null

    private var currentTimerId = ""

    init {
        createNotificationChannel()
    }

    fun startTimer(
        totalMillis: Long,
        isCountdown: Boolean = true,
        title: String = "Timer",
        timerId: String = "focusMode",
        onTickCallback: ((Long) -> Unit)? = null,
        onFinishCallback: (() -> Unit)? = null,
    ) {
        require(totalMillis > 0) { "totalMillis must be > 0" }

        // Prevent duplicate timers, but ONLY if the ID is the same AND it's actively running
        if (currentTimerId == timerId && timerJob?.isActive == true) {
            return
        }

        stopTimer() // Cleans up any old state safely

        currentTimerId = timerId
        _timerState.value = TimerState.RUNNING

        // Setup the cached notification UI title
        notificationBuilder.setContentTitle(title)

        timerJob = scope.launch {
            try {
                runTimer(totalMillis, isCountdown, onTickCallback)

                // If it finishes naturally (not cancelled):
                _timerState.value = TimerState.FINISHED
                _elapsedMillis.value = if (isCountdown) 0L else totalMillis

                withContext(Dispatchers.Main) {
                    onFinishCallback?.invoke()
                }

            } catch (e: CancellationException) {
                // Normal cancellation via stopTimer() — ignore
            } catch (e: Exception) {
                Log.e(TAG, "Timer '$timerId' crashed", e)
            } finally {
                if (currentTimerId == timerId) {
                    currentTimerId = ""
                    dismissNotification()
                }
            }
        }
    }

    fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        currentTimerId = ""
        dismissNotification()
        _timerState.value = TimerState.IDLE
        _elapsedMillis.value = 0L
    }

    fun release() {
        stopTimer()
        scope.cancel()
    }

    private suspend fun runTimer(
        totalMillis: Long,
        isCountdown: Boolean,
        onTickCallback: ((Long) -> Unit)?
    ) {
        val startTimeRealtime = SystemClock.elapsedRealtime()
        val endTimeRealtime = startTimeRealtime + totalMillis

        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val remainingMillis = endTimeRealtime - now

            if (remainingMillis <= 0) break // Timer finished!

            val displayMillis = if (isCountdown) remainingMillis else (totalMillis - remainingMillis)
            _elapsedMillis.value = displayMillis

            updateNotificationUI(displayMillis)

            onTickCallback?.let { cb ->
                withContext(Dispatchers.Main) { cb(displayMillis) }
            }

            val sleepTime = remainingMillis % 1000
            delay(if (sleepTime > 0) sleepTime else 1000L)
        }
    }

    private fun updateNotificationUI(remainingMillis: Long) {
        runCatching {
            val hours = remainingMillis / 3_600_000
            val minutes = (remainingMillis % 3_600_000) / 60_000
            val seconds = (remainingMillis % 60_000) / 1_000

            val timeString = if (hours > 0) {
                "%02d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }

            val notification = notificationBuilder
                .setContentText(timeString)
                .setContentInfo(timeString)
                .build()

            notificationManager.notify(notificationId, notification)
        }.onFailure { exception ->
            Log.e(TAG, "Failed to show notification: ${exception.message}", exception)
        }
    }

    private fun dismissNotification() {
        runCatching { notificationManager.cancel(notificationId) }
    }

    private fun createNotificationChannel() {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Blocker Timers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active countdowns for blocked apps"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
