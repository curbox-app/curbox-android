package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.room.InvalidationTracker
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
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
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.upgradeLegacyKeywordGroupConfigs
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AccessibilityHelper
import neth.iecal.curbox.utils.ActiveTimeGroupWindow
import neth.iecal.curbox.utils.KeywordMatcher
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.WebsiteUsageWindow
import neth.iecal.curbox.utils.activeWindow
import neth.iecal.curbox.utils.nextChangeAfter
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class KeywordBlocker : BaseBlocker() {
    companion object {
        const val INTENT_ACTION_REFRESH_CONFIG = "neth.iecal.curbox.refresh.keywordblocker.config"
        const val INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.keywordblocker.cooldown"
        private const val TARGET_EVENTS_MASK =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        private const val BLOCK_SUPPRESSION_MS = 5_000L
        private const val REPLACEMENT_PAGE = "about:blank"
        private const val COOLDOWN_NOTIFICATION_ID = 1004
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
    private var cooldownGroupsList = ConcurrentHashMap<String, Long>()
    private lateinit var notificationManager: TimerNotification
    private var notifiedCooldownGroupId: String? = null
    private var observationJob: Job? = null
    private val observationGuard = Any()
    private var lastObservedSnapshot: WebsiteStatsEntity? = null
    private val blockGuard = Any()
    private var lastBlockedTarget = ""
    private var blockSuppressedUntil = 0L
    @Volatile private var lastWebsiteObservation: WebsiteObservation? = null

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

    // TODO: instead of this approach, add a datastore obj that automatically setups up focus mode blocker in the regular observer
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
        if (ev.packageName == null) return
        if ((ev.eventType and TARGET_EVENTS_MASK) == 0) return
        if (isUnsupportedBrowserBlockingOn && ::browserBlocker.isInitialized && browserBlocker.isAppBrowser(ev)) {
            if (!service.isDelayOver(1000)) return
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(service, service.getString(R.string.toast_unsupported_browser), Toast.LENGTH_LONG).show()
            }
            service.pressHome()
        }
    }

    private fun startObservingDatabase() {
        if (observationJob?.isActive == true) return

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
                if (latest != null &&
                    latest.lastVisited > (System.currentTimeMillis() - 2500) &&
                    isShowingNow(latest) &&
                    markSnapshotAsObserved(latest)
                ) {
                    evaluateAndBlock(latest.packageName, latest.urlIdentifier)
                    Log.d("KeywordBlocker", "Evaluated $latest")
                }
            }
        }
    }

    // Saved rows can outlive their page, so only act on one while it is on screen.
    private fun isShowingNow(snapshot: WebsiteStatsEntity): Boolean {
        val observation = lastWebsiteObservation ?: return false
        if (observation.packageName != snapshot.packageName ||
            observation.urlIdentifier != snapshot.urlIdentifier
        ) return false
        val foreground = runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        return foreground == snapshot.packageName
    }

    private fun markSnapshotAsObserved(snapshot: WebsiteStatsEntity): Boolean =
        synchronized(observationGuard) {
            val previous = lastObservedSnapshot
            if (previous != null &&
                previous.date == snapshot.date &&
                previous.packageName == snapshot.packageName &&
                previous.urlIdentifier == snapshot.urlIdentifier &&
                previous.domain == snapshot.domain &&
                previous.totalTime == snapshot.totalTime &&
                previous.lastVisited == snapshot.lastVisited &&
                previous.hourlyUsage.contentEquals(snapshot.hourlyUsage)
            ) {
                false
            } else {
                lastObservedSnapshot = snapshot
                true
            }
        }

    fun onWebsiteObserved(observation: WebsiteObservation?) {
        lastWebsiteObservation = observation
        if (observation == null || !isTurnedOn) return
        evaluateAndBlock(observation.packageName, observation.urlIdentifier)
    }

    private fun evaluateAndBlock(packageName: String, urlIdentifier: String) {
        val matched = findMatchingGroups(urlIdentifier)
        if (matched.isEmpty()) return
        val now = System.currentTimeMillis()

        val eligible = matched.filter { group ->
            val cooldownEnd = cooldownGroupsList[group.id]
            if (cooldownEnd != null) {
                if (cooldownEnd > now) return@filter false
                else removeCooldownFrom(group.id)
            }
            true
        }

        for (group in eligible) {
            val window = group.config?.schedule?.activeWindow(now) ?: continue
            if (isUsageLimitExceeded(group, window)) {
                if (!claimBlock(packageName, urlIdentifier, group.id)) return
                handleBlocking(group, packageName)
                return
            }
        }

        // None blocked → schedule the soonest re-check across the matched groups
        var soonest = 0L
        for (group in eligible) {
            val recheck = computeNextRecheck(group)
            if (recheck > now && (soonest == 0L || recheck < soonest)) soonest = recheck
        }
        if (soonest > now) {
            CoroutineScope(Dispatchers.IO).launch {
                service.dataStoreManager.updateNextWebsiteRecheckTime(soonest)
            }
        }
    }

    private fun claimBlock(
        packageName: String,
        urlIdentifier: String,
        groupId: String
    ): Boolean = synchronized(blockGuard) {
        val now = System.currentTimeMillis()
        val target = blockTarget(packageName, urlIdentifier, groupId)
        if (target == lastBlockedTarget && now < blockSuppressedUntil) {
            false
        } else {
            lastBlockedTarget = target
            blockSuppressedUntil = now + BLOCK_SUPPRESSION_MS
            true
        }
    }

    private fun blockTarget(packageName: String, urlIdentifier: String, groupId: String): String =
        "$groupId\u0000$packageName\u0000$urlIdentifier"

    private fun handleBlocking(group: KeywordGroup, packageName: String) {
        Thread.sleep(250)
        // Back + Home can leave the tab on the blocked page, so leave it from the URL bar.
        if (!replaceBlockedPage(packageName)) {
            service.pressBack()
            service.pressHome()
        }
        Thread.sleep(1000)

        if (group.warningScreenConfig.isWarningDialogHidden) return

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

    private fun replaceBlockedPage(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (findMatchingGroups(REPLACEMENT_PAGE).isNotEmpty()) return false
        val urlBarId = URL_BAR_ID_LIST[packageName]?.displayUrlBarId ?: return false
        return runCatching {
            val root = service.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            val urlBar = AccessibilityHelper.findElementById(root, urlBarId) ?: return false
            if (!urlBar.isEditable) return false
            urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            urlBar.refresh()
            val text = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    REPLACEMENT_PAGE
                )
            }
            urlBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text) &&
                urlBar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }.getOrDefault(false)
    }

    private fun isUsageLimitExceeded(
        group: KeywordGroup,
        window: ActiveTimeGroupWindow
    ): Boolean {
        val usage = group.config?.usage ?: return false
        return isKeywordUsageLimitExceeded(limitForToday(usage)) {
            groupUsage(group, window)
        }
    }

    // Combined usage of every keyword in the group across all browsers, so the
    // limit applies to the group as a whole rather than each browser separately.
    private fun groupUsage(
        group: KeywordGroup,
        window: ActiveTimeGroupWindow
    ): Long {
        return runBlocking(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(service).websiteStatsDao()
            val usageEndMs = minOf(System.currentTimeMillis(), window.endMs)
            val zone = ZoneId.systemDefault()
            val startDate = Instant.ofEpochMilli(window.startMs).atZone(zone).toLocalDate()
            val endDate = Instant.ofEpochMilli(usageEndMs).atZone(zone).toLocalDate()
            val dates = buildList {
                var date = startDate
                while (!date.isAfter(endDate)) {
                    add(TimeTools.dayKey(date))
                    date = date.plusDays(1)
                }
            }
            val rows = dao.getStatsForDates(dates)
                .filter { matchesGroup(group, it.urlIdentifier) }
            WebsiteUsageWindow.sum(rows, window.startMs, usageEndMs)
        }
    }

    // Returns when this group should next be re-checked (0 if no re-check is needed). The caller is
    // responsible for persisting the soonest value across all matched groups.
    private fun computeNextRecheck(group: KeywordGroup): Long {
        val now = System.currentTimeMillis()
        val config = group.config ?: return 0L
        var nextRecheck = config.schedule.nextChangeAfter(now) ?: 0L

        val window = config.schedule.activeWindow(now)
        if (window != null) {
            val limit = limitForToday(config.usage) * 60_000L
            if (limit > 0) {
                val remaining = limit - groupUsage(group, window)
                if (remaining > 0) {
                    val usageLimitAt = now + remaining + 1_000L
                    if (nextRecheck == 0L || usageLimitAt < nextRecheck) {
                        nextRecheck = usageLimitAt
                    }
                }
            }
        }

        val cooldownEnd = cooldownGroupsList[group.id]
        if (cooldownEnd != null && cooldownEnd > now) {
            if (nextRecheck == 0L || cooldownEnd < nextRecheck) nextRecheck = cooldownEnd + 500
        }

        return nextRecheck
    }

    private fun limitForToday(config: AppUsageConfig): Long =
        if (config.isDailyUniform) {
            config.uniformLimit
        } else {
            config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        }

    private var configJob: Job? = null

    fun setupBlocker(service: BaseBlockingService, watchSettings: Boolean = true) {
        this.service = service
        this.browserBlocker = BrowserBlocker(service)
        this.prefs = service.getSharedPreferences("keyword_blocker_prefs", Context.MODE_PRIVATE)
        if (!::notificationManager.isInitialized) {
            notificationManager = TimerNotification(service, COOLDOWN_NOTIFICATION_ID)
        }
        loadPersistedData()

        if (!watchSettings) return

        configJob?.cancel()
        configJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                val keywordConfig =
                    settings.keywordBlockerConfig.upgradeLegacyKeywordGroupConfigs()
                isTurnedOn = keywordConfig.isActive
                isUnsupportedBrowserBlockingOn = keywordConfig.blockAllExceptSupported
                browserBlocker.isTurnedOn = isTurnedOn

                activeGroups = if (isTurnedOn) {
                    keywordConfig.keywordGroups.filter { it.isActive }
                } else {
                    emptyList()
                }

                groupPatternMap = activeGroups.associate { group ->
                    group.id to compileKeywords(group.selectedKeywords)
                }.toMutableMap()

                detectionCache.evictAll()

                if (isTurnedOn) {
                    startObservingDatabase()
                    showNextCooldownNotification()
                    reevaluateCurrentWebsite()
                    Handler(Looper.getMainLooper()).post {
                        val currentPackage =
                            service.rootInActiveWindow?.packageName?.toString() ?: return@post
                        val event = AccessibilityEvent.obtain(
                            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        )
                        event.packageName = currentPackage
                        checkIfUnsupportedBrowser(event)
                        event.recycle()
                    }
                } else {
                    observationJob?.cancel()
                    observationJob = null
                    notificationManager.stopTimer()
                    notifiedCooldownGroupId = null
                }
            }
        }
    }

    private fun reevaluateCurrentWebsite() {
        val observation = lastWebsiteObservation ?: return
        val currentPackage = runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        if (currentPackage != observation.packageName) return

        try {
            evaluateAndBlock(observation.packageName, observation.urlIdentifier)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("KeywordBlocker", "Failed to recheck current website", error)
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
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
            cooldownGroupsList.forEach { (id, end) -> putLong("cooldown_$id", end) }
        }
    }

    private fun removeCooldownFrom(id: String) {
        cooldownGroupsList.remove(id)
        prefs.edit {
            remove("cooldown_$id")
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
        }
        if (notifiedCooldownGroupId == id && ::notificationManager.isInitialized) {
            notifiedCooldownGroupId = null
            notificationManager.stopTimer()
            Handler(Looper.getMainLooper()).postDelayed(
                { showNextCooldownNotification() },
                100L
            )
        }
    }

    private fun handleCooldownIntent(intent: Intent) {
        val groupId = intent.getStringExtra("result_id") ?: return
        val duration = intent.getLongExtra("selected_time", 120000L)
        if (duration <= 0L) return

        val cooldownEnd = System.currentTimeMillis() + duration
        cooldownGroupsList[groupId] = cooldownEnd
        persistCooldownData()
        showCooldownNotification(groupId, cooldownEnd)

        val date = TimeTools.getCurrentDate()
        CoroutineScope(Dispatchers.IO).launch {
            val latest = AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForDate(date).maxByOrNull { it.lastVisited }
            if (latest != null && latest.lastVisited > (System.currentTimeMillis() - 5000)) {
                evaluateAndBlock(latest.packageName, latest.urlIdentifier)
            }
        }
    }

    private fun showNextCooldownNotification() {
        if (!isTurnedOn || !::notificationManager.isInitialized) return

        val now = System.currentTimeMillis()
        val nextCooldown = cooldownGroupsList
            .filterValues { it > now }
            .minByOrNull { it.value }

        if (nextCooldown == null) {
            notificationManager.stopTimer()
            notifiedCooldownGroupId = null
            return
        }

        showCooldownNotification(nextCooldown.key, nextCooldown.value)
    }

    private fun showCooldownNotification(groupId: String, cooldownEnd: Long) {
        if (!::notificationManager.isInitialized) return

        val remaining = cooldownEnd - System.currentTimeMillis()
        if (remaining <= 0L) {
            removeCooldownFrom(groupId)
            return
        }

        notifiedCooldownGroupId = groupId
        notificationManager.startTimer(
            totalMillis = remaining,
            timerId = "keyword_cooldown:$groupId:$cooldownEnd",
            title = service.getString(R.string.notification_remaining_usage_lockdown),
            onFinishCallback = {
                if (cooldownGroupsList[groupId] == cooldownEnd) {
                    cooldownGroupsList.remove(groupId)
                    prefs.edit {
                        remove("cooldown_$groupId")
                        putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
                    }
                }
                if (notifiedCooldownGroupId == groupId) {
                    notifiedCooldownGroupId = null
                    Handler(Looper.getMainLooper()).postDelayed(
                        { showNextCooldownNotification() },
                        100L
                    )
                }
            }
        )
    }

    // THIS IS EXPORTED HERE INTENTIONALLY. THE APPBLOCKER SERVICE RUNS IN A DIFFERENT PROCESS THAN
    // THE MAIN UI.
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_CONFIG)
            addAction(INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN)
        }
        ContextCompat.registerReceiver(
            service,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun removeReceivers() {
        service.unregisterReceiver(refreshReceiver)
        observationJob?.cancel()
        observationJob = null
        lastWebsiteObservation = null
        if (::notificationManager.isInitialized) {
            notificationManager.release()
        }
        notifiedCooldownGroupId = null
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
