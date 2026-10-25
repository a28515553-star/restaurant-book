package com.ibrahim.routermanager.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saves the router list in the app's private storage. */
class RouterStore(context: Context) {
    private val prefs = context.getSharedPreferences("routers", Context.MODE_PRIVATE)

    fun load(): List<RouterConfig> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RouterConfig(
                id = o.getString("id"),
                name = o.getString("name"),
                host = o.getString("host"),
                type = runCatching { RouterType.valueOf(o.getString("type")) }.getOrDefault(RouterType.SCAN),
                username = o.optString("username"),
                password = o.optString("password"),
            )
        }
    }

    fun save(routers: List<RouterConfig>) {
        val arr = JSONArray()
        routers.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("host", it.host)
                    .put("type", it.type.name).put("username", it.username).put("password", it.password)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}
