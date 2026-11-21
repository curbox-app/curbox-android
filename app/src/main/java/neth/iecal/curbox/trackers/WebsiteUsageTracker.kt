package neth.iecal.curbox.trackers

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsDao
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AccessibilityHelper
import neth.iecal.curbox.utils.TimeTools
import java.time.Instant
import java.time.ZoneId
import kotlin.text.endsWith
import kotlin.text.substring

class WebsiteUsageTracker {

    companion object {
        // Flush the running session this often so time is recorded even when a
        // browser (e.g. Firefox/GeckoView) does not fire a url change or leave
        // event that would otherwise trigger a commit.
        private const val HEARTBEAT_MS = 15_000L
    }

    private lateinit var service: BaseBlockingService
    private lateinit var websiteStatsDao: WebsiteStatsDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // All timing state is read and written on the main thread (accessibility
    // events, the heartbeat and rechecks all post here) so it never races.
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentPackage: String? = null
    private var currentDomain: String? = null
    private var currentUrlIdentifier: String? = null
    private var domainStartTimeMs: Long = 0L
    private var onWebsiteObserved: (WebsiteObservation?) -> Unit = {}

    private var settingsJob: Job? = null
    private var recheckJob: Job? = null
    @Volatile private var destroyed = false
    @Volatile private var trackingEnabled = true

    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyed) return
            saveSession()
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun setup(
        service: BaseBlockingService,
        onWebsiteObserved: (WebsiteObservation?) -> Unit = {}
    ) {
        this.service = service
        this.onWebsiteObserved = onWebsiteObserved
        val db = AppDatabase.getInstance(service)
        this.websiteStatsDao = db.websiteStatsDao()
        startObservingRecheckTime()
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun startObservingRecheckTime() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            service.dataStoreManager.settings.collect { settings ->
                val enabled = settings.isWebsiteUsageTrackingEnabled
                val wasEnabled = trackingEnabled
                trackingEnabled = enabled
                mainHandler.post {
                    if (destroyed) return@post
                    when {
                        !enabled -> pauseUsageSession()
                        !wasEnabled -> resumeUsageSession()
                    }
                }
                val nextRecheck = settings.nextWebsiteRecheckTime
                if (nextRecheck > System.currentTimeMillis()) {
                    scheduleRecheck(nextRecheck)
                }
            }
        }
    }

    private fun scheduleRecheck(recheckTime: Long) {
        if (destroyed) return
        recheckJob?.cancel()
        recheckJob = scope.launch {
            val delayMs = recheckTime - System.currentTimeMillis()
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
                Log.d("WebsiteUsageTracker", "Executing scheduled recheck")
                mainHandler.post {
                    if (destroyed) return@post
                    saveSession()
                    emitCurrentObservation()
                }
            }
        }
    }

    private fun filterOutUrlFromPlainText(inputText: String?): String? {
        if (inputText.isNullOrBlank()) return null

        if (BuildConfig.DEBUG) Log.d("website", "filtering url $inputText")
        val urlRegex = Regex(
            pattern = """(?:https?://|www\.)?[^\s<>\"']+""",
            option = RegexOption.IGNORE_CASE
        )

        for (match in urlRegex.findAll(inputText)) {
            val cleanUrl = match.value
                .trimStart('(', '[', '{')
                .trimEnd('.', ',', ')', ']', '}', '!', ';', ':')
            val uriText = if (cleanUrl.startsWith("http://", ignoreCase = true) ||
                cleanUrl.startsWith("https://", ignoreCase = true)
            ) cleanUrl else "https://$cleanUrl"

            val uri = runCatching { java.net.URI(uriText) }.getOrNull() ?: continue
            val host = uri.host ?: continue
            if (!host.contains('.')) continue

            val normalizedHost = host.removePrefix("www.")
            val path = uri.rawPath.orEmpty().let { if (it == "/") "" else it }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
            return "$normalizedHost$path$query$fragment"
        }

        return null
    }
    fun onEvent(event: AccessibilityEvent?) {
        if (destroyed || event == null) return
        
        val packageName = event.packageName?.toString() ?: return
        
        if (!URL_BAR_ID_LIST.containsKey(packageName)) {
            mainHandler.post { if (!destroyed) leaveCurrentWebsite() }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return
        }

        val rootNode = service.rootInActiveWindow ?: return
        val urlBarInfo = URL_BAR_ID_LIST[packageName] ?: return


        if (BuildConfig.DEBUG) Log.d("w source node", event.source.toString())
        try {
            val nodes = AccessibilityHelper.findElementById(
                rootNode,
                urlBarInfo.displayUrlBarId
            ) ?: AccessibilityHelper.findElementById(
                event.source,
                urlBarInfo.displayUrlBarId
            ) ?: return
            if (BuildConfig.DEBUG) Log.d("found node", nodes.toString())
            val text = (nodes.text ?: nodes.contentDescription).toString()

            if (text.isNotEmpty()) {
                val filteredUrl = filterOutUrlFromPlainText(text)
                val siteInfo = extractSiteInfo(filteredUrl?:text)
                if (siteInfo.domain.isNotEmpty()) {
                    mainHandler.post { observeWebsite(packageName, siteInfo) }
                }
            }
        } catch (e: Exception) {
            Log.e("WebsiteUsageTracker", "Failed to find node", e)
        }
    }

    private data class SiteInfo(val domain: String, val urlIdentifier: String)
    private data class HourSlice(val date: String, val hour: Int, val durationMs: Long)

    private fun observeWebsite(packageName: String, siteInfo: SiteInfo) {
        if (destroyed) return
        if (siteInfo.urlIdentifier == currentUrlIdentifier && packageName == currentPackage) return

        if (BuildConfig.DEBUG) Log.d("saving session", siteInfo.urlIdentifier)
        saveSession()
        currentDomain = siteInfo.domain
        currentUrlIdentifier = siteInfo.urlIdentifier
        currentPackage = packageName
        domainStartTimeMs = if (trackingEnabled) SystemClock.uptimeMillis() else 0L
        emitCurrentObservation()
        saveInitialSession()
    }

    private fun emitCurrentObservation() {
        val packageName = currentPackage ?: return
        val domain = currentDomain ?: return
        val urlIdentifier = currentUrlIdentifier ?: return
        onWebsiteObserved(WebsiteObservation(packageName, domain, urlIdentifier))
    }

    private fun leaveCurrentWebsite() {
        if (currentPackage == null) return
        saveSession()
        currentPackage = null
        currentDomain = null
        currentUrlIdentifier = null
        domainStartTimeMs = 0L
        onWebsiteObserved(null)
    }

    private fun pauseUsageSession() {
        domainStartTimeMs = 0L
    }

    private fun resumeUsageSession() {
        if (currentPackage == null || currentDomain == null || currentUrlIdentifier == null) return
        domainStartTimeMs = SystemClock.uptimeMillis()
        saveInitialSession()
    }

    private fun splitByLocalHour(startMs: Long, endMs: Long): List<HourSlice> {
        if (endMs <= startMs) return emptyList()
        val zone = ZoneId.systemDefault()
        val slices = ArrayList<HourSlice>(2)
        var cursor = startMs
        while (cursor < endMs) {
            val current = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = current.withMinute(0).withSecond(0).withNano(0)
                .plusHours(1).toInstant().toEpochMilli()
            val sliceEnd = minOf(endMs, nextHour.coerceAtLeast(cursor + 1))
            slices += HourSlice(
                date = TimeTools.dayKey(current.toLocalDate()),
                hour = current.hour,
                durationMs = sliceEnd - cursor
            )
            cursor = sliceEnd
        }
        return slices
    }

    private fun extractSiteInfo(urlText: String): SiteInfo {
        return try {
            var url = urlText
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            val uri = java.net.URI(url)
            val domain = (uri.host ?: urlText).lowercase().removePrefix("www.")

            // Keep the complete URL identifier because keyword rules can target
            // nested paths, query values, or fragments. Usage limits aggregate
            // every matching identifier later, so URL changes remain combined.
            val path = uri.rawPath.orEmpty().let { if (it == "/") "" else it }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
            val identifier = "$domain$path$query$fragment"

            SiteInfo(domain, identifier)
        } catch (e: Exception) {
            SiteInfo(urlText, urlText)
        }
    }


    private fun saveInitialSession() {
        if (!trackingEnabled) return
        val domain = currentDomain
        val identifier = currentUrlIdentifier
        val packageName = currentPackage

        if (domain != null && identifier != null && packageName != null) {
            val date = TimeTools.getCurrentDate()
            val wallNow = System.currentTimeMillis()
            scope.launch {
                if (!trackingEnabled) return@launch
                try {
                    // Make the row visible immediately without ever touching
                    // totalTime, so an in flight time increment is never clobbered.
                    websiteStatsDao.insertIfAbsent(
                        WebsiteStatsEntity(
                            date = date,
                            packageName = packageName,
                            urlIdentifier = identifier,
                            domain = domain,
                            totalTime = 0L,
                            lastVisited = wallNow
                        )
                    )
                    websiteStatsDao.touch(date, packageName, identifier, wallNow)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("WebsiteUsageTracker", "Failed to save initial website trace", e)
                }
            }
        }
    }

    private fun saveSession() {
        if (!trackingEnabled) return
        val domain = currentDomain
        val identifier = currentUrlIdentifier
        val packageName = currentPackage
        val startTime = domainStartTimeMs

        if (domain == null || identifier == null || packageName == null || startTime <= 0) return

        val now = SystemClock.uptimeMillis()
        val durationMs = now - startTime
        // Advance the clock so repeated commits (rechecks, leaving the browser)
        // never double count the same span.
        domainStartTimeMs = now

        // Conserve every slice instead of discarding short ones. Browsers like
        // Firefox change the address bar text many times a second, so the elapsed
        // time arrives in sub second pieces that must be accumulated, not dropped.
        if (durationMs <= 0) return

        val wallNow = System.currentTimeMillis()
        val wallStart = (wallNow - durationMs).coerceAtMost(wallNow)
        scope.launch {
            if (!trackingEnabled) return@launch
            try {
                splitByLocalHour(wallStart, wallNow).forEach { slice ->
                    val entity = WebsiteStatsEntity(
                        date = slice.date,
                        packageName = packageName,
                        urlIdentifier = identifier,
                        domain = domain,
                        totalTime = 0L,
                        lastVisited = wallNow
                    )
                    websiteStatsDao.insertIfAbsent(entity)
                    if (BuildConfig.DEBUG) Log.d("saving session", entity.toString())
                    websiteStatsDao.addTime(
                        slice.date,
                        packageName,
                        identifier,
                        slice.hour,
                        slice.durationMs,
                        wallNow
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WebsiteUsageTracker", "Failed to save website trace", e)
            }
        }
    }

    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        settingsJob?.cancel()
        recheckJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            leaveCurrentWebsite()
            // Finish queued writes, including the final session, before cancelling the scope.
            runBlocking(Dispatchers.IO) {
                scope.coroutineContext[Job]?.children?.toList()?.joinAll()
            }
        } finally {
            onWebsiteObserved = {}
            scope.cancel()
        }
    }
}
