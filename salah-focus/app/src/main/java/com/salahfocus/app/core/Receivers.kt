package com.salahfocus.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type")?.let { runCatching { AlertType.valueOf(it) }.getOrNull() }
        val prayer = intent.getStringExtra("prayer")?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
        if (type != null && prayer != null) Notifier.show(context, type, prayer)
        Notifier.reschedule(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.reschedule(context)
    }
}
