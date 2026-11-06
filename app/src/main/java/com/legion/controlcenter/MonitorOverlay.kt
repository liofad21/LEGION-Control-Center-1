package com.legion.controlcenter

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/** عدّاد عايم فوق أي تطبيق: رام وحرارة وبطارية ومعدل التحديث. اسحبه للتحريك، وإضغط عليه لقفله. */
class MonitorOverlay(private val svc: ControlAccessibilityService, private val onGone: () -> Unit) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val tv = TextView(svc)
    private var added = false
    private val lp = WindowManager.LayoutParams(
        -2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = svc.dpi(24); y = svc.dpi(56) }

    private val tick = object : Runnable {
        override fun run() { update(); h.postDelayed(this, 1000) }
    }

    init {
        tv.apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.MONOSPACE
            background = Ui.glass(svc, 14f, true)
            setPadding(svc.dpi(12), svc.dpi(8), svc.dpi(12), svc.dpi(8))
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - sx) > 10 || Math.abs(e.rawY - sy) > 10) moved = true
                        lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP -> if (!moved) hide()
                }
                true
            }
        }
    }

    private fun update() {
        val i: Intent? = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val t = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val mi = ActivityManager.MemoryInfo()
        (svc.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        @Suppress("DEPRECATION")
        val hz = wm.defaultDisplay.refreshRate
        tv.text = "RAM  %.1f/%.1f GB\nTEMP %.1f°C  BAT %d%%\nDISP %.0f Hz".format(
            (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9, t, lvl, hz)
    }

    fun show() { if (added) return; update(); wm.addView(tv, lp); added = true; h.postDelayed(tick, 1000) }
    fun hide() {
        h.removeCallbacks(tick)
        if (added) { try { wm.removeView(tv) } catch (_: Exception) {}; added = false }
        onGone()
    }
    val isShowing get() = added
}
