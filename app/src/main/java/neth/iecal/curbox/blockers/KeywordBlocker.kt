package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.edit
import androidx.room.InvalidationTracker
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.KeywordMatcher
import neth.iecal.curbox.utils.TimeTools
import java.util.Calendar
import java.util.Locale

class KeywordBlocker : BaseBlocker() {
    companion object {
        const val INTENT_ACTION_REFRESH_CONFIG = "neth.iecal.curbox.refresh.keywordblocker.config"
        const val INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.keywordblocker.cooldown"
        private const val TARGET_EVENTS_MASK =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    }

    private lateinit var service: BaseBlockingService
    private lateinit var browserBlocker: BrowserBlocker
    private lateinit var prefs: SharedPreferences

    private var activeGroups = listOf<KeywordGroup>()
    // Maps group ID → (compiled regexes, lowercase literal keywords)
    private var groupPatternMap = mutableMapOf<String, Pair<List<Regex>, List<String>>>()

    private val detectionCache = LruCache<String, List<KeywordGroup>>(200)
    private var isTurnedOn = false
    private var isUnsupportedBrowserBlockingOn = false
    private var lastpkg = ""
    private var cooldownGroupsList = HashMap<String, Long>()
    private var observationJob: Job? = null

    fun compileKeywords(keywords: Collection<String>): Pair<List<Regex>, List<String>> =
        KeywordMatcher.compileKeywords(keywords)

    private fun matchesPatterns(patterns: Pair<List<Regex>, List<String>>, urlIdentifier: String): Boolean =
        KeywordMatcher.matchesPatterns(patterns, urlIdentifier)

    // Returns every active group whose keywords match, preserving activeGroups order so the
    // first group in the list wins the tie-break when more than one would block.
    private fun findMatchingGroups(urlIdentifier: String): List<KeywordGroup> {
        detectionCache.get(urlIdentifier)?.let { return it }   // empty list = cached "no match"

        val matches = activeGroups.filter { group ->
            groupPatternMap[group.id]?.let { matchesPatterns(it, urlIdentifier) } == true
        }
        detectionCache.put(urlIdentifier, matches)
        return matches
    }

    private fun matchesGroup(group: KeywordGroup, urlIdentifier: String): Boolean {
        val patterns = groupPatternMap[group.id] ?: return false
        return matchesPatterns(patterns, urlIdentifier)
    }

    // TODO: instead of this approach, add a datastore obj that automatcally setups up focus mode blocker in the regular observer
    fun isFocusWebsiteBlocked(
        packageName: String,
        compiledKeywords: Pair<List<Regex>, List<String>>,
        blockMode: FocusBlockMode
    ): Boolean {
        val date = TimeTools.getCurrentDate()
        val latest = runBlocking(Dispatchers.IO) {
            AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForPackage(date, packageName)
                .maxByOrNull { it.lastVisited }
        } ?: return false

        if (latest.lastVisited < System.currentTimeMillis() - 5000) return false
        val urlIdentifier = latest.urlIdentifier.ifEmpty { return false }

        if (blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED && isInternalBrowserPage(urlIdentifier)) return false

        val matched = matchesPatterns(compiledKeywords, urlIdentifier)
        return if (blockMode == FocusBlockMode.BLOCK_SELECTED) matched else !matched
    }

