package neth.iecal.curbox.blockers

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.blockers.uihider.UiHiderRuntime
import neth.iecal.curbox.blockers.uihider.script.Budget
import neth.iecal.curbox.blockers.uihider.script.Interpreter
import neth.iecal.curbox.blockers.uihider.script.Parser
import neth.iecal.curbox.blockers.uihider.script.ScriptError
import neth.iecal.curbox.blockers.uihider.script.Stmt
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.INSTAGRAM_DM_REEL_MARKER
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.reelData
import neth.iecal.curbox.services.BaseBlockingService

data class ReelDetection(
    val comparator: String,
    val isInstagramReelOpenedFromDmInbox: Boolean = false,
    val position: Int? = null
)

// not the cleanest way but okay ig
internal fun reelDetectionFromScriptResult(
    result: String,
    comparisonResultCleanser: (String) -> String,
    positionMarker: String? = null
): ReelDetection {
    val openedFromDmInbox = result.startsWith(INSTAGRAM_DM_REEL_MARKER)
    var comparator = if (openedFromDmInbox) {
        result.removePrefix(INSTAGRAM_DM_REEL_MARKER)
    } else {
        result
    }

    // The position line is bookkeeping, so it is removed before the app cleanser sees the text.
    var position: Int? = null
    if (positionMarker != null && comparator.contains(positionMarker)) {
        val lines = comparator.lines()
        position = lines.firstOrNull { it.startsWith(positionMarker) }
            ?.removePrefix(positionMarker)
            ?.trim()
            ?.toIntOrNull()
        if (position != null) {
            comparator = lines.filterNot { it.startsWith(positionMarker) }.joinToString("\n")
        }
    }

    return ReelDetection(
        comparator = comparisonResultCleanser(comparator),
        isInstagramReelOpenedFromDmInbox = openedFromDmInbox,
        position = position
    )
}

/** Runs the shipped reel detectors with the same node API and safety budget as UI Hider scripts. */
class ReelScriptRunner {

    private lateinit var service: BaseBlockingService
    private lateinit var crashLogger: CrashLogger
    private var programs: Map<String, List<Stmt>> = emptyMap()
    private var screen: Map<String, Any?> = emptyMap()

    fun setup(service: BaseBlockingService) {
        this.service = service
        crashLogger = CrashLogger(service)
        val metrics = service.resources.displayMetrics
        screen = mapOf(
            "width" to metrics.widthPixels.toDouble(),
            "height" to metrics.heightPixels.toDouble()
        )
        programs = buildMap {
            for ((packageName, data) in reelData) {
                try {
                    put(packageName, Parser.parse(data.scriptSource))
                } catch (error: ScriptError) {
                    Log.w(TAG, "Compile error in reel detector for $packageName: ${error.message}")
                    crashLogger.logNonFatalError(error)
                }
            }
        }
    }

    /** Returns reel screen details, including an empty comparator while its content loads. */
    fun detect(event: AccessibilityEvent): ReelDetection? {
        val packageName = event.packageName?.toString() ?: return null
        val data = reelData[packageName] ?: return null
        val program = programs[packageName] ?: return null
        val root = service.rootInActiveWindow ?: return null
        val budget = Budget()
        val runtime = UiHiderRuntime(
            service = service,
            root = root,
            budget = budget,
            globals = mapOf(
                "app" to packageName,
                "screen" to screen,
                "event" to mapOf(
                    "type" to eventTypeName(event.eventType),
                    "package" to packageName,
                    "text" to event.text.joinToString(" ").takeIf { it.isNotEmpty() },
                    "class" to event.className?.toString(),
                    "fromIndex" to event.fromIndex.toDouble(),
                    "toIndex" to event.toIndex.toDouble(),
                    "itemCount" to event.itemCount.toDouble()
                )
            ),
            scriptId = "reel::$packageName",
            store = null
        )

        return try {
            val result = Interpreter(runtime, budget).run(program) as? String ?: return null
            reelDetectionFromScriptResult(result, data.comparisonResultCleanser, data.positionMarker)
        } catch (error: ScriptError) {
            Log.w(TAG, "Runtime error in reel detector for $packageName: ${error.message}")
            crashLogger.logNonFatalError(error)
            null
        } finally {
            runtime.finish()
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scrolled"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "clicked"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "selected"
        else -> "other"
    }

    private companion object {
        const val TAG = "ReelScriptRunner"
    }
}
