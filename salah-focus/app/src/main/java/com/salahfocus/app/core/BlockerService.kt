package com.salahfocus.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.salahfocus.app.BlockActivity
import com.salahfocus.app.ui.formatCountdown

/**
 * Watches which app is in the foreground. When a selected social app is open during a block
 * window it covers the screen with a prayer reminder. The cover is an accessibility overlay,
 * which phones allow even when apps may not open screens from the background.
 * The service never reads screen content.
 */
class BlockerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPackage: String? = null
    private var overlay: View? = null
    private var countdown: TextView? = null
    private var overlayEnd = 0L

    // Catches an app that was already open when the block window began (no new window event then).
    private val periodicCheck = object : Runnable {
        override fun run() {
            lastPackage?.let { check(it) }
            handler.postDelayed(this, 3000)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (overlay == null) return
            val left = overlayEnd - System.currentTimeMillis()
            if (left <= 0) {
                hideOverlay()
                return
            }
            countdown?.text = formatCountdown(left)
            handler.postDelayed(this, 500)
        }
    }

    override fun onServiceConnected() {
        connected = true
        handler.removeCallbacks(periodicCheck)
        handler.postDelayed(periodicCheck, 3000)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        hideOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacks(periodicCheck)
        hideOverlay()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        lastSeen = pkg
        // The notification shade and keyboard are drawn over the app without leaving it.
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        lastPackage = pkg
        // The user left the blocked app (home, recents, another app): take the cover down.
        if (overlay != null && pkg !in Prefs(this).blockedApps) hideOverlay()
        check(pkg)
    }

    private fun check(pkg: String) {
        val prefs = Prefs(this)
        if (!prefs.blockingEnabled) {
            lastDecision = "الحجب معطّل من الإعدادات"
            return
        }
        if (pkg !in prefs.blockedApps) {
            lastDecision = "$pkg ليس في قائمة الحجب"
            return
        }
        val active = Schedule.activeEvent(prefs, System.currentTimeMillis())
        if (active == null) {
            lastDecision = "$pkg في القائمة، لكن الوقت الآن خارج وقت الحجب"
            return
        }
        lastDecision = "تم حجب $pkg"
        if (showOverlay(active.label, active.end)) return
        // Fallback: open the full-screen reminder, and if the phone refuses, go to the home screen.
        lastDecision = "تم حجب $pkg (بالشاشة الاحتياطية)"
        runCatching {
            startActivity(
                Intent(this, BlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("label", active.label)
                    .putExtra("end", active.end),
            )
        }
        handler.postDelayed({
            if (lastPackage == pkg) performGlobalAction(GLOBAL_ACTION_HOME)
        }, 800)
    }

    private fun showOverlay(label: String, end: Long): Boolean {
        if (overlay != null) {
            overlayEnd = end
            return true
        }
        return runCatching {
            val dp = resources.displayMetrics.density
            fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
                this.text = s
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (16 * dp).toInt())
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            val gold = Color.rgb(0xE8, 0xC2, 0x6A)
            val count = text(formatCountdown(end - System.currentTimeMillis()), 56f, Color.WHITE)
            val button = Button(this).apply {
                this.text = "العودة للشاشة الرئيسية"
                setOnClickListener {
                    hideOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setBackgroundColor(Color.rgb(0x0F, 0x3D, 0x3E))
                setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
                addView(text(label, 30f, gold, bold = true))
                addView(text("برامج التواصل محجوبة الآن", 18f, Color.WHITE))
                addView(count)
                addView(text("حتى تُفتح من جديد", 14f, Color.argb(180, 255, 255, 255)))
                addView(text("﴿إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَّوْقُوتًا﴾", 20f, gold))
                addView(button)
            }
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE,
            )
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, params)
            overlay = root
            countdown = count
            overlayEnd = end
            handler.post(ticker)
        }.isSuccess
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        countdown = null
        handler.removeCallbacks(ticker)
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    override fun onInterrupt() {}

    companion object {
        /** For the diagnostics card in settings. */
        @Volatile var connected = false
        @Volatile var lastSeen: String? = null
        @Volatile var lastDecision: String? = null
    }
}
