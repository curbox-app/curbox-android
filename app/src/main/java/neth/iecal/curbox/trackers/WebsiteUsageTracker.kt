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
    @Volatile private var trackingEnabled = true

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
                val enabled = settings.isWebsiteUsageTrackingEnabled
                trackingEnabled = enabled
                if (!enabled) mainHandler.post { discardSession() }
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

        Log.d("website", "filtering url $inputText")
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
        if (event == null || !trackingEnabled) return
        
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


        Log.d("w source node",event.source.toString())
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

        val date = TimeTools.getCurrentDate()
        val wallNow = System.currentTimeMillis()
        scope.launch {
            if (!trackingEnabled) return@launch
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

    private fun discardSession() {
        currentPackage = null
        currentDomain = null
        currentUrlIdentifier = null
        domainStartTimeMs = 0L
    }
}
