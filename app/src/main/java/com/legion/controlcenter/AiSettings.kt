package com.legion.controlcenter

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** إعدادات الـ AI: لغة الترجمة، لغة التطبيق الأصلي، وأنهي تطبيقات تتترجم. بتتفتح من الفقاعة أو من التطبيق. */
class AiSettingsOverlay(private val svc: ControlAccessibilityService, private val onGone: () -> Unit) {
    companion object {
        val TARGETS = listOf("English" to "English", "العربية" to "Arabic", "Français" to "French", "Español" to "Spanish",
            "Deutsch" to "German", "Türkçe" to "Turkish", "Русский" to "Russian")
        val SOURCES = listOf("صيني" to "zh", "ياباني" to "ja", "كوري" to "ko", "الكل" to "all")
    }

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = svc.dev.prefs
    private var root: View? = null
    val isShowing get() = root != null

    private fun label(t: String, size: Float = 14f, color: Int = Color.WHITE) =
        TextView(svc).apply { text = t; textSize = size; setTextColor(color) }

    private fun LinearLayout.add(v: View, top: Int = 0, h: Int = -2) = addView(v, LinearLayout.LayoutParams(-1, h).apply { topMargin = svc.dpi(top) })

    private fun chips(items: List<Pair<String, String>>, current: () -> String, set: (String) -> Unit): View {
        val rowWrap = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        var row = LinearLayout(svc)
        val btns = ArrayList<Pair<GlassButton, String>>()
        fun refresh() { btns.forEach { (b, v) -> b.on = v == current() } }
        items.forEachIndexed { i, (ar, v) ->
            if (i % 4 == 0) { row = LinearLayout(svc); rowWrap.addView(row, LinearLayout.LayoutParams(-1, svc.dpi(40)).apply { topMargin = svc.dpi(4) }) }
            val b = GlassButton(svc, GlassButton.Shape.PILL, null, ar)
            b.setOnClickListener { set(v); refresh() }
            btns += b to v
            row.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = svc.dpi(3); marginEnd = svc.dpi(3) })
        }
        refresh()
        return rowWrap
    }

    private fun sw(text: String, pref: String, def: Boolean, after: (Boolean) -> Unit = {}) = CheckBox(svc).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 14f; isChecked = prefs.getBoolean(pref, def)
        setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(pref, v).apply(); after(v) }
    }

    fun show() {
        if (root != null) return
        val dm = svc.resources.displayMetrics
        val w = minOf(svc.dpi(460), (dm.widthPixels * 0.94f).toInt())
        val h = (dm.heightPixels * 0.86f).toInt()
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(svc.dpi(14), svc.dpi(10), svc.dpi(14), svc.dpi(14))
        }

        col.add(label("الترجمة التلقائية للتطبيقات", 16f), 4)
        col.add(sw("تشغيل الترجمة", "translate_on", false) { svc.setTranslation(it) }, 4)

        col.add(label("ترجم إلى:", 13f, Ui.TEXT_DIM), 8)
        col.add(chips(TARGETS, { prefs.getString("translate_lang", "English") ?: "English" }) { v ->
            prefs.edit().putString("translate_lang", v).apply(); svc.translationReload()
        })
        col.add(label("لغة التطبيق الأصلية:", 13f, Ui.TEXT_DIM), 8)
        col.add(chips(SOURCES, { prefs.getString("translate_src", "zh") ?: "zh" }) { v ->
            prefs.edit().putString("translate_src", v).apply(); svc.translationReload()
        })

        col.add(label("التطبيقات:", 13f, Ui.TEXT_DIM), 8)
        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        fun selected(): MutableSet<String> = HashSet(prefs.getStringSet("translate_apps", emptySet()) ?: emptySet())
        fun fillList() {
            list.removeAllViews()
            if (prefs.getBoolean("translate_all", true)) return
            val pm = svc.packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(q, 0).filter { it.activityInfo.packageName != svc.packageName }
                .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString().lowercase() }
            val sel = selected()
            for (a in apps) {
                val pkg = a.activityInfo.packageName
                val r = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
                r.addView(ImageView(svc).apply { setImageDrawable(a.loadIcon(pm)) }, LinearLayout.LayoutParams(svc.dpi(30), svc.dpi(30)))
                r.addView(CheckBox(svc).apply {
                    text = a.loadLabel(pm).toString(); setTextColor(Color.WHITE); textSize = 14f; isChecked = pkg in sel
                    setOnCheckedChangeListener { _, v ->
                        val s = selected(); if (v) s.add(pkg) else s.remove(pkg)
                        prefs.edit().putStringSet("translate_apps", s).apply(); svc.translationReload()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = svc.dpi(8) })
                list.addView(r, LinearLayout.LayoutParams(-1, svc.dpi(42)))
            }
        }
        col.add(sw("كل التطبيقات (الصينية فقط تتترجم)", "translate_all", true) { fillList(); svc.translationReload() }, 2)
        col.add(label("لو قفلتها اختار التطبيقات اللي عايزها تتترجم بس:", 12f, Ui.TEXT_DIM), 0)
        col.add(list, 4)
        fillList()

        col.add(label("المساعدة", 16f), 14)
        col.add(sw("Raven ترد بصوتها", "voice_replies", true), 4)
        col.add(sw("إدارة الحرارة التلقائية", "auto_thermal", true), 2)
        col.add(sw("لمس الشاشة 3 مرات يظهر Raven", "triple_on", true), 2)

        val close = GlassButton(svc, GlassButton.Shape.PILL, null, "تم").apply { on = true; setOnClickListener { hide() } }
        col.add(close, 14, svc.dpi(44))

        val sv = ScrollView(svc).apply {
            isVerticalScrollBarEnabled = false
            background = NeonDrawable(svc, 22f, 0xF2)
            addView(col)
        }
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        root = sv
        wm.addView(sv, lp)
    }

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        onGone()
    }
}
