package neth.iecal.curbox.blockers.uihider.script

/**
 * Hard execution limits for a single script run. The interpreter calls [tick] on every
 * statement and loop iteration so a runaway script (e.g. `while true {}`) is always
 * aborted with a [ScriptError] instead of hanging the background worker.
 */
class Budget(
    private val maxOps: Int = 300_000,
    private val maxNodes: Int = 20_000,
    private val maxDepth: Int = 64,
    timeBudgetMs: Long = 40L
) {
    private val deadlineNs = System.nanoTime() + timeBudgetMs * 1_000_000L
    private var ops = 0
    private var nodes = 0
    private var depth = 0

    fun tick() {
        if (++ops > maxOps) throw ScriptError("script exceeded operation budget ($maxOps)")
        // Checking the clock on every op is wasteful; sample it periodically.
        if (ops and 0x3FF == 0 && System.nanoTime() > deadlineNs) {
            throw ScriptError("script exceeded time budget")
        }
    }

    fun countNode() {
        if (++nodes > maxNodes) throw ScriptError("script visited too many nodes ($maxNodes)")
    }

    /** Charge a bounded amount of host-side work (for example regex input scanning). */
    fun chargeOperations(amount: Int) {
        if (amount <= 0) return
        if (amount > maxOps - ops) {
            throw ScriptError("script exceeded operation budget ($maxOps)")
        }
        ops += amount
        if (System.nanoTime() > deadlineNs) {
            throw ScriptError("script exceeded time budget")
        }
    }

    fun enterCall() {
        if (++depth > maxDepth) throw ScriptError("call depth exceeded ($maxDepth)")
    }

    fun exitCall() {
        depth--
    }
}
