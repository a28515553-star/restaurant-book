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
)

data class City(val ar: String, val lat: Double, val lng: Double, val tz: String)

val CITIES = listOf(
    City("مكة المكرمة", 21.4225, 39.8262, "Asia/Riyadh"),
    City("المدينة المنورة", 24.4686, 39.6142, "Asia/Riyadh"),
    City("الرياض", 24.7136, 46.6753, "Asia/Riyadh"),
    City("جدة", 21.5433, 39.1728, "Asia/Riyadh"),
    City("الدمام", 26.4207, 50.0888, "Asia/Riyadh"),
    City("أبها", 18.2164, 42.5053, "Asia/Riyadh"),
    City("القاهرة", 30.0444, 31.2357, "Africa/Cairo"),
    City("الإسكندرية", 31.2001, 29.9187, "Africa/Cairo"),
    City("عمّان", 31.9454, 35.9284, "Asia/Amman"),
    City("القدس", 31.7683, 35.2137, "Asia/Jerusalem"),
    City("دمشق", 33.5138, 36.2765, "Asia/Damascus"),
    City("بيروت", 33.8938, 35.5018, "Asia/Beirut"),
    City("بغداد", 33.3152, 44.3661, "Asia/Baghdad"),
    City("الكويت", 29.3759, 47.9774, "Asia/Kuwait"),
    City("المنامة", 26.2285, 50.5860, "Asia/Bahrain"),
    City("الدوحة", 25.2854, 51.5310, "Asia/Qatar"),
    City("دبي", 25.2048, 55.2708, "Asia/Dubai"),
    City("أبوظبي", 24.4539, 54.3773, "Asia/Dubai"),
    City("مسقط", 23.5880, 58.3829, "Asia/Muscat"),
    City("صنعاء", 15.3694, 44.1910, "Asia/Aden"),
    City("الخرطوم", 15.5007, 32.5599, "Africa/Khartoum"),
    City("طرابلس", 32.8872, 13.1913, "Africa/Tripoli"),
    City("تونس", 36.8065, 10.1815, "Africa/Tunis"),
    City("الجزائر", 36.7538, 3.0588, "Africa/Algiers"),
    City("الرباط", 34.0209, -6.8416, "Africa/Casablanca"),
    City("إسطنبول", 41.0082, 28.9784, "Europe/Istanbul"),
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
        val half = deg(acos(term.coerceIn(-1.0, 1.0))) / 15.0
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

        val tz = TimeZone.getTimeZone(tzId)
        val midnight = Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
        val shift = tz.getOffset(midnight) / 3_600_000.0 - lng / 15.0
        fun at(h: Double) = midnight + ((h + shift) * 3_600_000.0).toLong()

        return mapOf(
            Prayer.FAJR to at(fajr),
            Prayer.DHUHR to at(dhuhr),
            Prayer.ASR to at(asr),
            Prayer.MAGHRIB to at(maghrib),
            Prayer.ISHA to at(isha),
        )
    }
}
