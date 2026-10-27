package com.salahfocus.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.salahfocus.app.BlockActivity

/**
 * Watches which app is in the foreground. When a selected social app is open during a block
 * window it shows [BlockActivity] on top of it. It never reads screen content.
 */
class BlockerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPackage: String? = null

    // Catches an app that was already open when the block window began (no new window event then).
    private val periodicCheck = object : Runnable {
        override fun run() {
            lastPackage?.let { check(it) }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onServiceConnected() {
        connected = true
        handler.removeCallbacks(periodicCheck)
        handler.postDelayed(periodicCheck, 3000)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacks(periodicCheck)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        lastSeen = pkg
        // The notification shade and keyboard are drawn over the app without leaving it.
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        lastPackage = pkg
        check(pkg)
    }

    private fun check(pkg: String) {
        val prefs = Prefs(this)
        if (!prefs.blockingEnabled || pkg !in prefs.blockedApps) return
        val active = Schedule.activeEvent(prefs, System.currentTimeMillis()) ?: return
        runCatching {
            startActivity(
                Intent(this, BlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("label", active.label)
                    .putExtra("end", active.end),
            )
        }
        // Some phones refuse to open screens from the background. If the blocked app is still
        // in front a moment later, send the user to the home screen instead.
        handler.postDelayed({
            if (lastPackage == pkg) performGlobalAction(GLOBAL_ACTION_HOME)
        }, 800)
    }

    override fun onInterrupt() {}

    companion object {
        /** For the diagnostics card in settings. */
        @Volatile var connected = false
        @Volatile var lastSeen: String? = null
    }
}
