package com.salahfocus.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

object LocationHelper {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Stores the device's current position (anywhere in the world) in [prefs] and reports success.
     * Uses the last known fix, and asks for a fresh one on Android 11+ when there is none.
     */
    @SuppressLint("MissingPermission")
    fun refresh(context: Context, prefs: Prefs, onDone: (Boolean) -> Unit) {
        if (!hasPermission(context)) return onDone(false)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) {
            prefs.setAutoLocation(last.latitude, last.longitude)
            return onDone(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (provider != null) {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                    if (loc != null) prefs.setAutoLocation(loc.latitude, loc.longitude)
                    onDone(loc != null)
                }
                return
            }
        }
        onDone(false)
    }
}
