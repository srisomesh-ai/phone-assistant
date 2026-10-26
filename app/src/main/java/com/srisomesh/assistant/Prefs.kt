package com.srisomesh.assistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** All settings live on the phone itself. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    var wakeName: String
        get() = p.getString("wakeName", "Bangru") ?: "Bangru"
        set(v) = p.edit().putString("wakeName", v.trim()).apply()

    /** Extra spellings of the wake phrase as the offline recogniser hears them (from "Teach wake word"). */
    var wakeVariants: Set<String>
        get() = p.getStringSet("wakeVariants", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("wakeVariants", v).apply()

    /** Language for commands after the wake word: "en-IN" or "te-IN". */
    var commandLang: String
        get() = p.getString("commandLang", "en-IN") ?: "en-IN"
        set(v) = p.edit().putString("commandLang", v).apply()

    /** Note of your home/office Wi-Fi names, for the Smart Lock "trusted place" reminder. */
    var trustedWifi: Set<String>
        get() = p.getStringSet("trustedWifi", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("trustedWifi", v).apply()

    var voskModelDir: String?
        get() = p.getString("voskModelDir", null)
        set(v) = p.edit().putString("voskModelDir", v).apply()

    var aiModelPath: String?
        get() = p.getString("aiModelPath", null)
        set(v) = p.edit().putString("aiModelPath", v).apply()

    var vivoSetupDone: Boolean
        get() = p.getBoolean("vivoSetupDone", false)
        set(v) = p.edit().putBoolean("vivoSetupDone", v).apply()

    var listeningWanted: Boolean
        get() = p.getBoolean("listeningWanted", false)
        set(v) = p.edit().putBoolean("listeningWanted", v).apply()

    /** Recent commands: list of [heard, result]. Newest first, max 10. */
    fun recent(): List<Pair<String, String>> {
        val arr = JSONArray(p.getString("recent", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONArray(it)
            o.getString(0) to o.getString(1)
        }
    }

    fun addRecent(heard: String, result: String) {
        val list = listOf(heard to result) + recent()
        val arr = JSONArray()
        list.take(10).forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        p.edit().putString("recent", arr.toString()).apply()
    }
}
