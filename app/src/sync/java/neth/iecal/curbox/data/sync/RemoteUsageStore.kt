package neth.iecal.curbox.data.sync

import android.content.Context
import java.io.File
import org.json.JSONObject

class RemoteUsageStore(context: Context) {
    private val file = File(context.filesDir, "sync_remote_usage_v2.json")
    private val records: HashMap<String, String> = load(context)
    private var dirty = false

    private fun load(context: Context): HashMap<String, String> {
        runCatching {
            val legacy = File(context.filesDir, "sync_remote_usage.json")
            if (legacy.exists()) legacy.delete()
        }
        if (!file.exists()) return HashMap()
        val loaded = try {
            val obj = JSONObject(file.readText())
            HashMap<String, String>().apply { obj.keys().forEach { put(it, obj.getString(it)) } }
        } catch (_: Exception) {
            HashMap()
        }
        pruneOld(loaded)
        return loaded
    }

    private fun pruneOld(map: HashMap<String, String>) {
        val cutoff = java.time.LocalDate.now().minusDays(14).toString()
        val stale = map.entries.filter { (_, json) ->
            runCatching { JSONObject(json).optString("date") < cutoff }.getOrDefault(false)
        }.map { it.key }
        if (stale.isNotEmpty()) {
            stale.forEach { map.remove(it) }
            dirty = true
        }
    }

    fun put(namespace: String, recordKey: String, payloadJson: String) {
        records["$namespace/$recordKey"] = payloadJson
        dirty = true
    }

    fun flush() {
        if (!dirty) return
        val obj = JSONObject()
        records.forEach { (k, v) -> obj.put(k, v) }
        file.writeText(obj.toString())
        dirty = false
    }

    fun clear() {
        records.clear()
        dirty = false
        runCatching { if (file.exists()) file.delete() }
    }

    fun appTotals(date: String, deviceIds: Set<String> = emptySet()): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((key, json) in records) {
            if (!key.startsWith("usage_app/")) continue
            if (deviceIds.isNotEmpty() && deviceIds.none { key.startsWith("usage_app/$it:") }) continue
            val o = JSONObject(json)
            if (o.optString("date") != date) continue
            val apps = o.optJSONObject("apps") ?: continue
            val keys = apps.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val ms = apps.optJSONObject(pkg)?.optLong("ms") ?: 0L
                out[pkg] = (out[pkg] ?: 0L) + ms
            }
        }
        return out
    }

    fun websiteTotals(date: String, deviceIds: Set<String> = emptySet()): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((key, json) in records) {
            if (!key.startsWith("usage_web/")) continue
            if (deviceIds.isNotEmpty() && deviceIds.none { key.startsWith("usage_web/$it:") }) continue
            val o = JSONObject(json)
            if (o.optString("date") != date) continue
            val domains = o.optJSONObject("domains") ?: continue
            val keys = domains.keys()
            while (keys.hasNext()) {
                val domain = keys.next()
                val ms = domains.optJSONObject(domain)?.optLong("ms") ?: 0L
                out[domain] = (out[domain] ?: 0L) + ms
            }
        }
        return out
    }
}
