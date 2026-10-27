package com.salahfocus.app.core

import android.content.Context

val DEFAULT_SOCIAL_APPS = setOf(
    "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.facebook.mlite",
    "com.instagram.android", "com.twitter.android", "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill", "com.snapchat.android", "com.whatsapp", "com.whatsapp.w4b",
    "org.telegram.messenger", "com.google.android.youtube", "com.pinterest",
    "com.reddit.frontpage", "com.discord", "com.linkedin.android", "com.tumblr",
)

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("salah", Context.MODE_PRIVATE)

    var blockingEnabled: Boolean
        get() = sp.getBoolean("blocking", true)
        set(v) = sp.edit().putBoolean("blocking", v).apply()

    var remindersEnabled: Boolean
        get() = sp.getBoolean("reminders", true)
        set(v) = sp.edit().putBoolean("reminders", v).apply()

    var minutesBefore: Int
        get() = sp.getInt("before", 10)
        set(v) = sp.edit().putInt("before", v).apply()

    var minutesAfter: Int
        get() = sp.getInt("after", 10)
        set(v) = sp.edit().putInt("after", v).apply()

    var blockedApps: Set<String>
        get() = sp.getStringSet("apps", null)?.toSet() ?: DEFAULT_SOCIAL_APPS
        set(v) = sp.edit().putStringSet("apps", v).apply()

    var methodId: String
        get() = sp.getString("method", "ummalqura") ?: "ummalqura"
        set(v) = sp.edit().putString("method", v).apply()

    var asrFactor: Int
        get() = sp.getInt("asr", 1)
        set(v) = sp.edit().putInt("asr", v).apply()

    var placeName: String
        get() = sp.getString("place", CITIES[2].ar) ?: CITIES[2].ar
        private set(v) = sp.edit().putString("place", v).apply()

    var lat: Double
        get() = sp.getFloat("lat", CITIES[2].lat.toFloat()).toDouble()
        private set(v) = sp.edit().putFloat("lat", v.toFloat()).apply()

    var lng: Double
        get() = sp.getFloat("lng", CITIES[2].lng.toFloat()).toDouble()
        private set(v) = sp.edit().putFloat("lng", v.toFloat()).apply()

    var tzId: String
        get() = sp.getString("tz", CITIES[2].tz) ?: CITIES[2].tz
        private set(v) = sp.edit().putString("tz", v).apply()

    fun setPlace(name: String, lat: Double, lng: Double, tzId: String) {
        this.placeName = name
        this.lat = lat
        this.lng = lng
        this.tzId = tzId
    }

    val method: CalcMethod get() = METHODS.firstOrNull { it.id == methodId } ?: METHODS[0]
}
