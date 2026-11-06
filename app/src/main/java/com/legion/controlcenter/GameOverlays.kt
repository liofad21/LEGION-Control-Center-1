package com.legion.controlcenter

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

private fun overlayType() = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

/** يخلّي أي View تتسحب بالإصبع (بتحرّك الشباك نفسه). */
private fun makeDraggable(handle: View, wm: WindowManager, target: View, lp: WindowManager.LayoutParams) {
    var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
    handle.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
            MotionEvent.ACTION_MOVE -> {
                lp.x = (ox + (e.rawX - sx)).toInt(); lp.y = (oy + (e.rawY - sy)).toInt()
                try { wm.updateViewLayout(target, lp) } catch (_: Exception) {}
            }
        }
        true
    }
}

/** Floating Window: شريط أدوات عايم فوق أي تطبيق (هوم / رجوع / آخر التطبيقات / لقطة / صوت). اسحبه من الخط اللي فوق. */
class ToolboxOverlay(private val svc: ControlAccessibilityService, private val dev: DeviceActions, private val onGone: () -> Unit) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lp = WindowManager.LayoutParams(
        -2, -2, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = svc.dpi(16); y = svc.dpi(180) }
    private val root = LinearLayout(svc)
    var isShowing = false
        private set

    init {
        root.orientation = LinearLayout.VERTICAL
        root.background = Ui.glass(svc, 22f, true)
        root.setPadding(svc.dpi(6), svc.dpi(4), svc.dpi(6), svc.dpi(6))
        val grip = View(svc).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#88FF5A64")); cornerRadius = svc.dpf(3f)
            }
        }
        root.addView(grip, LinearLayout.LayoutParams(svc.dpi(34), svc.dpi(6)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = svc.dpi(4); bottomMargin = svc.dpi(6) })
        makeDraggable(root, wm, root, lp)

        fun glyphBtn(res: Int, f: () -> Unit) {
            val b = GlassButton(svc, GlassButton.Shape.FLAT, Glyphs.get(svc, res))
            b.setOnClickListener { f() }
            root.addView(b, LinearLayout.LayoutParams(svc.dpi(46), svc.dpi(44)))
        }
        fun textBtn(t: String, f: () -> Unit) {
            val b = GlassButton(svc, GlassButton.Shape.PILL, null, t)
            b.setOnClickListener { f() }
            root.addView(b, LinearLayout.LayoutParams(svc.dpi(46), svc.dpi(38)).apply { topMargin = svc.dpi(2) })
        }
        glyphBtn(R.drawable.g_home) { dev.globalAction("home") }
        glyphBtn(R.drawable.g_back) { dev.globalAction("back") }
        glyphBtn(R.drawable.g_recents) { dev.globalAction("recents") }
        glyphBtn(R.drawable.g_screenshot) { dev.globalAction("screenshot") }
        textBtn("Vol+") { dev.setVolume(dev.volume() + 1) }
        textBtn("Vol−") { dev.setVolume(dev.volume() - 1) }
        glyphBtn(R.drawable.g_close) { hide() }
    }

    fun show() { if (isShowing) return; wm.addView(root, lp); isShowing = true }
    fun hide() {
        if (isShowing) { try { wm.removeView(root) } catch (_: Exception) {}; isShowing = false }
        onGone()
    }
}

