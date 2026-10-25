package com.ibrahim.routermanager.data

import android.content.Context
import org.json.JSONObject

/** Custom device names and the set of devices the user has already seen (to flag new ones). */
class DeviceNotes(context: Context) {
    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)

    fun names(): Map<String, String> {
        val o = JSONObject(prefs.getString("names", "{}")!!)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    fun saveNames(names: Map<String, String>) {
        prefs.edit().putString("names", JSONObject(names).toString()).apply()
    }

    fun known(): Set<String> = prefs.getStringSet("known", emptySet())!!.toSet()

    fun saveKnown(keys: Set<String>) {
        prefs.edit().putStringSet("known", keys).apply()
    }
}
