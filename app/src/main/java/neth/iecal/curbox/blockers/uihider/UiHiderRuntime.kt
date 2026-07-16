package neth.iecal.curbox.blockers.uihider

import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import neth.iecal.curbox.blockers.uihider.script.Budget
import neth.iecal.curbox.blockers.uihider.script.Builtins
import neth.iecal.curbox.blockers.uihider.script.RuntimeApi
import neth.iecal.curbox.blockers.uihider.script.ScriptError
import neth.iecal.curbox.blockers.uihider.script.ScriptNode
import neth.iecal.curbox.blockers.uihider.script.Values
import neth.iecal.curbox.services.BaseBlockingService

/**
 * Per-run [RuntimeApi] implementation bridging the script language to the live accessibility
 * tree, overlay drawing, and global actions. Created fresh for every script execution.
 *
 * Accessibility node handles obtained during a run are tracked in [ownedNodes] and recycled in
 * [finish]; the supplied [root] is owned by the caller. The collected [drawCommands] are handed
 * to the [UiHiderOverlayManager] after the run completes.
 */
class UiHiderRuntime(
    private val service: BaseBlockingService,
    private val root: AccessibilityNodeInfo,
    private val budget: Budget,
    private val globals: Map<String, Any?>,
    private val scriptId: String,
    private val store: ScriptStore
) : RuntimeApi {

    val drawCommands = ArrayList<DrawCommand>()
    val output = StringBuilder()

    private val ownedNodes = ArrayList<AccessibilityNodeInfo>()
    private var autoKey = 0

    companion object {
        // Resolved foreign-app string resources, cached across runs (key = "package:resName").
        private val appStringCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        // Compiled patterns are immutable and safe to reuse across short-lived runtime instances.
        private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

        private const val MAX_SUBTREE_DEPTH = 64
        private const val MAX_SUBTREE_CHARS = 100_000
    }

    override fun provideGlobals(): Map<String, Any?> = globals

    override fun callFunction(name: String, args: List<Any?>, named: Map<String, Any?>): Any? {
        return when (name) {
            "root" -> NodeHandle(root)
            "find" -> NodeFinder.findFirst(root, named) { budget.countNode() }?.let { wrap(it) }
            "findAll" -> NodeFinder.findAll(root, named) { budget.countNode() }.map { wrap(it) }
            "draw" -> { addDraw(args, named); null }
            "hide" -> { hideNode(args, named); null }
            "back" -> { service.pressBack(); null }
            "home" -> { service.pressHome(); null }
            "log" -> { output.append(args.joinToString(" ") { Values.stringify(it) }).append('\n'); null }
            "appString" -> resolveAppString(args.getOrNull(0))
            "subtreeText" -> {
                val target = args.getOrNull(0)
                if (target !is NodeHandle) {
                    throw ScriptError("subtreeText() expects a node, got ${Values.typeName(target)}")
                }
                target.collectSubtreeText(args.drop(1))
            }
            "save" -> {
                val value = args.getOrNull(1)
                assertStorable(value)
                store.put(scriptId, storeKey(args, "save"), value)
                null
            }
            "load" -> store.get(scriptId, storeKey(args, "load"))
            "has" -> store.has(scriptId, storeKey(args, "has"))
            "remove" -> { store.remove(scriptId, storeKey(args, "remove")); null }
            else -> {
                val result = Builtins.tryCall(name, args, budget, regexCache)
                if (result === Builtins.UNKNOWN) throw ScriptError("unknown function '$name'")
                result
            }
        }
    }

    /** Recycle every node handle this run created. Call exactly once when the run ends. */
    fun finish() {
        for (node in ownedNodes) {
            try {
                @Suppress("DEPRECATION") node.recycle()
            } catch (_: Exception) {}
        }
        ownedNodes.clear()
    }

    private fun wrap(node: AccessibilityNodeInfo): NodeHandle {
        ownedNodes.add(node)
        return NodeHandle(node)
    }

    private fun addDraw(args: List<Any?>, named: Map<String, Any?>) {
        val x = coord(args, named, 0, "x")
        val y = coord(args, named, 1, "y")
        val w = coord(args, named, 2, "w")
        val h = coord(args, named, 3, "h")
        emitDraw(Rect(x, y, x + w, y + h), named)
    }

    private fun hideNode(args: List<Any?>, named: Map<String, Any?>) {
        val target = args.getOrNull(0)
        if (target !is NodeHandle) throw ScriptError("hide() expects a node, got ${Values.typeName(target)}")
        emitDraw(Rect(target.bounds), named)
    }

    private fun emitDraw(bounds: Rect, named: Map<String, Any?>) {
        if (bounds.isEmpty) return
        val key = (named["key"] as? String) ?: "auto::${autoKey++}"
        val color = (named["color"] as? String)?.let { parseColor(it) }
        val blockTouches = named["touch"]?.let { Values.isTruthy(it) } ?: true
        drawCommands.add(DrawCommand(key, bounds, color, blockTouches))
    }

    private fun coord(args: List<Any?>, named: Map<String, Any?>, index: Int, name: String): Int {
        val v = args.getOrNull(index) ?: named[name]
            ?: throw ScriptError("draw() missing coordinate '$name'")
        return Values.asInt(v, 0)
    }

    /**
     * Resolve a string resource by name from the foreground app's package (mirrors ViewBlocker's
     * `descres`). Lets scripts match localized content descriptions without hardcoding text.
     * Returns the resolved string, or null if the package or resource can't be found.
     */
    private fun resolveAppString(arg: Any?): Any? {
        val resName = arg as? String
            ?: throw ScriptError("appString() expects a resource name string")
        val pkg = globals["app"] as? String ?: return null
        val cacheKey = "$pkg:$resName"
        appStringCache[cacheKey]?.let { return it }
        return try {
            val res = service.packageManager.getResourcesForApplication(pkg)
            val id = res.getIdentifier(resName, "string", pkg)
            if (id == 0) null else res.getString(id).also { appStringCache[cacheKey] = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun storeKey(args: List<Any?>, fn: String): String {
        val k = args.getOrNull(0)
        if (k !is String) throw ScriptError("$fn() expects a string key")
        return k
    }

    /** Reject values that can't be persisted (live node handles); everything else is JSON-friendly. */
    private fun assertStorable(value: Any?) {
        when (value) {
            null, is Double, is String, is Boolean -> {}
            is List<*> -> value.forEach { assertStorable(it) }
            else -> throw ScriptError(
                "cannot save a ${Values.typeName(value)} (only numbers, strings, booleans, and lists)"
            )
        }
    }

    private fun parseColor(s: String): Int? = try {
        Color.parseColor(if (s.startsWith("#")) s else "#$s")
    } catch (_: Exception) { null }

    // Selector search itself lives in the shared NodeFinder; this runtime only tracks the nodes a
    // run produced so they can be recycled in finish(), and charges each visited node to the budget.

    private fun obtain(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        @Suppress("DEPRECATION") AccessibilityNodeInfo.obtain(node)

    private fun recycle(node: AccessibilityNodeInfo) {
        try {
            @Suppress("DEPRECATION") node.recycle()
        } catch (_: Exception) {}
    }

    /** Script-visible wrapper over an [AccessibilityNodeInfo]. Bounds are read once, lazily. */
    inner class NodeHandle(private val node: AccessibilityNodeInfo) : ScriptNode {

        val bounds: Rect by lazy { Rect().also { node.getBoundsInScreen(it) } }

        override fun prop(name: String): Any? = when (name) {
            "id" -> node.viewIdResourceName
            "text" -> node.text?.toString()
            "desc" -> node.contentDescription?.toString()
            "class" -> node.className?.toString()
            "x", "left" -> bounds.left.toDouble()
            "y", "top" -> bounds.top.toDouble()
            "right" -> bounds.right.toDouble()
            "bottom" -> bounds.bottom.toDouble()
            "w", "width" -> bounds.width().toDouble()
            "h", "height" -> bounds.height().toDouble()
            "cx" -> bounds.centerX().toDouble()
            "cy" -> bounds.centerY().toDouble()
            "childCount" -> node.childCount.toDouble()
            "clickable" -> node.isClickable
            "scrollable" -> node.isScrollable
            "checked" -> node.isChecked
            "selected" -> node.isSelected
            "focused" -> node.isFocused
            "enabled" -> node.isEnabled
            "visible" -> node.isVisibleToUser
            "path" -> computePath(node)
            else -> throw ScriptError("unknown node property '$name'")
        }

        override fun call(name: String, args: List<Any?>, named: Map<String, Any?>): Any? = when (name) {
            "find" -> NodeFinder.findFirst(node, named) { budget.countNode() }?.let { wrap(it) }
            "findAll" -> NodeFinder.findAll(node, named) { budget.countNode() }.map { wrap(it) }
            "child" -> {
                val i = Values.asInt(args.getOrNull(0) ?: throw ScriptError("child() needs an index"), 0)
                if (i < 0 || i >= node.childCount) null else node.getChild(i)?.let { wrap(it) }
            }
            "children" -> (0 until node.childCount).mapNotNull { i -> node.getChild(i)?.let { wrap(it) } }
            "parent" -> node.parent?.let { wrap(it) }
            "hide" -> { emitDraw(Rect(bounds), named); null }
            "subtreeText" -> collectSubtreeText(args)
            else -> throw ScriptError("unknown node method '$name'")
        }

        fun collectSubtreeText(args: List<Any?>): String {
            val maxDepth = boundedSubtreeArg(args, 0, "maxDepth", MAX_SUBTREE_DEPTH)
            val maxChars = boundedSubtreeArg(args, 1, "maxChars", MAX_SUBTREE_CHARS)
            if (maxChars == 0) return ""

            val out = StringBuilder(minOf(maxChars, 1024))
            budget.countNode()
            appendNodeText(node, out, maxChars)
            if (out.length >= maxChars || maxDepth == 0) return out.toString()

            fun visit(parent: AccessibilityNodeInfo, depth: Int) {
                if (depth > maxDepth || out.length >= maxChars) return
                for (i in 0 until parent.childCount) {
                    if (out.length >= maxChars) break
                    budget.countNode()
                    val child = parent.getChild(i) ?: continue
                    try {
                        appendNodeText(child, out, maxChars)
                        if (depth < maxDepth && out.length < maxChars) visit(child, depth + 1)
                    } finally {
                        recycle(child)
                    }
                }
            }

            visit(node, 1)
            return out.toString()
        }

        private fun boundedSubtreeArg(
            args: List<Any?>,
            index: Int,
            label: String,
            hardMax: Int
        ): Int {
            val raw = args.getOrNull(index)
                ?: throw ScriptError("subtreeText() requires $label")
            val value = Values.asInt(raw, 0)
            if (value < 0) throw ScriptError("subtreeText() $label must be non-negative")
            if (value > hardMax) {
                throw ScriptError("subtreeText() $label exceeds maximum $hardMax")
            }
            return value
        }

        private fun appendNodeText(target: AccessibilityNodeInfo, out: StringBuilder, maxChars: Int) {
            appendTextPart(target.text?.toString(), out, maxChars)
            val desc = target.contentDescription?.toString()
            if (desc != target.text?.toString()) appendTextPart(desc, out, maxChars)
        }

        private fun appendTextPart(value: String?, out: StringBuilder, maxChars: Int) {
            val text = value?.trim().orEmpty()
            if (text.isEmpty() || out.length >= maxChars) return
            if (out.isNotEmpty()) {
                if (out.length >= maxChars) return
                out.append('\n')
            }
            val remaining = maxChars - out.length
            if (remaining > 0) out.append(text, 0, minOf(text.length, remaining))
        }
    }

    /** Best-effort class-index path from the root, e.g. `FrameLayout[0]/RecyclerView[1]`. */
    private fun computePath(node: AccessibilityNodeInfo): String {
        val segments = ArrayList<String>()
        var current: AccessibilityNodeInfo? = obtain(node)
        var guard = 0
        while (current != null && guard++ < 100) {
            val cur = current
            val parent = cur.parent
            if (parent == null) { recycle(cur); break }
            val cls = cur.className?.toString()?.substringAfterLast('.') ?: "?"
            var index = 0
            for (i in 0 until parent.childCount) {
                val sib = parent.getChild(i) ?: continue
                val isSame = sib == cur
                val sibCls = sib.className?.toString()?.substringAfterLast('.')
                recycle(sib)
                if (isSame) break
                if (sibCls == cls) index++
            }
            segments.add("$cls[$index]")
            recycle(cur)
            current = parent
        }
        return segments.asReversed().joinToString("/")
    }
}