/** Macros: منقر تلقائي. حط الهدف على المكان، دوس Start وهيدوس عليه كل فترة. دوس Stop لإيقافه. */
class AutoTapOverlay(private val svc: ControlAccessibilityService, private val onGone: () -> Unit) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val size = svc.dpi(64)
    private val intervals = longArrayOf(100, 250, 500, 1000)
    private var iv = 1
    private var running = false
    private var added = false

    private val aimLp = WindowManager.LayoutParams(
        size, size, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private val barLp = WindowManager.LayoutParams(
        -2, -2, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = svc.dpi(24); y = svc.dpi(120) }

    private val aim = object : View(svc) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = svc.dpf(2.5f) }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = width / 2f - svc.dpf(6f)
            p.color = if (running) Color.parseColor("#FF4DFF9A") else Color.parseColor("#FFFF2A3A")
            c.drawCircle(cx, cy, r, p)
            c.drawLine(cx - r - svc.dpf(4f), cy, cx + r + svc.dpf(4f), cy, p)
            c.drawLine(cx, cy - r - svc.dpf(4f), cx, cy + r + svc.dpf(4f), p)
            p.style = Paint.Style.FILL; c.drawCircle(cx, cy, svc.dpf(3f), p); p.style = Paint.Style.STROKE
        }
    }
    private val bar = LinearLayout(svc)
    private val startBtn = GlassButton(svc, GlassButton.Shape.PILL, null, "Start")
    private val speedBtn = GlassButton(svc, GlassButton.Shape.PILL, null, "")

    private val tapper = object : Runnable {
        override fun run() {
            if (!running) return
            val x = aimLp.x + size / 2f; val y = aimLp.y + size / 2f
            val path = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
            svc.dispatchGesture(g, null, null)
            h.postDelayed(this, intervals[iv])
        }
    }

    init {
        val dm = svc.resources.displayMetrics
        aimLp.x = dm.widthPixels / 2 - size / 2; aimLp.y = dm.heightPixels / 2 - size / 2
        makeDraggable(aim, wm, aim, aimLp)

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = Ui.glass(svc, 22f, true)
        bar.setPadding(svc.dpi(8), svc.dpi(6), svc.dpi(8), svc.dpi(6))
        val grip = TextView(svc).apply { text = "⠿"; setTextColor(Color.parseColor("#FFFF8C94")); textSize = 20f; setPadding(svc.dpi(6), 0, svc.dpi(8), 0) }
        makeDraggable(grip, wm, bar, barLp)
        bar.addView(grip)
        startBtn.on = true
        startBtn.setOnClickListener { toggle() }
        speedBtn.setOnClickListener { iv = (iv + 1) % intervals.size; speedLabel() }
        val close = GlassButton(svc, GlassButton.Shape.PILL, null, "✕").apply { setOnClickListener { hide() } }
        bar.addView(startBtn, LinearLayout.LayoutParams(svc.dpi(84), svc.dpi(40)))
        bar.addView(speedBtn, LinearLayout.LayoutParams(svc.dpi(84), svc.dpi(40)).apply { marginStart = svc.dpi(6) })
        bar.addView(close, LinearLayout.LayoutParams(svc.dpi(44), svc.dpi(40)).apply { marginStart = svc.dpi(6) })
        speedLabel()
    }

    private fun speedLabel() {
        val t = "${intervals[iv]} ms"
        speedBtn.setLabel(t)
    }

    private fun toggle() {
        running = !running
        startBtn.setLabel(if (running) "Stop" else "Start")
        startBtn.on = !running
        // أثناء التشغيل الهدف مبيبقاش بياخد لمس عشان الضغطة توصل للتطبيق اللي تحته
        aimLp.flags = if (running) aimLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else aimLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(aim, aimLp) } catch (_: Exception) {}
        aim.invalidate()
        h.removeCallbacks(tapper)
        if (running) h.postDelayed(tapper, 300)
    }

    val isShowing get() = added

    fun show() {
        if (added) return
        wm.addView(aim, aimLp); wm.addView(bar, barLp); added = true
    }

    fun hide() {
        running = false; h.removeCallbacks(tapper)
        if (added) {
            try { wm.removeView(aim) } catch (_: Exception) {}
            try { wm.removeView(bar) } catch (_: Exception) {}
            added = false
        }
        onGone()
    }
}

/**
 * Key Mapping: بتربط زر في دراع (بلوتوث/USB) بنقطة على الشاشة. لما تدوس الزر الدراع بيدوس مكانها.
 * التسجيل: اضغط "إضافة" ← دوس زر الدراع ← دوس على المكان في الشاشة.
 */
class KeyMapper(private val svc: ControlAccessibilityService) {
    private class Map(val code: Int, val x: Float, val y: Float)

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = svc.getSharedPreferences("legion", Context.MODE_PRIVATE)
    private val h = Handler(Looper.getMainLooper())
    private var maps = ArrayList<Map>()
    private var capturing = false
    private var onKey: ((Int) -> Unit)? = null
    private var card: LinearLayout? = null
    private var catcher: View? = null
    private var info: TextView? = null
    private var toggleBtn: GlassButton? = null

    init { load() }

    val enabled get() = prefs.getBoolean("keymap_on", false)
    val isEditing get() = card != null

