package neth.iecal.curbox.utils

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Carries one edit over to a different starting value.
 *
 * While a weakening change waits under the settings change delay, editors show the waiting
 * value, so a new edit is made on top of it. [merge] takes what that edit changed (from
 * [before] to [after]) and applies just that to [target], the value in force now. The gate
 * then checks the result with [RestrictionComparator]: when it is same or stricter, the edit
 * applies at once instead of waiting behind the earlier change.
 *
 * Nothing here decides strictness. A merge that misreads some data shape can only produce a
 * value the comparator rejects (the edit then waits, as before) or one that is still same or
 * stricter, and the waiting value, which holds the user's exact edit, lands at the deadline.
 *
 * Shapes:
 *  - objects merge key by key;
 *  - arrays of objects that all carry an "id" or "groupId" string are matched by it, so
 *    groups added, removed or edited carry over one by one;
 *  - arrays of strings (package names, keywords, list ids) are treated as sets;
 *  - anything else takes the edited value whole.
 */
object GatedEditMerge {

    private val ID_KEYS = listOf("id", "groupId")

    fun merge(before: JsonElement?, after: JsonElement?, target: JsonElement?): JsonElement? {
        if (before == after) return target
        if (after == null) return null
        if (before == null || target == null) return after.deepCopy()
        if (before.isJsonObject && after.isJsonObject && target.isJsonObject) {
            return mergeObjects(before.asJsonObject, after.asJsonObject, target.asJsonObject)
        }
        if (before.isJsonArray && after.isJsonArray && target.isJsonArray) {
            val b = before.asJsonArray
            val a = after.asJsonArray
            val t = target.asJsonArray
            if (allStrings(b) && allStrings(a) && allStrings(t)) return mergeStringSets(b, a, t)
            val key = ID_KEYS.firstOrNull { k -> listOf(b, a, t).all { hasIds(it, k) } }
            if (key != null) return mergeById(b, a, t, key)
        }
        return after.deepCopy()
    }

    private fun mergeObjects(before: JsonObject, after: JsonObject, target: JsonObject): JsonObject {
        val result = target.deepCopy()
        for (key in before.keySet() + after.keySet()) {
            val b = before.get(key)
            val a = after.get(key)
            if (b == a) continue
            val merged = merge(b, a, target.get(key))
            if (merged == null) result.remove(key) else result.add(key, merged)
        }
        return result
    }

    private fun mergeStringSets(before: JsonArray, after: JsonArray, target: JsonArray): JsonArray {
        val b = before.map { it.asString }.toSet()
        val a = after.map { it.asString }
        val removed = b - a.toSet()
        val kept = target.map { it.asString }.filter { it !in removed }
        val result = JsonArray()
        (kept + a.filter { it !in b }).distinct().forEach(result::add)
        return result
    }

    private fun mergeById(before: JsonArray, after: JsonArray, target: JsonArray, key: String): JsonArray {
        val b = before.associateBy { it.asJsonObject.get(key).asString }
        val a = after.associateBy { it.asJsonObject.get(key).asString }
        val result = JsonArray()
        val inTarget = HashSet<String>()
        for (item in target) {
            val id = item.asJsonObject.get(key).asString
            inTarget += id
            when {
                id in b && id !in a -> Unit // deleted by the edit
                id in b -> merge(b[id], a[id], item)?.let(result::add)
                else -> result.add(item.deepCopy())
            }
        }
        // Added by the edit.
        for ((id, item) in a) {
            if (id !in b && id !in inTarget) result.add(item.deepCopy())
        }
        return result
    }

    private fun allStrings(array: JsonArray) =
        array.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }

    private fun hasIds(array: JsonArray, key: String) = array.all {
        it.isJsonObject && it.asJsonObject.get(key)?.let { id ->
            id.isJsonPrimitive && id.asJsonPrimitive.isString
        } == true
    }
}
