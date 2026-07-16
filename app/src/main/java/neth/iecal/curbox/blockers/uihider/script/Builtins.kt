package neth.iecal.curbox.blockers.uihider.script

import java.util.concurrent.ConcurrentHashMap

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Host-agnostic standard library: math and utility functions with no Android dependency.
 * The runtime's [RuntimeApi] delegates here for any name it doesn't handle itself; [UNKNOWN]
 * is returned for names this object doesn't recognise so the host can raise a clean error.
 */
object Builtins {

    val UNKNOWN = Any()

    private const val MAX_RANGE = 100_000
    private const val MAX_REGEX_LENGTH = 16_384
    private const val REGEX_CHARS_PER_OPERATION = 16

    fun tryCall(
        name: String,
        args: List<Any?>,
        budget: Budget? = null,
        regexCache: ConcurrentHashMap<String, Regex>? = null
    ): Any? = when (name) {
        "abs" -> abs(num(name, args, 0))
        "floor" -> floor(num(name, args, 0))
        "ceil" -> ceil(num(name, args, 0))
        "round" -> num(name, args, 0).roundToLong().toDouble()
        "sqrt" -> sqrt(num(name, args, 0))
        "pow" -> num(name, args, 0).pow(num(name, args, 1))
        "min" -> reduceNums(name, args) { a, b -> if (a < b) a else b }
        "max" -> reduceNums(name, args) { a, b -> if (a > b) a else b }
        "clamp" -> {
            val v = num(name, args, 0); val lo = num(name, args, 1); val hi = num(name, args, 2)
            if (v < lo) lo else if (v > hi) hi else v
        }
        "len" -> when (val x = arg(name, args, 0)) {
            is String -> x.length.toDouble()
            is List<*> -> x.size.toDouble()
            else -> throw ScriptError("len() expects a string or list, got ${Values.typeName(x)}")
        }
        "str" -> Values.stringify(arg(name, args, 0))
        "lower" -> stringArg(name, args, 0).lowercase()
        "containsIgnoreCase" -> stringArg(name, args, 0).contains(
            stringArg(name, args, 1),
            ignoreCase = true
        )
        "matchesRegex" -> matchesRegex(args, budget, regexCache)
        "int" -> when (val x = arg(name, args, 0)) {
            is Double -> x.toLong().toDouble()
            is Boolean -> if (x) 1.0 else 0.0
            is String -> x.trim().toDoubleOrNull()?.toLong()?.toDouble()
                ?: throw ScriptError("int() cannot parse \"$x\"")
            else -> throw ScriptError("int() expects a number or string, got ${Values.typeName(x)}")
        }
        "range" -> range(args)
        else -> UNKNOWN
    }


    private fun matchesRegex(
        args: List<Any?>,
        budget: Budget?,
        regexCache: ConcurrentHashMap<String, Regex>?
    ): Boolean {
        val input = stringArg("matchesRegex", args, 0)
        val pattern = stringArg("matchesRegex", args, 1)
        val flags = if (args.size >= 3) stringArg("matchesRegex", args, 2) else ""

        if (pattern.length > MAX_REGEX_LENGTH) {
            throw ScriptError("matchesRegex() pattern too long (max $MAX_REGEX_LENGTH characters)")
        }

        val options = LinkedHashSet<RegexOption>()
        for (flag in flags) {
            when (flag.lowercaseChar()) {
                'i' -> options.add(RegexOption.IGNORE_CASE)
                'm' -> options.add(RegexOption.MULTILINE)
                's' -> options.add(RegexOption.DOT_MATCHES_ALL)
                'u' -> Unit // Kotlin/JVM regexes are Unicode-aware; accepted for API familiarity.
                else -> throw ScriptError("matchesRegex() unknown flag '$flag'")
            }
        }

        // Approximate regex work by the amount of input and pattern data examined. The runtime's
        // hard time deadline remains the final guard against unexpectedly expensive expressions.
        val charge = maxOf(1, (input.length + pattern.length + REGEX_CHARS_PER_OPERATION - 1) /
            REGEX_CHARS_PER_OPERATION)
        budget?.chargeOperations(charge)

        val optionKey = options.map { it.name }.sorted().joinToString(",")
        val cacheKey = "$optionKey\u0000$pattern"
        val regex = try {
            regexCache?.get(cacheKey) ?: Regex(pattern, options).also {
                regexCache?.putIfAbsent(cacheKey, it)
            }
        } catch (e: IllegalArgumentException) {
            throw ScriptError("matchesRegex() invalid pattern: ${e.message ?: "syntax error"}")
        }
        return regex.containsMatchIn(input)
    }

    private fun range(args: List<Any?>): List<Any?> {
        val start: Int
        val end: Int
        when (args.size) {
            1 -> { start = 0; end = Values.asInt(args[0], 0) }
            2 -> { start = Values.asInt(args[0], 0); end = Values.asInt(args[1], 0) }
            else -> throw ScriptError("range() expects 1 or 2 arguments")
        }
        if (end - start > MAX_RANGE) throw ScriptError("range too large (max $MAX_RANGE)")
        val out = ArrayList<Any?>(maxOf(0, end - start))
        var i = start
        while (i < end) { out.add(i.toDouble()); i++ }
        return out
    }

    private fun reduceNums(name: String, args: List<Any?>, op: (Double, Double) -> Double): Double {
        val nums: List<Double> = if (args.size == 1 && args[0] is List<*>) {
            (args[0] as List<*>).map { Values.asNumber(it, 0) }
        } else {
            args.map { Values.asNumber(it, 0) }
        }
        if (nums.isEmpty()) throw ScriptError("$name() needs at least one number")
        return nums.reduce(op)
    }

    private fun arg(name: String, args: List<Any?>, i: Int): Any? {
        if (i >= args.size) throw ScriptError("$name() missing argument ${i + 1}")
        return args[i]
    }

    private fun num(name: String, args: List<Any?>, i: Int): Double =
        Values.asNumber(arg(name, args, i), 0)

    private fun stringArg(name: String, args: List<Any?>, i: Int): String {
        val value = arg(name, args, i)
        return value as? String
            ?: throw ScriptError("$name() expects argument ${i + 1} to be a string, got ${Values.typeName(value)}")
    }
}
