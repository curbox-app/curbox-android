package neth.iecal.curbox.utils

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class TimeTools {
    companion object {
        private val DAY_KEY_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault())

        fun dayKey(date: LocalDate): String = date.format(DAY_KEY_FORMATTER)

        fun convertToMinutesFromMidnight(hour: Int, minute: Int): Int {
            return (hour * 60) + minute
        }

        fun convertMinutesTo24Hour(minutes: Int): Pair<Int, Int> {
            return Pair(minutes / 60, minutes % 60)
        }

        fun getCurrentDate(): String {
            val currentDate = LocalDate.now()

            val formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
            return currentDate.format(formatter)
        }
        fun getPreviousDate(daysAgo:Long = 1): String {
            val previousDate = LocalDate.now().minusDays(daysAgo)

            val formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
            return previousDate.format(formatter)
        }


        fun getCurrentTime(): String {
            val currentTime = LocalTime.now()

            val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

            return currentTime.format(formatter)
        }

        fun shortenDate(dateString: String): String {
            val parts = dateString.split(" ")

            if (parts.size >= 2) {
                val day = parts[0]
                val month = parts[1].take(3)
                return "$day $month"
            }

            return dateString
        }

        fun formatDate(timestamp: Long): String {
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            return dateFormat.format(Date(timestamp))
        }



        fun formatTime(timeInMillis: Long, showSeconds: Boolean = true): String {
            val hours = timeInMillis / (1000 * 60 * 60)
            val minutes = (timeInMillis % (1000 * 60 * 60)) / (1000 * 60)
            val seconds = (timeInMillis % (1000 * 60)) / 1000

            return buildString {
                if (hours > 0) append("$hours hr")
                if (minutes > 0) append(" $minutes mins")
                if (showSeconds && seconds > 0) append(" $seconds secs")
            }.trim()
        }

        fun formatTimeInHHMM(millis: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(millis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

            return if (hours > 0) {
                // Format as HH:mm:ss if there are hours
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                // Format as mm:ss for shorter sessions
                String.format("%02d:%02d", minutes, seconds)
            }
        }
        fun formatTimeForWidget(timeInMillis: Long): String {
            val hours = timeInMillis / (1000 * 60 * 60)
            val minutes = (timeInMillis % (1000 * 60 * 60)) / (1000 * 60)

            return buildString {
                if (hours > 0) append("${hours}h ")
                if (minutes > 0L) append("${minutes}m")
                if (hours == 0L && minutes == 0L) append("<1m") // Handle case for less than 1 minute
            }.trim()
        }

        // Like formatTimeForWidget but keeps seconds when under an hour, so short
        // sessions (e.g. website visits) stay visible instead of collapsing to "1m".
        fun formatDuration(timeInMillis: Long): String {
            val hours = timeInMillis / (1000 * 60 * 60)
            val minutes = (timeInMillis % (1000 * 60 * 60)) / (1000 * 60)
            val seconds = (timeInMillis % (1000 * 60)) / 1000

            return buildString {
                if (hours > 0) append("${hours}h ")
                if (minutes > 0L) append("${minutes}m ")
                if (hours == 0L && seconds > 0L) append("${seconds}s")
            }.trim().ifEmpty { "0s" }
        }


    }
}