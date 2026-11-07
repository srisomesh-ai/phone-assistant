package com.srisomesh.speed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("speed", Context.MODE_PRIVATE)

    var tab: Int
        get() = p.getInt("tab", 0)
        set(v) = p.edit().putInt("tab", v).apply()

    var mode: Mode
        get() = runCatching { Mode.valueOf(p.getString("mode", "ROAD")!!) }.getOrDefault(Mode.ROAD)
        set(v) = p.edit().putString("mode", v.name).apply()

    var unit: SpeedUnit
        get() = runCatching { SpeedUnit.valueOf(p.getString("unit", "KMH")!!) }.getOrDefault(SpeedUnit.KMH)
        set(v) = p.edit().putString("unit", v.name).apply()

    /** Speed-test history, newest first, max 20. */
    fun history(): List<JSONObject> {
        val a = JSONArray(p.getString("history", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun addHistory(o: JSONObject) {
        val list = listOf(o) + history()
        val a = JSONArray(); list.take(20).forEach { a.put(it) }
        p.edit().putString("history", a.toString()).apply()
    }

    fun clearHistory() = p.edit().remove("history").apply()
}
