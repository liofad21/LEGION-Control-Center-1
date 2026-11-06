package com.legion.controlcenter

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * ترجمة واجهة التطبيقات الصينية لإنجليزي "في مكانها": بنقرا نصوص الشاشة من إمكانية الوصول، بنترجمها بـ Gemini (مع كاش دائم)،
 * وبنرسم الإنجليزي فوق النص الأصلي بنفس لون الخلفية (بنقيسه من لقطة الشاشة) فيبان إن التطبيق أصلًا إنجليزي. مفيش فقاعات.
 * حدود: التطبيقات اللي بترسم الواجهة بنفسها (ألعاب/Canvas/Flutter بدون semantics) مبتكشفش نصوص، ومحتاج Android 11+ للقياس الدقيق للألوان.
 */
class TranslationOverlay(private val svc: ControlAccessibilityService) {

    private class Raw(val rect: Rect, val text: String)
    private class Item(val rect: Rect, val text: String, val bg: Int, val fg: Int)

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val cjk = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")
    private val sp = svc.getSharedPreferences("legion_tr", Context.MODE_PRIVATE)
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 3000
    }
    private var items: List<Item> = emptyList()
    private var added = false
    private var enabled = false
    private var busy = false
    private var lastRun = 0L
    private var saveQueued = false

    private val view = object : View(svc) {
        private val bgP = Paint()
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        override fun onDraw(c: Canvas) {
            for (it in items) {
                val r = it.rect
                bgP.color = it.bg
                c.drawRect(r.left - 2f, r.top - 1f, r.right + 2f, r.bottom + 1f, bgP)
                tp.color = it.fg
                val w = max(1, r.width())
                var size = (r.height() * 0.62f).coerceIn(svc.dpf(9f), svc.dpf(22f))
                var layout: StaticLayout
                while (true) {
                    tp.textSize = size
                    layout = StaticLayout.Builder.obtain(it.text, 0, it.text.length, tp, w)
                        .setAlignment(if (w < svc.dpi(200)) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false).build()
                    if (layout.height <= r.height() + svc.dpi(2) || size <= svc.dpf(8f)) break
                    size -= svc.dpf(1f)
                }
                c.save(); c.translate(r.left.toFloat(), r.top + (r.height() - layout.height) / 2f)
                layout.draw(c); c.restore()
            }
        }
    }

    init {
        try {
            val o = JSONObject(sp.getString("c", "{}") ?: "{}")
            for (k in o.keys()) cache[k] = o.getString(k)
        } catch (_: Exception) {}
    }

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            val lp = WindowManager.LayoutParams(
                -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            if (android.os.Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (!added) { wm.addView(view, lp); added = true }
            schedule(100)
        } else {
            items = emptyList(); view.invalidate()
            if (added) { try { wm.removeView(view) } catch (_: Exception) {}; added = false }
        }
    }

    private val run = Runnable { refresh() }
    fun schedule(delay: Long = 450) { if (!enabled) return; ui.removeCallbacks(run); ui.postDelayed(run, delay) }
    fun onScroll() { if (!enabled) return; view.alpha = 0f; schedule(500) }

    fun suspendDraw(s: Boolean) { view.alpha = if (s) 0f else 1f }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Raw>, depth: Int = 0) {
        if (n == null || depth > 45 || out.size >= 150) return
        if (!n.isVisibleToUser) return
        val t = (n.text ?: n.contentDescription)?.toString()
        if (!t.isNullOrBlank() && cjk.containsMatchIn(t) && t.length <= 300) {
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.width() > 8 && r.height() > 8) out.add(Raw(r, t.trim()))
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i)
            collect(c, out, depth + 1)
        }
    }

    private fun refresh() {
        if (!enabled || busy) return
        val now = System.currentTimeMillis()
        if (now - lastRun < 600) { schedule(700 - (now - lastRun)); return }
        val root = svc.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == svc.packageName || pkg == "com.android.systemui") { items = emptyList(); view.invalidate(); view.alpha = 1f; return }
        val raws = ArrayList<Raw>()
        collect(root, raws)
        lastRun = now
        if (raws.isEmpty()) { items = emptyList(); view.alpha = 1f; view.invalidate(); return }
        val missing = raws.map { it.text }.filter { !cache.containsKey(it) }.distinct()
        busy = true
        thread {
            try {
                for (chunk in missing.chunked(60)) {
                    val tr = svc.gemini.translate(chunk)
                    ui.post { chunk.forEachIndexed { i, s -> cache[s] = tr[i] } }
                    Thread.sleep(50)
                }
                if (missing.isNotEmpty()) ui.post { queueSave() }
            } catch (_: Exception) { /* لو فشلت الترجمة نرسم اللي في الكاش بس */ }
            ui.post { paint(raws) }
        }
    }

    private fun queueSave() {
        if (saveQueued) return
        saveQueued = true
        ui.postDelayed({
            saveQueued = false
            val o = JSONObject(); cache.entries.forEach { o.put(it.key, it.value) }
            sp.edit().putString("c", o.toString()).apply()
        }, 4000)
    }

    private fun paint(raws: List<Raw>) {
        val translated = raws.filter { cache.containsKey(it.text) && cache[it.text] != it.text }
        if (translated.isEmpty()) { busy = false; items = emptyList(); view.alpha = 1f; view.invalidate(); return }
        // بنخفي الرسم لحظة عشان لقطة الشاشة تاخد ألوان التطبيق الأصلية
        view.alpha = 0f
        ui.postDelayed({
            svc.takeScreenshotBitmap { bmp ->
                val list = translated.map { r ->
                    val bg = if (bmp != null) sampleBg(bmp, r.rect) else Color.parseColor("#FAFAFA")
                    val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255
                    Item(r.rect, cache[r.text] ?: r.text, bg, if (lum < 0.5) Color.parseColor("#F2F2F2") else Color.parseColor("#1B1B1B"))
                }
                items = list
                view.alpha = 1f; view.invalidate()
                busy = false
                bmp?.recycle()
            }
        }, 70)
    }

    private fun sampleBg(b: Bitmap, r: Rect): Int {
        fun px(x: Int, y: Int) = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
        val cy = r.centerY(); val cx = r.centerX()
        val pts = listOf(px(r.left - 4, cy), px(r.right + 4, cy), px(cx, r.top - 3), px(cx, r.bottom + 3), px(r.left - 4, r.top - 3), px(r.right + 4, r.bottom + 3))
        // اللون الأكتر تكرارًا بين النقط (أقرب نسبة)
        var best = pts[0]; var bestScore = -1
        for (p in pts) {
            val score = pts.count { q -> Math.abs(Color.red(p) - Color.red(q)) + Math.abs(Color.green(p) - Color.green(q)) + Math.abs(Color.blue(p) - Color.blue(q)) < 36 }
            if (score > bestScore) { best = p; bestScore = score }
        }
        return best or (0xFF shl 24)
    }
}
