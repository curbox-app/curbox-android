package neth.iecal.curbox.trackers

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsDao
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AccessibilityHelper
import neth.iecal.curbox.utils.TimeTools
import java.util.regex.Pattern
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

    private var recheckJob: Job? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            saveSession()
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun setup(service: BaseBlockingService) {
        this.service = service
        val db = AppDatabase.getInstance(service)
        this.websiteStatsDao = db.websiteStatsDao()
        startObservingRecheckTime()
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun startObservingRecheckTime() {
        scope.launch {
            service.dataStoreManager.settings.collect { settings ->
                val nextRecheck = settings.nextWebsiteRecheckTime
                if (nextRecheck > System.currentTimeMillis()) {
                    scheduleRecheck(nextRecheck)
                }
            }
        }
    }

    private fun scheduleRecheck(recheckTime: Long) {
        recheckJob?.cancel()
        recheckJob = scope.launch {
            val delayMs = recheckTime - System.currentTimeMillis()
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
                Log.d("WebsiteUsageTracker", "Executing scheduled recheck")
                saveSession()
            }
        }
    }

    private fun filterOutUrlFromPlainText(inputText: String?): String? {
        if (inputText.isNullOrBlank()) return null

        val urlRegex = """(?:https?://|www\.)?[a-zA-Z0-9][a-zA-Z0-9\-]{1,61}[a-zA-Z0-9]\.[a-zA-Z]{2,}(?:[/\?#][a-zA-Z0-9\-._~:/?#\[\]@!${'$'}&'()*+,;=%]*)?"""
        val pattern = Pattern.compile(urlRegex, Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(inputText)

        if (matcher.find()) {
            var cleanUrl = matcher.group(0) ?: return null

            // Strip trailing punctuation unlikely to be part of the URL
            cleanUrl = cleanUrl.trimEnd('.', ',', ')', ']', '\'', '"', '>')

            return cleanUrl
        }

        return null
    }
    fun onEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val packageName = event.packageName?.toString() ?: return
        
        if (!URL_BAR_ID_LIST.containsKey(packageName)) {
            // Not a supported browser package
            if (currentPackage != null) {
                saveSession()
                currentPackage = null
                currentDomain = null
                currentUrlIdentifier = null
            }
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


        Log.d("source node",event.source.toString())
        try {
            val nodes = AccessibilityHelper.findElementById(
                rootNode,
                urlBarInfo.displayUrlBarId
            ) ?: AccessibilityHelper.findElementById(
                event.source,
                urlBarInfo.displayUrlBarId
            ) ?: return
            Log.d("found node",nodes.toString())
            val text = (nodes.text ?: nodes.contentDescription).toString()

            if (text.isNotEmpty()) {
                val filteredUrl = filterOutUrlFromPlainText(text)
                val siteInfo = extractSiteInfo(filteredUrl?:text)
                if (siteInfo.domain.isNotEmpty()) {
                    if (siteInfo.urlIdentifier != currentUrlIdentifier || packageName != currentPackage) {
                        Log.d("saving session", text)
                        saveSession()
                        currentDomain = siteInfo.domain
                        currentUrlIdentifier = siteInfo.urlIdentifier
                        currentPackage = packageName
                        domainStartTimeMs = SystemClock.uptimeMillis()
                        saveInitialSession()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebsiteUsageTracker", "Failed to find node", e)
        }
    }

    private data class SiteInfo(val domain: String, val urlIdentifier: String)

    private fun extractSiteInfo(urlText: String): SiteInfo {
        return try {
            var url = urlText
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            val uri = java.net.URI(url)
            val domain = (uri.host ?: urlText).lowercase().removePrefix("www.")

            // Keep only the first path segment so deep links and query changes
            // within the same section collapse to one stable identifier
            // (e.g. "youtube.com/shorts/abc?v=1" -> "youtube.com/shorts").
            // Firefox shows the full URL in its address bar, so without this the
            // identifier would change constantly and reset the usage timer.
            val firstSegment = uri.path
                ?.split('/')
                ?.firstOrNull { it.isNotEmpty() }
                ?.let { "/$it" }
                .orEmpty()
            val identifier = if (firstSegment.isEmpty()) domain else "$domain$firstSegment"

            SiteInfo(domain, identifier)
        } catch (e: Exception) {
            SiteInfo(urlText, urlText)
        }
    }


    private fun saveInitialSession() {
        val domain = currentDomain
        val identifier = currentUrlIdentifier
        val packageName = currentPackage

        if (domain != null && identifier != null && packageName != null) {
            val date = TimeTools.getCurrentDate()
            val wallNow = System.currentTimeMillis()
            scope.launch {
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
                } catch (e: Exception) {
                    Log.e("WebsiteUsageTracker", "Failed to save initial website trace", e)
                }
            }
        }
    }

    private fun saveSession() {
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

        val date = TimeTools.getCurrentDate()
        val wallNow = System.currentTimeMillis()
        scope.launch {
            try {
                val entity = WebsiteStatsEntity(
                    date = date,
                    packageName = packageName,
                    urlIdentifier = identifier,
                    domain = domain,
                    totalTime = 0L,
                    lastVisited = wallNow
                )
                websiteStatsDao.insertIfAbsent(
                    entity
                )
                Log.d("saving session", entity.toString())

                websiteStatsDao.addTime(date, packageName, identifier, durationMs, wallNow)
            } catch (e: Exception) {
                Log.e("WebsiteUsageTracker", "Failed to save website trace", e)
            }
        }
    }

    fun onDestroy() {
        recheckJob?.cancel()
        saveSession()
    }
}
