package com.salahfocus.app.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

enum class Prayer(val ar: String) {
    FAJR("الفجر"), DHUHR("الظهر"), ASR("العصر"), MAGHRIB("المغرب"), ISHA("العشاء")
}

/** ishaMinutes > 0 means isha = maghrib + that many minutes (Umm al-Qura), otherwise ishaAngle is used. */
data class CalcMethod(val id: String, val ar: String, val fajrAngle: Double, val ishaAngle: Double, val ishaMinutes: Int)

val METHODS = listOf(
    CalcMethod("ummalqura", "أم القرى (مكة المكرمة)", 18.5, 0.0, 90),
    CalcMethod("mwl", "رابطة العالم الإسلامي", 18.0, 17.0, 0),
    CalcMethod("egypt", "الهيئة المصرية العامة للمساحة", 19.5, 17.5, 0),
    CalcMethod("karachi", "جامعة العلوم الإسلامية - كراتشي", 18.0, 18.0, 0),
    CalcMethod("isna", "أمريكا الشمالية (ISNA)", 15.0, 15.0, 0),
    CalcMethod("gulf", "الخليج", 19.5, 0.0, 90),
    CalcMethod("kuwait", "الكويت", 18.0, 17.5, 0),
    CalcMethod("qatar", "قطر", 18.0, 0.0, 90),
    CalcMethod("turkey", "تركيا (الديانت)", 18.0, 17.0, 0),
    CalcMethod("singapore", "سنغافورة وماليزيا وإندونيسيا", 20.0, 18.0, 0),
)

/** Offline solar-position based prayer times (same maths as the classic PrayTimes.org algorithm). */
object PrayerCalc {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun fixAngle(a: Double) = a - 360.0 * floor(a / 360.0)
    private fun fixHour(a: Double) = a - 24.0 * floor(a / 24.0)

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private class Sun(val decl: Double, val eqt: Double)

    private fun sun(jd: Double): Sun {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g)))
        val e = 23.439 - 0.00000036 * d
        val ra = fixHour(deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15.0)
        return Sun(deg(asin(sin(rad(e)) * sin(rad(l)))), q / 15.0 - ra)
    }

    private fun midDay(jd: Double, t: Double) = fixHour(12 - sun(jd + t).eqt)

    private fun angleTime(jd: Double, lat: Double, angle: Double, t: Double, ccw: Boolean): Double {
        val decl = sun(jd + t).decl
        val noon = midDay(jd, t)
        val term = (-sin(rad(angle)) - sin(rad(decl)) * sin(rad(lat))) / (cos(rad(decl)) * cos(rad(lat)))
        if (term < -1.0 || term > 1.0) return Double.NaN // the sun never reaches this angle that day
        val half = deg(acos(term)) / 15.0
        return noon + if (ccw) -half else half
    }

    private fun asrTime(jd: Double, lat: Double, factor: Int, t: Double): Double {
        val decl = sun(jd + t).decl
        val angle = -deg(atan(1.0 / (factor + tan(rad(abs(lat - decl))))))
        return angleTime(jd, lat, angle, t, false)
    }

    /** Returns the five prayer times of the given local date as epoch milliseconds. */
    fun compute(
        year: Int, month: Int, day: Int,
        lat: Double, lng: Double, tzId: String,
        method: CalcMethod, asrFactor: Int,
    ): Map<Prayer, Long> {
        val solar = hours(year, month, day, lat, lng, method, asrFactor)
            // Near the poles the sun may not reach the fajr/isha angle (or never set):
            // fall back to the nearest latitude where it does, the usual practice.
            ?: hours(year, month, day, 48.5 * if (lat < 0) -1 else 1, lng, method, asrFactor)
            ?: error("cannot compute prayer times")
        val tz = TimeZone.getTimeZone(tzId)
        val midnight = Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
        // The offset at noon keeps days that switch daylight saving time correct for most of the day.
        val shift = tz.getOffset(midnight + 12 * 3_600_000L) / 3_600_000.0 - lng / 15.0
        fun at(h: Double) = midnight + ((h + shift) * 3_600_000.0).toLong()
        return Prayer.values().associateWith { at(solar.getValue(it)) }
    }

    /** Prayer times as local solar hours, or null if one cannot be computed at this latitude. */
    private fun hours(
        year: Int, month: Int, day: Int,
        lat: Double, lng: Double,
        method: CalcMethod, asrFactor: Int,
    ): Map<Prayer, Double>? {
        val jd = julian(year, month, day) - lng / (15.0 * 24.0)
        var fajr = 5.0
        var dhuhr = 12.0
        var asr = 13.0
        var maghrib = 18.0
        var isha = 18.0
        repeat(2) {
            fajr = angleTime(jd, lat, method.fajrAngle, fajr / 24, true)
            dhuhr = midDay(jd, dhuhr / 24)
            asr = asrTime(jd, lat, asrFactor, asr / 24)
            maghrib = angleTime(jd, lat, 0.833, maghrib / 24, false)
            isha = if (method.ishaMinutes > 0) maghrib + method.ishaMinutes / 60.0
            else angleTime(jd, lat, method.ishaAngle, isha / 24, false)
        }

        val result = mapOf(
            Prayer.FAJR to fajr,
            Prayer.DHUHR to dhuhr,
            Prayer.ASR to asr,
            Prayer.MAGHRIB to maghrib,
            Prayer.ISHA to isha,
        )
        return if (result.values.any { it.isNaN() }) null else result
    }
}
