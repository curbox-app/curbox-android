package neth.iecal.curbox.blockers.uihider.script

/**
 * Tree-walking interpreter for a parsed UIHider script.
 *
 * Semantics: dynamic typing, lexical block scopes, top-level user functions. Host concerns
 * (nodes, overlays, global actions) are delegated to [api]; the [budget] bounds every
 * statement and loop iteration so a run can never hang the service.
 *
 * `and`/`or` short-circuit and return the deciding operand value (Lua-style). A top-level
 * `return` simply ends the run.
 */
class Interpreter(
    private val api: RuntimeApi,
    private val budget: Budget
) {

    private class Environment(val parent: Environment?) {
        private val values = HashMap<String, Any?>()

        fun definedHere(name: String) = values.containsKey(name)
        fun getLocal(name: String): Any? = values[name]
        fun define(name: String, value: Any?) { values[name] = value }

        fun get(name: String): Any? {
            var env: Environment? = this
            while (env != null) {
                if (env.definedHere(name)) return env.getLocal(name)
                env = env.parent
            }
            throw ScriptError("undefined variable '$name'")
        }

        /** Reassign the nearest existing binding, or declare in the current scope. */
        fun assign(name: String, value: Any?) {
            var env: Environment? = this
            while (env != null) {
                if (env.definedHere(name)) { env.define(name, value); return }
                env = env.parent
            }
            define(name, value)
        }
    }

    private class UserFunction(val decl: Stmt.FnDecl)

    private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
    private class BreakSignal : RuntimeException(null, null, false, false)
    private class ContinueSignal : RuntimeException(null, null, false, false)

    private val globals = Environment(null)
    private val functions = HashMap<String, UserFunction>()

    fun run(program: List<Stmt>) {
        for ((name, value) in api.provideGlobals()) globals.define(name, value)
        // Hoist function declarations so order doesn't matter.
        for (stmt in program) if (stmt is Stmt.FnDecl) functions[stmt.name] = UserFunction(stmt)
        try {
            for (stmt in program) execute(stmt, globals)
        } catch (_: ReturnSignal) {
            // top-level return ends the script
        } catch (_: BreakSignal) {
            throw ScriptError("'break' outside of a loop")
        } catch (_: ContinueSignal) {
            throw ScriptError("'continue' outside of a loop")
        }
    }

    private fun execute(stmt: Stmt, env: Environment) {
        budget.tick()
        when (stmt) {
            is Stmt.ExprStmt -> evaluate(stmt.expr, env)
            is Stmt.Assign -> env.assign(stmt.name, evaluate(stmt.value, env))
            is Stmt.Block -> executeBlock(stmt.statements, Environment(env))
            is Stmt.FnDecl -> functions[stmt.name] = UserFunction(stmt)
            is Stmt.Return -> throw ReturnSignal(stmt.value?.let { evaluate(it, env) })
            is Stmt.Break -> throw BreakSignal()
            is Stmt.Continue -> throw ContinueSignal()
            is Stmt.If -> executeIf(stmt, env)
            is Stmt.While -> executeWhile(stmt, env)
            is Stmt.For -> executeFor(stmt, env)
        }
    }

    private fun executeBlock(statements: List<Stmt>, env: Environment) {
        for (s in statements) execute(s, env)
    }

    private fun executeIf(stmt: Stmt.If, env: Environment) {
        for ((cond, body) in stmt.branches) {
            if (Values.isTruthy(evaluate(cond, env))) {
                executeBlock(body.statements, Environment(env))
                return
            }
        }
        stmt.elseBranch?.let { executeBlock(it.statements, Environment(env)) }
    }

    private fun executeWhile(stmt: Stmt.While, env: Environment) {
        while (Values.isTruthy(evaluate(stmt.condition, env))) {
            budget.tick()
            try {
                executeBlock(stmt.body.statements, Environment(env))
            } catch (_: BreakSignal) {
                break
            } catch (_: ContinueSignal) {
                continue
            }
        }
    }

    private fun executeFor(stmt: Stmt.For, env: Environment) {
        val iterable = evaluate(stmt.iterable, env)
        if (iterable !is List<*>) {
            throw ScriptError("'for' expects a list or range, got ${Values.typeName(iterable)}", stmt.line)
        }
        for (item in iterable) {
            budget.tick()
            val loopScope = Environment(env)
            loopScope.define(stmt.varName, item)
            try {
                executeBlock(stmt.body.statements, loopScope)
            } catch (_: BreakSignal) {
                break
            } catch (_: ContinueSignal) {
                continue
            }
        }
    }

    private fun evaluate(expr: Expr, env: Environment): Any? = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.ListLiteral -> expr.elements.map { evaluate(it, env) }
        is Expr.Variable -> env.get(expr.name)
        is Expr.Unary -> evalUnary(expr, env)
        is Expr.Binary -> evalBinary(expr, env)
        is Expr.Logical -> evalLogical(expr, env)
        is Expr.Range -> evalRange(expr, env)
        is Expr.Index -> evalIndex(expr, env)
        is Expr.Member -> evalMember(expr, env)
        is Expr.Call -> evalCall(expr, env)
    }

    private fun evalUnary(expr: Expr.Unary, env: Environment): Any? {
        val v = evaluate(expr.operand, env)
        return when (expr.op) {
            TokenType.MINUS -> -Values.asNumber(v, expr.line)
            TokenType.NOT -> !Values.isTruthy(v)
            else -> throw ScriptError("bad unary operator", expr.line)
        }
    }

    private fun evalBinary(expr: Expr.Binary, env: Environment): Any? {
        val l = evaluate(expr.left, env)
        val r = evaluate(expr.right, env)
        return when (expr.op) {
            TokenType.PLUS ->
                if (l is String || r is String) Values.stringify(l) + Values.stringify(r)
                else Values.asNumber(l, expr.line) + Values.asNumber(r, expr.line)
            TokenType.MINUS -> Values.asNumber(l, expr.line) - Values.asNumber(r, expr.line)
            TokenType.STAR -> Values.asNumber(l, expr.line) * Values.asNumber(r, expr.line)
            TokenType.SLASH -> {
                val d = Values.asNumber(r, expr.line)
                if (d == 0.0) throw ScriptError("division by zero", expr.line)
                Values.asNumber(l, expr.line) / d
            }
            TokenType.PERCENT -> {
                val d = Values.asNumber(r, expr.line)
                if (d == 0.0) throw ScriptError("modulo by zero", expr.line)
                Values.asNumber(l, expr.line) % d
            }
            TokenType.EQ -> Values.equal(l, r)
            TokenType.NEQ -> !Values.equal(l, r)
            TokenType.LT -> Values.asNumber(l, expr.line) < Values.asNumber(r, expr.line)
            TokenType.LTE -> Values.asNumber(l, expr.line) <= Values.asNumber(r, expr.line)
            TokenType.GT -> Values.asNumber(l, expr.line) > Values.asNumber(r, expr.line)
            TokenType.GTE -> Values.asNumber(l, expr.line) >= Values.asNumber(r, expr.line)
            else -> throw ScriptError("bad binary operator", expr.line)
        }
    }

    private fun evalLogical(expr: Expr.Logical, env: Environment): Any? {
        val l = evaluate(expr.left, env)
        return when (expr.op) {
            TokenType.AND -> if (!Values.isTruthy(l)) l else evaluate(expr.right, env)
            TokenType.OR -> if (Values.isTruthy(l)) l else evaluate(expr.right, env)
            else -> throw ScriptError("bad logical operator", expr.line)
        }
    }

    private fun evalRange(expr: Expr.Range, env: Environment): Any? {
        val start = Values.asInt(evaluate(expr.start, env), expr.line)
        val end = Values.asInt(evaluate(expr.end, env), expr.line)
        if (end - start > 100_000) throw ScriptError("range too large", expr.line)
        val out = ArrayList<Any?>(maxOf(0, end - start))
        var i = start
        while (i < end) { out.add(i.toDouble()); i++ }
        return out
    }

    private fun evalIndex(expr: Expr.Index, env: Environment): Any? {
        val target = evaluate(expr.target, env)
        if (target !is List<*>) throw ScriptError("cannot index ${Values.typeName(target)}", expr.line)
        val i = Values.asInt(evaluate(expr.index, env), expr.line)
        if (i < 0 || i >= target.size) throw ScriptError("index $i out of range (size ${target.size})", expr.line)
        return target[i]
    }

    private fun evalMember(expr: Expr.Member, env: Environment): Any? {
        val target = evaluate(expr.target, env)
        return when (target) {
            null -> null
            is ScriptNode -> target.prop(expr.name)
            is Map<*, *> -> {
                if (!target.containsKey(expr.name)) throw ScriptError("unknown property '${expr.name}'", expr.line)
                target[expr.name]
            }
            else -> throw ScriptError("cannot read '.${expr.name}' on ${Values.typeName(target)}", expr.line)
        }
    }

    private fun evalCall(expr: Expr.Call, env: Environment): Any? {
        val callee = expr.callee
        val args = expr.args.map { evaluate(it, env) }
        val named = expr.named.mapValues { evaluate(it.value, env) }

        // Method call: obj.method(...)
        if (callee is Expr.Member) {
            val target = evaluate(callee.target, env)
            if (target == null) return null
            if (target is ScriptNode) return target.call(callee.name, args, named)
            throw ScriptError("cannot call '.${callee.name}()' on ${Values.typeName(target)}", expr.line)
        }

        if (callee !is Expr.Variable) throw ScriptError("expression is not callable", expr.line)

        functions[callee.name]?.let { return callUserFunction(it, args, expr.line) }
        return api.callFunction(callee.name, args, named)
    }

    private fun callUserFunction(fn: UserFunction, args: List<Any?>, line: Int): Any? {
        val decl = fn.decl
        if (args.size != decl.params.size) {
            throw ScriptError("${decl.name}() expects ${decl.params.size} arguments, got ${args.size}", line)
        }
        budget.enterCall()
        try {
            val scope = Environment(globals)
            for (i in decl.params.indices) scope.define(decl.params[i], args[i])
            return try {
                executeBlock(decl.body.statements, scope)
                null
            } catch (r: ReturnSignal) {
                r.value
            }
        } finally {
            budget.exitCall()
        }
    }
}
