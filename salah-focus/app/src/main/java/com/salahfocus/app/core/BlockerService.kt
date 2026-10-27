package com.salahfocus.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.salahfocus.app.BlockActivity

/**
 * Watches which app comes to the foreground. When a selected social app opens during a block
 * window it shows [BlockActivity] on top of it. It never reads screen content.
 */
class BlockerService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val prefs = Prefs(this)
        if (!prefs.blockingEnabled || pkg !in prefs.blockedApps) return
        val active = Schedule.activeEvent(prefs, System.currentTimeMillis()) ?: return
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("prayer", active.prayer.name)
                .putExtra("end", active.end),
        )
    }

    override fun onInterrupt() {}
}