    private fun load() {
        maps.clear()
        try {
            val a = JSONArray(prefs.getString("keymap", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                maps.add(Map(o.getInt("c"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat()))
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val a = JSONArray()
        maps.forEach { a.put(JSONObject().put("c", it.code).put("x", it.x.toDouble()).put("y", it.y.toDouble())) }
        prefs.edit().putString("keymap", a.toString()).apply()
    }

    /** بتتنادى من onKeyEvent في الخدمة. true = استهلكنا الزر. */
    fun handleKey(e: KeyEvent): Boolean {
        if (capturing) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                capturing = false
                val cb = onKey; onKey = null
                h.post { cb?.invoke(e.keyCode) }
            }
            return true
        }
        if (!enabled) return false
        val m = maps.firstOrNull { it.code == e.keyCode } ?: return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) tap(m.x, m.y)
        return true
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        svc.dispatchGesture(g, null, null)
    }

    private fun keyName(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    private fun refreshInfo() {
        info?.text = if (maps.isEmpty()) "مفيش ربط لسه. اضغط \"إضافة\"."
        else maps.joinToString("\n") { "${keyName(it.code)}  ←  (${it.x.toInt()}, ${it.y.toInt()})" }
        toggleBtn?.setLabel(if (enabled) "الربط: شغّال" else "الربط: مقفول")
        toggleBtn?.on = enabled
    }

    fun openEditor() {
        if (card != null) return
        val c = LinearLayout(svc)
        c.orientation = LinearLayout.VERTICAL
        c.background = Ui.glass(svc, 24f, true)
        c.setPadding(svc.dpi(16), svc.dpi(14), svc.dpi(16), svc.dpi(14))
        c.layoutDirection = View.LAYOUT_DIRECTION_RTL
        c.addView(TextView(svc).apply { text = "Key Mapping — ربط زراير الدراع"; setTextColor(Color.WHITE); textSize = 16f })
        c.addView(TextView(svc).apply {
            text = "وصّل دراع بلوتوث/USB. اضغط إضافة، دوس الزر على الدراع، وبعدين دوس على المكان اللي عايزه يدوسه في الشاشة."
            setTextColor(Ui.TEXT_DIM); textSize = 12f; setPadding(0, svc.dpi(4), 0, svc.dpi(8))
        })
        val i = TextView(svc).apply { setTextColor(Color.parseColor("#FFFFD6D9")); textSize = 13f; maxLines = 8 }
        info = i
        c.addView(i)
        val row = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        fun btn(t: String, on: Boolean, f: () -> Unit): GlassButton {
            val b = GlassButton(svc, GlassButton.Shape.PILL, null, t); b.on = on; b.setOnClickListener { f() }
            row.addView(b, LinearLayout.LayoutParams(0, svc.dpi(40), 1f).apply { setMargins(svc.dpi(3), 0, svc.dpi(3), 0) })
            return b
        }
        btn("إضافة", true) { beginAdd() }
        btn("مسح الكل", false) { maps.clear(); save(); refreshInfo() }
        toggleBtn = btn("", false) { prefs.edit().putBoolean("keymap_on", !enabled).apply(); refreshInfo() }
        btn("إغلاق", false) { closeEditor() }
        c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = svc.dpi(10) })
        card = c
        refreshInfo()
        val dm = svc.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            minOf(svc.dpi(460), (dm.widthPixels * 0.92f).toInt()), -2, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = svc.dpi(60) }
        wm.addView(c, lp)
    }

    fun closeEditor() {
        capturing = false; onKey = null
        catcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }; catcher = null
        card?.let { try { wm.removeView(it) } catch (_: Exception) {} }; card = null
        info = null; toggleBtn = null
    }

    private fun beginAdd() {
        info?.text = "دوس دلوقتي على الزر اللي في الدراع…  (15 ثانية)"
        capturing = true
        onKey = { code -> placeTarget(code) }
        h.postDelayed({ if (capturing) { capturing = false; onKey = null; refreshInfo() } }, 15000)
    }

    private fun placeTarget(code: Int) {
        val v = object : View(svc) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = svc.dpf(18f); textAlign = Paint.Align.CENTER }
            override fun onDraw(c: Canvas) {
                c.drawText("دوس على المكان اللي الزر ${keyName(code)} يدوسه", width / 2f, height * 0.15f, p)
            }
        }
        v.setBackgroundColor(Color.parseColor("#88000000"))
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                maps.removeAll { it.code == code }
                maps.add(Map(code, e.rawX, e.rawY)); save()
                catcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }; catcher = null
                prefs.edit().putBoolean("keymap_on", true).apply()
                refreshInfo()
            }
            true
        }
        catcher = v
        val lp = WindowManager.LayoutParams(
            -1, -1, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, lp)
        // نرجّع كارت التحكم فوق الشبكة
        card?.let { c -> try { val l = c.layoutParams; wm.removeView(c); wm.addView(c, l) } catch (_: Exception) {} }
    }
}
