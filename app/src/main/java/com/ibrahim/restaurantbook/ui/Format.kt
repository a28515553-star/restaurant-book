package com.ibrahim.restaurantbook.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val arabic: Locale = Locale.forLanguageTag("ar")

// Western digits keep amounts easy to read next to receipts.
private val amountFormat = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))
private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", arabic)
private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", arabic)
private val shortDayFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", arabic)

fun formatAmount(value: Double): String = amountFormat.format(value)

fun formatMonth(month: YearMonth): String = month.format(monthFormat)

fun formatDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dayFormat)

fun formatDate(date: LocalDate): String = date.format(shortDayFormat)

/** Accepts Arabic-Indic digits and separators as typed on Arabic keyboards. */
fun parseAmount(text: String): Double? {
    val normalized = buildString {
        for (ch in text.trim()) {
            append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '٫' -> '.'
                    '٬', ',', ' ' -> null
                    else -> ch
                } ?: continue
            )
        }
    }
    return normalized.toDoubleOrNull()?.takeIf { it > 0 }
}

fun Double.toInputText(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
