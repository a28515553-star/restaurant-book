package com.salahfocus.app.core

import android.content.Context
import java.util.Locale
import java.util.TimeZone

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

    /** Epoch millis until which a manual test block is active (0 = none). */
    var testUntil: Long
        get() = sp.getLong("testUntil", 0L)
        set(v) = sp.edit().putLong("testUntil", v).apply()

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
        get() = sp.getString("method", null) ?: defaultMethodFor(Locale.getDefault().country)
        set(v) = sp.edit().putString("method", v).apply()

    var asrFactor: Int
        get() = sp.getInt("asr", 1)
        set(v) = sp.edit().putInt("asr", v).apply()

    /** True once a city was picked or a location was found; until then times use a default place. */
    val hasLocation: Boolean get() = sp.getBoolean("hasLocation", false)

    /** When true the timezone always follows the phone, so travelling keeps times correct. */
    val autoLocation: Boolean get() = sp.getBoolean("auto", true)

    val placeName: String get() = sp.getString("place", DEFAULT_CITY.ar) ?: DEFAULT_CITY.ar

    val lat: Double get() = sp.getFloat("lat", DEFAULT_CITY.lat.toFloat()).toDouble()

    val lng: Double get() = sp.getFloat("lng", DEFAULT_CITY.lng.toFloat()).toDouble()

    val tzId: String
        get() = if (autoLocation && hasLocation) TimeZone.getDefault().id
        else sp.getString("tz", DEFAULT_CITY.tz) ?: DEFAULT_CITY.tz

    fun setCity(city: City) {
        sp.edit()
            .putString("place", city.ar).putFloat("lat", city.lat.toFloat()).putFloat("lng", city.lng.toFloat())
            .putString("tz", city.tz).putString("method", city.method)
            .putBoolean("auto", false).putBoolean("hasLocation", true)
            .apply()
    }

    fun setAutoLocation(lat: Double, lng: Double) {
        val edit = sp.edit()
            .putString("place", "موقعي الحالي").putFloat("lat", lat.toFloat()).putFloat("lng", lng.toFloat())
            .putString("tz", TimeZone.getDefault().id)
            .putBoolean("auto", true).putBoolean("hasLocation", true)
        if (!sp.getBoolean("methodManual", false)) edit.putString("method", defaultMethodFor(Locale.getDefault().country))
        edit.apply()
    }

    fun chooseMethod(id: String) {
        sp.edit().putString("method", id).putBoolean("methodManual", true).apply()
    }

    val method: CalcMethod get() = METHODS.firstOrNull { it.id == methodId } ?: METHODS[0]

    companion object {
        val DEFAULT_CITY = CITIES[0]

        /** Usual calculation method for the phone's country; falls back to the Muslim World League. */
        fun defaultMethodFor(country: String): String = when (country) {
            "SA" -> "ummalqura"
            "EG" -> "egypt"
            "KW" -> "kuwait"
            "QA" -> "qatar"
            "AE", "OM", "BH" -> "gulf"
            "TR" -> "turkey"
            "US", "CA" -> "isna"
            "PK", "IN", "BD", "AF" -> "karachi"
            "MY", "SG", "ID", "BN", "TH", "PH" -> "singapore"
            else -> "mwl"
        }
    }
}
