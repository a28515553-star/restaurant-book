package com.salahfocus.app.core

import java.util.Calendar
import java.util.TimeZone

/** One prayer with the window in which social apps are blocked: [start, end). */
data class PrayerEvent(val prayer: Prayer, val time: Long, val start: Long, val end: Long)

enum class AlertType { START, TIME, END }

data class Alert(val type: AlertType, val event: PrayerEvent, val at: Long)

object Schedule {
    private const val MIN = 60_000L

    /** Prayer events for yesterday .. day after tomorrow, sorted by time. */
    fun events(prefs: Prefs, now: Long): List<PrayerEvent> {
        val tz = TimeZone.getTimeZone(prefs.tzId)
        val result = mutableListOf<PrayerEvent>()
        for (offset in -1..2) {
            val cal = Calendar.getInstance(tz).apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val times = PrayerCalc.compute(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                prefs.lat, prefs.lng, prefs.tzId, prefs.method, prefs.asrFactor,
            )
            for ((p, t) in times) {
                result += PrayerEvent(p, t, t - prefs.minutesBefore * MIN, t + prefs.minutesAfter * MIN)
            }
        }
        return result.sortedBy { it.time }
    }

    /** The event whose block window contains [now], if any. */
    fun activeEvent(prefs: Prefs, now: Long): PrayerEvent? =
        events(prefs, now).firstOrNull { now >= it.start && now < it.end }

    /** First prayer whose time is still ahead of [now]. */
    fun nextPrayer(prefs: Prefs, now: Long): PrayerEvent? =
        events(prefs, now).firstOrNull { it.time > now }

    /** Today's five prayers, in the timezone of the chosen place. */
    fun today(prefs: Prefs, now: Long): List<PrayerEvent> {
        val tz = TimeZone.getTimeZone(prefs.tzId)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        return events(prefs, now).filter {
            val c = Calendar.getInstance(tz).apply { timeInMillis = it.time }
            c.get(Calendar.DAY_OF_YEAR) == day && c.get(Calendar.YEAR) == year
        }
    }

    /** Next reminder (start of block, prayer time, end of block) strictly after [now]. */
    fun nextAlert(prefs: Prefs, now: Long): Alert? =
        events(prefs, now).flatMap {
            listOf(Alert(AlertType.START, it, it.start), Alert(AlertType.TIME, it, it.time), Alert(AlertType.END, it, it.end))
        }.filter { it.at > now }.minByOrNull { it.at }
}