    private fun isInternalBrowserPage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("chrome://") || lower.startsWith("about:") ||
               lower.contains("newtab") || lower.contains("bookmarks") ||
               lower.contains("history") || lower.startsWith("search") ||
               lower.endsWith("url") || lower.contains("Search Google or type URL") ||
               !lower.contains('.') || lower.contains("null")
    }

    fun checkIfUnsupportedBrowser(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return
        if (lastpkg == packageName || (ev.eventType and TARGET_EVENTS_MASK) == 0) return
        lastpkg = packageName
        if (isUnsupportedBrowserBlockingOn && ::browserBlocker.isInitialized && browserBlocker.isAppBrowser(ev)) {
            if (!service.isDelayOver(1000)) return
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(service, service.getString(R.string.toast_unsupported_browser), Toast.LENGTH_LONG).show()
            }
            service.pressHome()
        }
    }

    private fun startObservingDatabase() {
        observationJob?.cancel()
        observationJob = CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(service)
            val dao = db.websiteStatsDao()
            callbackFlow {
                val observer = object : InvalidationTracker.Observer("website_stats") {
                    override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
                }
                db.invalidationTracker.addObserver(observer)
                awaitClose { db.invalidationTracker.removeObserver(observer) }
            }.collect {
                val date = TimeTools.getCurrentDate()
                val latest = dao.getStatsForDate(date).maxByOrNull { it.lastVisited }
                if (latest != null && latest.lastVisited > (System.currentTimeMillis() - 2500)) {
                    evaluateAndBlock(latest)
                }
            }
        }
    }

    private fun evaluateAndBlock(entry: WebsiteStatsEntity) {
        val matched = findMatchingGroups(entry.urlIdentifier)
        if (matched.isEmpty()) return
        val now = System.currentTimeMillis()

        // Block if any matched group demands it; the first group in list order wins its warning screen.
        // A group in cooldown is skipped so the others can still decide.
        for (group in matched) {
            val cooldownEnd = cooldownGroupsList[group.id]
            if (cooldownEnd != null) {
                if (cooldownEnd > now) continue
                else removeCooldownFrom(group.id)
            }
            if (isBlocked(group)) {
                handleBlocking(group)
                return
            }
        }

        // None blocked → schedule the soonest re-check across the matched groups
        var soonest = 0L
        for (group in matched) {
            val recheck = computeNextRecheck(group)
            if (recheck > now && (soonest == 0L || recheck < soonest)) soonest = recheck
        }
        if (soonest > now) {
            CoroutineScope(Dispatchers.IO).launch {
                service.dataStoreManager.updateNextWebsiteRecheckTime(soonest)
            }
        }
    }

    private fun handleBlocking(group: KeywordGroup) {
        service.pressBack()
        Thread.sleep(1000)
        service.pressHome()
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(service, WarningActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("mode", Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER)
                putExtra("result_id", group.id)
                putExtra("warning_config", Gson().toJson(group.warningScreenConfig))
            }
            service.startActivity(intent)
        }, 300)
    }

    private fun isBlocked(group: KeywordGroup): Boolean =
        if (group.blockingType == AppBlockingType.Timed) isTimedBlockActive(group)
        else isUsageLimitExceeded(group)

    // Intervals describe the ALLOWED time. Keywords are blocked whenever the
    // current time falls outside every allowed interval (matching the app blocker).
    private fun isTimedBlockActive(group: KeywordGroup): Boolean {
        val config = Gson().fromJson(group.setting, AppTimeConfig::class.java) ?: return false
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(
            calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
        )
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val intervals = if (config.isEveryday) config.everydayIntervals
                        else config.dailyIntervals[dayOfWeek] ?: emptyList()

        for (interval in intervals) {
            val start = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val end = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)
            val withinAllowed = if (start <= end) currentMinutes in start until end
                                else currentMinutes >= start || currentMinutes < end
            if (withinAllowed) return false
        }
        return true
    }

    private fun isUsageLimitExceeded(group: KeywordGroup): Boolean {
        val config = Gson().fromJson(group.setting, AppUsageConfig::class.java) ?: return false
        val limit = (if (config.isDailyUniform) config.uniformLimit else {
            config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        }) * 60_000L

        if (limit <= 0) return true

        return groupUsage(group) >= limit
    }

    // Combined usage of every keyword in the group across all browsers, so the
    // limit applies to the group as a whole rather than each browser separately.
    private fun groupUsage(group: KeywordGroup): Long {
        val date = TimeTools.getCurrentDate()
        return runBlocking(Dispatchers.IO) {
            AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForDate(date)
                .filter { matchesGroup(group, it.urlIdentifier) }
                .sumOf { it.totalTime }
        }
    }

    // Returns when this group should next be re-checked (0 if no re-check is needed). The caller is
    // responsible for persisting the soonest value across all matched groups.
    private fun computeNextRecheck(group: KeywordGroup): Long {
        val now = System.currentTimeMillis()
        var nextRecheck = 0L

        if (group.blockingType == AppBlockingType.Usage) {
            val config = Gson().fromJson(group.setting, AppUsageConfig::class.java)
            if (config != null) {
                val limit = (if (config.isDailyUniform) config.uniformLimit else {
                    config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
                }) * 60_000L
                if (limit > 0) {
                    val remaining = limit - groupUsage(group)
                    if (remaining > 0) nextRecheck = now + remaining + 1000
                }
            }
        }

        if (group.blockingType == AppBlockingType.Timed) {
            val config = Gson().fromJson(group.setting, AppTimeConfig::class.java)
            if (config != null) {
                val calendar = Calendar.getInstance()
                val currentMinutes = TimeTools.convertToMinutesFromMidnight(
                    calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
                )
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
                val intervals = if (config.isEveryday) config.everydayIntervals
                                else config.dailyIntervals[dayOfWeek] ?: emptyList()

                // We are inside an allowed window; re-check when it ends so the block kicks in.
                var minMinutesUntilEnd = Int.MAX_VALUE
                for (interval in intervals) {
                    val start = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
                    val end = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)
                    val withinAllowed = if (start <= end) currentMinutes in start until end
                                        else currentMinutes >= start || currentMinutes < end
                    if (withinAllowed) {
                        val minutesUntilEnd = if (start <= end || currentMinutes < end) end - currentMinutes
                                              else (1440 - currentMinutes) + end
                        minMinutesUntilEnd = minOf(minMinutesUntilEnd, minutesUntilEnd)
                    }
                }
                if (minMinutesUntilEnd != Int.MAX_VALUE) {
                    val recheckAt = now + (minMinutesUntilEnd * 60_000L) -
                        (calendar.get(Calendar.SECOND) * 1000L) - calendar.get(Calendar.MILLISECOND)
                    if (nextRecheck == 0L || recheckAt < nextRecheck) nextRecheck = recheckAt
                }
            }
        }

        val cooldownEnd = cooldownGroupsList[group.id]
        if (cooldownEnd != null && cooldownEnd > now) {
            if (nextRecheck == 0L || cooldownEnd < nextRecheck) nextRecheck = cooldownEnd + 500
        }

        return nextRecheck
    }

    private var configJob: Job? = null

    fun setupBlocker(service: BaseBlockingService, watchSettings: Boolean = true) {
        this.service = service
        this.browserBlocker = BrowserBlocker(service)
        this.prefs = service.getSharedPreferences("keyword_blocker_prefs", Context.MODE_PRIVATE)
        loadPersistedData()

        if (!watchSettings) return

        configJob?.cancel()
        configJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                isTurnedOn = settings.keywordBlockerConfig.isActive
                isUnsupportedBrowserBlockingOn = settings.keywordBlockerConfig.blockAllExceptSupported
                browserBlocker.isTurnedOn = isTurnedOn

                activeGroups = if (isTurnedOn) {
                    settings.keywordBlockerConfig.keywordGroups.filter { it.isActive }
                } else emptyList()

                groupPatternMap = activeGroups.associate { group ->
                    group.id to compileKeywords(group.selectedKeywords)
                }.toMutableMap()

                detectionCache.evictAll()

                if (isTurnedOn) startObservingDatabase() else observationJob?.cancel()
            }
        }
    }

    private fun loadPersistedData() {
        val keys = prefs.getStringSet("cooldown_keys", setOf()) ?: setOf()
        keys.forEach { id ->
            val end = prefs.getLong("cooldown_$id", 0L)
            if (end > System.currentTimeMillis()) cooldownGroupsList[id] = end
        }
    }

    private fun persistCooldownData() {
        prefs.edit {
            putStringSet("cooldown_keys", cooldownGroupsList.keys)
            cooldownGroupsList.forEach { (id, end) -> putLong("cooldown_$id", end) }
        }
    }

    private fun removeCooldownFrom(id: String) {
        cooldownGroupsList.remove(id)
        prefs.edit {
            remove("cooldown_$id")
            putStringSet("cooldown_keys", cooldownGroupsList.keys)
        }
    }

    private fun handleCooldownIntent(intent: Intent) {
        val groupId = intent.getStringExtra("result_id") ?: return
        val duration = intent.getIntExtra("selected_time", 120000)
        cooldownGroupsList[groupId] = System.currentTimeMillis() + duration
        persistCooldownData()

        val date = TimeTools.getCurrentDate()
        CoroutineScope(Dispatchers.IO).launch {
            val latest = AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForDate(date).maxByOrNull { it.lastVisited }
            if (latest != null && latest.lastVisited > (System.currentTimeMillis() - 5000)) {
                evaluateAndBlock(latest)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_CONFIG)
            addAction(INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun removeReceivers() {
        service.unregisterReceiver(refreshReceiver)
        observationJob?.cancel()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_ACTION_REFRESH_CONFIG -> setupBlocker(service)
                INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN -> handleCooldownIntent(intent)
            }
        }
    }
}
