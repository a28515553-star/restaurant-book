package com.salahfocus.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun formatTime(millis: Long, tzId: String): String =
    SimpleDateFormat("h:mm a", Locale("ar")).apply { timeZone = TimeZone.getTimeZone(tzId) }.format(Date(millis))

fun formatCountdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
