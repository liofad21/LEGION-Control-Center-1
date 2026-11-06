package com.legion.controlcenter

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * لوحة Legion Control Center بالتصميم الجديد: كل الأقسام مرسومة بالكود (نيون أحمر) وكل رقم فيها قراءة حقيقية من النظام.
 * طولي: عمود واحد زي الصورة. عرضي: البنت على الشمال كاملة من فوق لتحت وفوق اللوحة، والأقسام في عمودين.
 */
class ControlPanel(
    private val svc: ControlAccessibilityService,
    private val dev: DeviceActions,
    animateIn: Boolean,
    private val onClose: () -> Unit
) : FrameLayout(svc) {

    private val ui = Handler(Looper.getMainLooper())
    private val stats = dev.stats
    private val refreshers = ArrayList<() -> Unit>()       // بتتنفذ كل ثانية
    private val slowRefreshers = ArrayList<() -> Unit>()   // بعد كل ضغطة وكل ثانيتين
    private val perfBtns = ArrayList<GlassButton>()
    private var tickN = 0
    private var disposed = false

    private val dm = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }
    private val land = dm.widthPixels > dm.heightPixels
    private fun d(v: Int) = svc.dpi(v)
    private val fb: (Intent) -> Unit = { openIntent(it) }

    private val tick = object : Runnable {
        override fun run() {
            if (disposed) return
            refreshers.forEach { try { it() } catch (_: Exception) {} }
            if (tickN++ % 2 == 0) slowRefreshers.forEach { try { it() } catch (_: Exception) {} }
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- أدوات بناء ----------
    private fun tv(t: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(svc).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
    }

    private fun glyph(res: Int, dp: Int, tint: Int = Color.WHITE) = ImageView(svc).apply {
        setImageBitmap(Glyphs.get(svc, res)); setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(d(dp), d(dp))
    }

    private fun neon(): LinearLayout = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = NeonDrawable(svc)
        setPadding(d(14), d(11), d(14), d(12))
    }

    private fun title(res: Int, t: String): View = LinearLayout(svc).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
        addView(glyph(res, 22)); addView(tv(t, 15f, true).apply { setPadding(d(8), 0, 0, 0) })
    }

    private fun LinearLayout.put(v: View, top: Int = 0, h: Int = -2) =
        addView(v, LinearLayout.LayoutParams(-1, h).apply { topMargin = d(top) })

    private fun openIntent(i: Intent) {
        onClose()
        try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { dev.toast("مش متاح على الجهاز") }
    }

    private fun after(ms: Long = 650) { ui.postDelayed({ if (!disposed) slowRefreshers.forEach { try { it() } catch (_: Exception) {} } }, ms) }

    /** صفوف من خلايا متساوية. */
    private fun grid(views: List<View>, cols: Int, gap: Int = 5): LinearLayout {
        val box = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        views.chunked(cols).forEachIndexed { ri, rowItems ->
            val row = LinearLayout(svc)
            for (c in 0 until cols) {
                val v = rowItems.getOrNull(c)
                val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(d(gap) / 2, 0, d(gap) / 2, 0) }
                if (v != null) row.addView(v, lp) else row.addView(Space(svc), lp)
            }
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (ri == 0) 0 else d(gap) })
        }
        return box
    }

    private class MiniSwitch(ctx: Context) : View(ctx) {
        var checked = false
            set(v) { if (field != v) { field = v; anim?.cancel(); anim = ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply { duration = 160; addUpdateListener { pos = it.animatedValue as Float; invalidate() }; start() } } }
        private var pos = 0f
        private var anim: ValueAnimator? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(46), context.dpi(26))
        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            r.set(0f, 0f, w, h)
            p.style = Paint.Style.FILL
            p.color = if (pos > 0.5f) Color.parseColor("#FFE3182A") else Color.parseColor("#FF2B1114")
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1f); p.color = Color.parseColor("#AAFF5A64")
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            val rad = h / 2 - context.dpf(3f)
            c.drawCircle(h / 2 + (w - h) * pos, h / 2, rad, p)
        }
    }

    /** سطر بسويتش: أيقونة + نص + مفتاح. */
    private fun toggleChip(icon: Int, text: String, key: String, onTap: () -> Unit): View {
        val sw = MiniSwitch(svc)
        val row = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Ui.glass(svc, 14f); setPadding(d(10), d(8), d(10), d(8))
            addView(glyph(icon, 22)); addView(tv(text, 12.5f).apply { setPadding(d(8), 0, d(4), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw)
            setOnClickListener { onTap(); after(300) }
        }
        slowRefreshers += { dev.state(key)?.let { sw.checked = it } }
        return row
    }

    private fun tile(ar: String, en: String, res: Int, key: String?, click: () -> Unit, h: Int = 98): GlassButton {
        val b = GlassButton(svc, GlassButton.Shape.CARD, Glyphs.get(svc, res), if (en.isEmpty()) ar else "$ar\n$en", 58f, h)
        b.setOnClickListener { click(); after() }
        if (key != null) slowRefreshers += { dev.state(key)?.let { b.on = it } }
        return b
    }

    // ---------- الشخصية والخلفية ----------
    /** مساحة مخصصة للصورة على الشمال: القوايم بتاخد الباقي بس ومبتغطيش الشاشة كلها. */
    private val zoneKey get() = if (land) "zone_w_l" else "zone_w"
    private val zoneFrac get() = svc.dev.prefs.getFloat(zoneKey, if (land) 0.24f else 0.30f).coerceIn(0.12f, 0.5f)
    private val hasImg = java.io.File(svc.filesDir, "girl_custom.png").exists() || svc.dev.prefs.getBoolean("girl_original", false)
    private val zoneW = if (hasImg) (dm.widthPixels * zoneFrac).toInt() else 0
    private val girl = GirlView(svc, land, zoneW)
    private lateinit var content: FrameLayout

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        isClickable = true
        val girlW = 0
        val areaLeft = zoneW
        val areaW = dm.widthPixels - areaLeft
        val k = dm.widthPixels / 2172f
        val topH = (256 * k).toInt(); val botH = (220 * k).toInt()
        val side = (areaW * 0.076f).toInt()

        addView(BgView(svc, 0), LayoutParams(-1, -1))

        content = FrameLayout(svc)
        addView(content, LayoutParams(-1, -1).apply { leftMargin = areaLeft })
        buildHeader(d(14), topH)

        val colArea = FrameLayout(svc)
        content.addView(colArea, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = side + (girlW - areaLeft); rightMargin = side; topMargin = topH; bottomMargin = botH
        })
        fun scroll(v: View) = ScrollView(svc).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(v) }
        if (land) {
            val a = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            a.put(aiPanel(), 0, -2); a.put(connPanel(), 12); a.put(quickPanel(), 12); a.put(modesPanel(), 12)
            val b = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            b.put(shortcutsPanel(5), 0); b.put(statusPanel(), 12); b.put(batteryPanel(), 12)
            val row = LinearLayout(svc)
            row.addView(scroll(a), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = d(4) })
            row.addView(scroll(b), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = d(4) })
            colArea.addView(row, FrameLayout.LayoutParams(-1, -1))
        } else {
            // الوضع الطولي: عمود واحد بأطوال طبيعية وسكرول، والبنت في الشمال بعيد عن الأزرار
            val one = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            one.put(aiPanel(), 0, -2); one.put(connPanel(), 12); one.put(quickPanel(), 12); one.put(modesPanel(), 12)
            one.put(shortcutsPanel(5), 12); one.put(statusPanel(), 12); one.put(batteryPanel(), 12)
            colArea.addView(scroll(one), FrameLayout.LayoutParams(-1, -1))
        }

        // البنت فوق كل حاجة (بتظهر كاملة من الراس للرجلين في الوضع العرضي)
        addView(girl, LayoutParams(-1, -1))

        refreshers.forEach { try { it() } catch (_: Exception) {} }
        slowRefreshers.forEach { try { it() } catch (_: Exception) {} }
        ui.postDelayed(tick, 1000)
        if (animateIn) {
            translationY = -dm.heightPixels.toFloat()
            animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    // ================= تعديل الشخصية: سحب لتحريك وبإصبعين تكبير/تصغير =================
    private var editLayer: View? = null
    private fun startGirlEdit() {
        if (editLayer != null) return
        if (!girl.hasImage) {
            dev.toast("اختار صورة الأول")
            onClose()
            try { svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pick_girl", true)) } catch (_: Exception) {}
            return
        }
        val layer = FrameLayout(svc)
        var seekW: SeekBar? = null; var seekH: SeekBar? = null
        val scaleDet = android.view.ScaleGestureDetector(svc, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: android.view.ScaleGestureDetector): Boolean {
                girl.sx = (girl.sx * det.scaleFactor).coerceIn(0.2f, 4f); girl.sy = (girl.sy * det.scaleFactor).coerceIn(0.2f, 4f)
                seekW?.progress = (girl.sx * 100).toInt(); seekH?.progress = (girl.sy * 100).toInt()
                girl.invalidate(); return true
            }
        })
        var lx = 0f; var ly = 0f
        layer.setOnTouchListener { _, e ->
            scaleDet.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) (if (e.actionIndex == 0) 1 else 0) else 0
                    lx = e.getX(i); ly = e.getY(i)
                }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaleDet.isInProgress) {
                    girl.dx += (e.x - lx) / width; girl.dy += (e.y - ly) / height
                    lx = e.x; ly = e.y; girl.invalidate()
                } else if (e.pointerCount > 1) { lx = e.getX(0); ly = e.getY(0) }
            }
            true
        }
        val hint = tv("اسحب الصورة لتحريكها • بإصبعين كبّر/صغّر • أو استخدم المنزلقات", 14f).apply { gravity = Gravity.CENTER; background = Ui.glass(svc, 14f, true); setPadding(d(14), d(8), d(14), d(8)) }
        layer.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = d(90) })

        val panelBox = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; background = Ui.glass(svc, 18f, true); setPadding(d(14), d(10), d(14), d(10)); layoutDirection = LAYOUT_DIRECTION_RTL }
        fun slider(name: String, get: () -> Float, set: (Float) -> Unit): SeekBar {
            val row = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv(name, 13f), LinearLayout.LayoutParams(d(56), -2))
            val sb = SeekBar(svc).apply {
                max = 400; progress = (get() * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { set((p.coerceAtLeast(20)) / 100f); girl.invalidate() } }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            row.addView(sb, LinearLayout.LayoutParams(d(260), -2))
            panelBox.addView(row)
            return sb
        }
        seekW = slider("العرض", { girl.sx }) { girl.sx = it }
        seekH = slider("الطول", { girl.sy }) { girl.sy = it }
        val row = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        fun b(t: String, on: Boolean, f: () -> Unit) = row.addView(GlassButton(svc, GlassButton.Shape.PILL, null, t).apply { this.on = on; setOnClickListener { f() } },
            LinearLayout.LayoutParams(d(100), d(40)).apply { marginStart = d(3); marginEnd = d(3) })
        fun end() { removeView(layer); editLayer = null }
        fun zone(delta: Float) {
            girl.save()
            svc.dev.prefs.edit().putFloat(zoneKey, (zoneFrac + delta).coerceIn(0.12f, 0.5f)).apply()
            end(); onClose(); svc.postDelayed(350) { svc.showPanel(false) }
        }
        b("مساحة +", false) { zone(0.05f) }
        b("مساحة −", false) { zone(-0.05f) }
        b("حفظ", true) { girl.save(); end() }
        b("إعادة ضبط", false) { girl.reset(); seekW?.progress = 100; seekH?.progress = 100 }
        b("تغيير الصورة", false) {
            end(); onClose()
            try { svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pick_girl", true)) } catch (_: Exception) {}
        }
        b("إلغاء", false) { girl.restore(); end() }
        panelBox.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(8); gravity = Gravity.CENTER_HORIZONTAL })
        layer.addView(panelBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = d(40) })
        editLayer = layer
        addView(layer, LayoutParams(-1, -1))
        girl.bringToFront(); layer.bringToFront()
    }

    // ================= الهيدر: ساعة وتاريخ وحالة =================
    private fun buildHeader(leftPad: Int, topH: Int) {
        val clock = tv("", 36f, false).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setShadowLayer(d(6).toFloat(), 0f, 0f, Color.parseColor("#88FF2A3A")) }
        val date = tv("", 14.5f, false, Color.parseColor("#FFF1E4E4"))
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; addView(clock); addView(date.apply { setPadding(0, d(2), 0, 0) }) }
        val editBtn = GlassButton(svc, GlassButton.Shape.PILL, null, "تخصيص").apply { setOnClickListener { startGirlEdit() } }
        col.addView(editBtn, LinearLayout.LayoutParams(d(96), d(34)).apply { topMargin = d(6) })
        this@ControlPanel.addView(col, LayoutParams(-2, -2).apply { leftMargin = d(16); topMargin = d(8) })
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        val df = SimpleDateFormat("EEEE، d MMMM yyyy", Locale.forLanguageTag("ar-u-nu-latn"))
        refreshers += { val now = Date(); clock.text = tf.format(now); date.text = df.format(now) }

        // أيقونات الحالة + زرارين
        val wifi = glyph(R.drawable.g_wifi, 20); val bt = glyph(R.drawable.g_bluetooth, 20)
        val batG = glyph(R.drawable.g_battery, 22); val pct = tv("", 15f)
        val st = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL; addView(wifi); addView(bt, LinearLayout.LayoutParams(d(20), d(20)).apply { marginStart = d(12) })
            addView(batG, LinearLayout.LayoutParams(d(22), d(22)).apply { marginStart = d(12) }); addView(pct, LinearLayout.LayoutParams(-2, -2).apply { marginStart = d(4) }) }
        val power = GlassButton(svc, GlassButton.Shape.ORB, Glyphs.get(svc, R.drawable.g_power), "", 40f).apply { on = true; setOnClickListener { onClose(); dev.globalAction("power_menu") } }
        val gear = GlassButton(svc, GlassButton.Shape.ORB, Glyphs.get(svc, R.drawable.g_settings), "", 40f).apply {
            setOnClickListener { onClose(); try { svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
            setOnLongClickListener { openIntent(Intent(Settings.ACTION_SETTINGS)); true }
        }
        val right = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(st, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(10) })
            addView(LinearLayout(svc).apply { addView(power, LinearLayout.LayoutParams(d(46), d(46))); addView(gear, LinearLayout.LayoutParams(d(46), d(46)).apply { marginStart = d(8) }) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(4) }) }
        content.addView(right, LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = d(14) })
        refreshers += {
            val b = stats.battery()
            pct.text = "${b.pct}%"
            batG.setColorFilter(if (b.charging) Color.parseColor("#FF4DFF9A") else Color.WHITE, PorterDuff.Mode.SRC_IN)
            wifi.alpha = if (stats.wifiConnected()) 1f else 0.35f
            bt.alpha = if (dev.state("bluetooth") == true) 1f else 0.35f
        }
    }

    // ================= Raven =================
    private fun aiPanel(): View {
        val box = LinearLayout(svc).apply { background = NeonDrawable(svc); setPadding(d(12), d(10), d(12), d(10)); gravity = Gravity.CENTER_VERTICAL }
        val av = d(if (land) 78 else 96)
        val avatar = ImageView(svc).apply {
            setImageResource(R.drawable.avatar); setOnClickListener { onClose(); svc.openAssistant(false) }
            contentDescription = "Raven"
        }
        box.addView(avatar, LinearLayout.LayoutParams(av, av))
        val right = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL
            addView(LinearLayout(svc).apply {
                addView(tv("AI ", 22f, true).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD_ITALIC) })
                addView(tv("RAVEN", 22f, true, Color.parseColor("#FFFF2A3C")).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD_ITALIC) })
            })
            addView(tv("مساعدك الذكي", 14f).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }) }
        top.addView(names)
        val wave = WaveView(svc)
        top.addView(wave, LinearLayout.LayoutParams(0, d(34), 1f).apply { marginStart = d(8); marginEnd = d(8) })
        val chev = GlassButton(svc, GlassButton.Shape.CARD, Glyphs.get(svc, R.drawable.g_chevr), "", 58f, 40).apply { setOnClickListener { onClose(); svc.openAssistant(false) } }
        top.addView(chev, LinearLayout.LayoutParams(d(44), d(40)))
        right.addView(top, LinearLayout.LayoutParams(-1, -2))
        fun ai(ar: String, res: Int, f: () -> Unit) = GlassButton(svc, GlassButton.Shape.CARD, Glyphs.get(svc, res), ar, 58f, 74).apply { setOnClickListener { f() } }
        val btns = listOf(
            ai("محادثة", R.drawable.g_star) { onClose(); svc.openAssistant(false) },
            ai("تلخيص", R.drawable.g_doc) { svc.assistant?.let { svc.setBubbleVisible(true) }; svc.assistant?.summarizeScreen() },
            ai("ترجمة", R.drawable.g_translate) { toggleTranslate() },
            ai("تحليل صورة", R.drawable.g_image) { svc.setBubbleVisible(true); svc.assistant?.analyzeScreen() },
            ai("إجابة سريعة", R.drawable.g_bolt) { onClose(); svc.openAssistant(true) }
        )
        btns[2].also { b -> slowRefreshers += { b.on = svc.translationOn() }; b.setOnLongClickListener { onClose(); svc.openAiSettings(); true } }
        right.addView(grid(btns, 5, 4), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(6) })
        box.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d(10) })
        refreshers += { val a = svc.assistant; wave.level = a?.level ?: 0f; val act = a?.active == true; if (wave.active != act) wave.active = act }
        return box
    }

    private fun toggleTranslate() {
        val on = !svc.translationOn()
        svc.setTranslation(on)
        dev.toast(if (on) "ترجمة التطبيقات الصينية: شغّالة" else "ترجمة التطبيقات الصينية: مقفولة")
        after(100)
    }

    // ================= الاتصال =================
    private fun wide(res: Int, label: String, key: String, click: () -> Unit): GlassButton {
        val b = GlassButton(svc, GlassButton.Shape.WIDE, Glyphs.get(svc, res), label)
        b.setOnClickListener { click(); after() }
        slowRefreshers += { dev.state(key)?.let { b.on = it } }
        return b
    }

    private fun connPanel(): View {
        val box = neon()
        box.put(title(R.drawable.g_antenna, "الاتصال"))
        val wifi = wide(R.drawable.g_wifi, "Wi-Fi\n", "wifi") { dev.toggle("wifi", fb) }
        val bt = wide(R.drawable.g_bluetooth, "Bluetooth\n", "bluetooth") { dev.toggle("bluetooth", fb) }
        slowRefreshers += {
            val on = stats.wifiOn()
            wifi.setLabel("Wi-Fi\n" + when { stats.wifiConnected() -> stats.wifiName() ?: "متصل"; on -> "غير متصل"; else -> "مقفول" })
            bt.setLabel("Bluetooth\n" + if (dev.state("bluetooth") == true) "مفعّل" else "مقفول")
        }
        box.put(grid(listOf(wifi, bt), 2), 6)
        val hs = tile("نقطة اتصال", "Hotspot", R.drawable.g_hotspot, "hotspot", { dev.toggle("hotspot", fb) }, 72)
        val cast = tile("البث", "Cast", R.drawable.g_cast, null, { dev.toggle("cast", fb) }, 72)
        val vpn = tile("VPN", "", R.drawable.g_vpn, "vpn", { dev.toggle("vpn", fb) }, 72)
        box.put(grid(listOf(hs, cast, vpn), 3), 5)
        return box
    }

    // ================= التحكم السريع =================
    private fun quickPanel(): View {
        val box = neon()
        box.put(title(R.drawable.g_sliders, "التحكم السريع"))
        fun sliderRow(icon: Int, s: GlowSlider, pct: TextView) = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(glyph(icon, 24)); addView(s, LinearLayout.LayoutParams(0, -2, 1f)); addView(pct, LinearLayout.LayoutParams(d(46), -2))
        }
        val bp = tv("", 14f).apply { gravity = Gravity.END }
        val bs = GlowSlider(svc).apply { max = 255; progress = dev.brightness(); onChange = { dev.setBrightness(it); bp.text = "${it * 100 / 255}%" } }
        box.put(sliderRow(R.drawable.g_sun, bs, bp), 6)
        val vp = tv("", 14f).apply { gravity = Gravity.END }
        val vs = GlowSlider(svc).apply { max = dev.maxVolume(); progress = dev.volume(); onChange = { dev.setVolume(it); vp.text = "${it * 100 / maxOf(1, dev.maxVolume())}%" } }
        box.put(sliderRow(R.drawable.g_volume, vs, vp), 2)
        refreshers += {
            if (!bs.isDragging) { bs.progress = dev.brightness(); bp.text = "${dev.brightness() * 100 / 255}%" }
            if (!vs.isDragging) { vs.progress = dev.volume(); vp.text = "${dev.volume() * 100 / maxOf(1, dev.maxVolume())}%" }
        }
        val a = toggleChip(R.drawable.g_sun, "سطوع تلقائي", "auto_brightness") { dev.toggle("auto_brightness", fb) }
        val s = toggleChip(R.drawable.g_mic, "صوت ذكي", "smart_sound") { dev.toggle("smart_sound", fb) }
        val row = LinearLayout(svc)
        row.addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(3) })
        row.addView(s, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d(3) })
        box.put(row, 8)
        return box
    }

    // ================= أوضاع الجهاز =================
    private fun modesPanel(): View {
        val box = neon()
        box.put(title(R.drawable.g_gamepad, "أوضاع الجهاز"))
        val cur = dev.prefs.getInt("perf_mode", 1)
        fun mode(ar: String, en: String, res: Int, idx: Int): GlassButton {
            val b = GlassButton(svc, GlassButton.Shape.CARD, Glyphs.get(svc, res), "$ar\n$en", 58f, 70)
            b.on = cur == idx
            b.setOnClickListener {
                dev.prefs.edit().putInt("perf_mode", idx).apply()
                perfBtns.forEachIndexed { i, x -> x.on = i == idx }
                dev.setPerformanceMode(idx, fb)
            }
            return b
        }
        val performance = mode("وضع الألعاب", "Performance", R.drawable.g_gamepad, 2)
        val balanced = mode("متوازن", "Balanced", R.drawable.g_leaf, 1)
        val saving = mode("توفير الطاقة", "Power Saving", R.drawable.g_battery_saver, 0)
        // ترتيب الأزرار في perfBtns = فهرس الوضع (0 توفير،1 متوازن،2 أداء)
        perfBtns.clear(); perfBtns.addAll(listOf(saving, balanced, performance))
        val reading = tile("القراءة", "Reading", R.drawable.g_book, "reading", { dev.toggle("reading", fb) }, 70)
        val custom = GlassButton(svc, GlassButton.Shape.CARD, Glyphs.get(svc, R.drawable.g_sliders), "مخصص\nCustom", 58f, 70).apply { setOnClickListener { showCustomMenu() } }
        box.put(grid(listOf(performance, balanced, saving, reading, custom), 5, 4), 6)
        return box
    }

    // ================= الاختصارات الذكية =================
    private fun shortcutsPanel(cols: Int): View {
        val box = neon()
        box.put(title(R.drawable.g_layers, "الاختصارات الذكية"))
        val svcA = svc
        val items = listOf(
            tile("تدوير تلقائي", "Auto-rotate", R.drawable.g_rotate, "rotate", { dev.toggle("rotate", fb) }),
            tile("صامت", "Silent", R.drawable.g_bell_off, "silent", { dev.toggle("silent", fb) }),
            tile("الاهتزاز", "Vibration", R.drawable.g_vibrate, "vibrate", { dev.toggle("vibrate", fb) }),
            tile("وضع الطيران", "Airplane mode", R.drawable.g_airplane, "airplane", { dev.toggle("airplane", fb) }),
            tile("وضع الكمبيوتر", "PC mode", R.drawable.g_desktop, "pc_mode", { dev.toggle("pc_mode", fb) }),
            tile("لقطة شاشة", "Screen shot", R.drawable.g_screenshot, null, { onClose(); dev.globalAction("screenshot") }),
            tile("تسجيل الشاشة", "Screen record", R.drawable.g_record, null, { dev.toggle("record", fb) }),
            tile("الكشاف", "Flashlight", R.drawable.g_flash, "torch", { dev.toggle("torch", fb) }),
            tile("تفريغ صوتي", "AI Transcript", R.drawable.g_transcript, null, { onClose(); svcA.setBubbleVisible(true); svcA.assistant?.openChat(true, AssistantOverlay.Task.TRANSCRIBE) }),
            tile("الموقع", "Location", R.drawable.g_location, "location", { dev.toggle("location", fb) }),
            tile("مساعد مكالمات", "Video call assistant", R.drawable.g_video, null, { onClose(); svcA.setBubbleVisible(true); svcA.assistant?.openChat(true, AssistantOverlay.Task.CALL) }),
            tile("عدم الإزعاج", "Do Not Disturb", R.drawable.g_dnd, "dnd", { dev.toggle("dnd", fb) }),
            tile("تقسيم الشاشة", "Split screen", R.drawable.g_split, null, { onClose(); dev.globalAction("split_screen") }),
            tile("توفير البطارية", "Battery Saver", R.drawable.g_battery_saver, "battery_saver", { dev.toggle("battery_saver", fb) }),
            tile("وضع القراءة", "Reading mode", R.drawable.g_book, "reading", { dev.toggle("reading", fb) }),
            tile("الأصوات", "Sounds", R.drawable.g_ear, null, { dev.toggle("sounds", fb) }),
            tile("وضع داكن", "Dark mode", R.drawable.g_dark, "dark", { dev.toggle("dark", fb) }),
            tile("سطوع تلقائي", "Auto brightness", R.drawable.g_sun, "auto_brightness", { dev.toggle("auto_brightness", fb) }),
            tile("حماية العين", "Eye protection", R.drawable.g_eye, "eye", { dev.toggle("eye", fb) }),
            tile("درع الخصوصية", "Privacy shield", R.drawable.g_shield, null, { dev.toggle("privacy", fb) }),
            tile("مساعد الدراسة", "Study Assistant", R.drawable.g_cap, null, { onClose(); svcA.setBubbleVisible(true); svcA.assistant?.startStudy() }),
            tile("QR code", "QR code scanner", R.drawable.g_qr, null, { onClose(); dev.toggle("qr", fb) })
        )
        box.put(grid(items, cols, 5), 6)
        return box
    }

    // ================= حالة الجهاز =================
    private fun statusPanel(): View {
        val box = neon()
        box.put(title(R.drawable.g_pulse, "حالة الجهاز"))
        val cpu = RingGauge(svc, "CPU"); val gpu = RingGauge(svc, "GPU"); val ram = RingGauge(svc, "RAM"); val sto = RingGauge(svc, "Storage")
        box.put(grid(listOf(cpu, gpu, ram, sto), 4, 2), 4)
        fun t(v: Float) = if (v < 0) "--" else "${v.toInt()}°C"
        refreshers += {
            cpu.set(stats.cpuLoad(), t(stats.cpuTemp()))
            gpu.set(stats.gpuLoad(), t(stats.gpuTemp()))
            val r = stats.ram(); ram.set(r.first, "%.1f / %.0f GB".format(r.second, r.third))
        }
        slowRefreshers += { val s = stats.storage(); sto.set(s.first, "%.0f / %.0f GB".format(s.second, s.third)) }
        return box
    }

    // ================= البطارية =================
    private fun batteryPanel(): View {
        val box = neon()
        box.put(title(R.drawable.g_battery, "البطارية"))
        val bar = ChargeBar(svc)
        val pct = tv("", 26f, false).apply { gravity = Gravity.END }
        val row = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(bar, LinearLayout.LayoutParams(0, d(20), 1f))
        row.addView(pct, LinearLayout.LayoutParams(d(72), -2))
        box.put(row, 12)
        val st = tv("", 14f).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val bolt = glyph(R.drawable.g_bolt, 18, Color.parseColor("#FFFF4050"))
        val stRow = LinearLayout(svc).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; addView(st); addView(bolt, LinearLayout.LayoutParams(d(18), d(18)).apply { marginStart = d(6) }) }
        box.put(stRow, 12)
        val rem = tv("", 13.5f, false, Color.parseColor("#FFE6D0D2")).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.END }
        box.put(rem, 6)
        val temp = tv("", 12f, false, Ui.TEXT_DIM).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.put(temp, 4)
        refreshers += {
            val b = stats.battery()
            bar.fraction = b.pct / 100f; bar.charging = b.charging
            pct.text = "${b.pct}%"
            st.text = when { b.full -> "مشحونة بالكامل"; b.charging -> "يتم الشحن"; b.plugged -> "متصلة بالشاحن"; else -> "تعمل على البطارية" }
            bolt.visibility = if (b.charging) View.VISIBLE else View.GONE
            rem.text = if (b.remainingMin > 0) {
                val h = b.remainingMin / 60; val m = b.remainingMin % 60
                (if (b.charging) "متبقي للشحن: " else "متبقي: ") + (if (h > 0) "$h ساعات و " else "") + "$m دقيقة"
            } else ""
            temp.text = "حرارة البطارية: ${"%.1f".format(b.tempC)}°C"
        }
        return box
    }

    // ================= قائمة Custom + اختيار الألعاب =================
    private var overlay: FrameLayout? = null

    private fun showOverlay(card: View) {
        dismissOverlay()
        val o = FrameLayout(svc).apply { setBackgroundColor(Color.parseColor("#B0000000")); setOnClickListener { dismissOverlay() } }
        o.addView(card, LayoutParams(minOf(d(520), (dm.widthPixels * 0.92f).toInt()), -2, Gravity.CENTER))
        addView(o, LayoutParams(-1, -1))
        overlay = o
        o.alpha = 0f; o.animate().alpha(1f).setDuration(160).start()
        girl.bringToFront()
    }

    private fun dismissOverlay() { overlay?.let { removeView(it) }; overlay = null }

    private fun showCustomMenu() {
        val card = neon().apply { setOnClickListener { } }
        card.put(title(R.drawable.g_sliders, "مخصص — أدوات الألعاب"))
        fun w(res: Int, label: String, key: String?, f: () -> Unit): GlassButton {
            val b = GlassButton(svc, GlassButton.Shape.WIDE, Glyphs.get(svc, res), label)
            b.setOnClickListener { f() }
            if (key != null) b.on = dev.state(key) == true
            return b
        }
        val items = listOf(
            w(R.drawable.g_floating, "Floating Window\nشريط عايم", null) { dismissOverlay(); onClose(); svc.toggleToolbox() },
            w(R.drawable.g_keymap, "Key Mapping\nربط الدراع", null) { dismissOverlay(); onClose(); svc.keyMapper.openEditor() },
            w(R.drawable.g_macros, "Macros\nنقر تلقائي", null) { dismissOverlay(); onClose(); svc.toggleAutoTap() },
            w(R.drawable.g_gamepad, "Game Assistant\nالألعاب", null) { showGamePicker() },
            w(R.drawable.g_perfmon, "Performance Monitor\nعدّاد الأداء", null) { dismissOverlay(); onClose(); svc.toggleMonitor() },
            w(R.drawable.g_rocket, "Game Mode\nوضع اللعب", "game_mode") { dev.setGameMode(!dev.gameMode()); dismissOverlay(); dev.toast(if (dev.gameMode()) "وضع اللعب شغّال" else "وضع اللعب اتقفل") },
            w(R.drawable.g_tornado, "Extreme\nأقصى أداء", null) { dev.prefs.edit().putInt("perf_mode", 2).apply(); dev.setPerformanceMode(3, fb); dismissOverlay() },
            w(R.drawable.g_target, "Stay Awake\nمنع الإطفاء", "stay_awake") { dev.toggleStayAwake(); dismissOverlay() }
        )
        card.put(grid(items, 2, 6), 8)
        val hz = LinearLayout(svc)
        val cur = dev.currentHz()
        for (v in listOf(60, 90, 120, 144)) {
            val b = GlassButton(svc, GlassButton.Shape.PILL, null, "$v Hz").apply { on = Math.abs(cur - v) < 2f; setOnClickListener { dev.setRefresh(v.toFloat()); dismissOverlay(); dev.toast("معدل التحديث: $v Hz") } }
            hz.addView(b, LinearLayout.LayoutParams(0, d(38), 1f).apply { setMargins(d(3), 0, d(3), 0) })
        }
        card.put(hz, 10)
        showOverlay(card)
    }

    private fun showGamePicker() {
        val (isGames, apps) = dev.games()
        val card = neon().apply { setOnClickListener { } }
        card.put(title(R.drawable.g_gamepad, if (isGames) "Game Assistant — ألعابك" else "Game Assistant — كل التطبيقات"))
        val modeChip = toggleChip(R.drawable.g_rocket, "وضع اللعب (عدم إزعاج + 144Hz + عدّاد)", "game_mode") { dev.setGameMode(!dev.gameMode()) }
        card.put(modeChip, 8)
        slowRefreshers.forEach { try { it() } catch (_: Exception) {} }
        val cells = apps.map { a ->
            LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(d(2), d(6), d(2), d(6))
                addView(ImageView(svc).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(d(48), d(48)))
                addView(tv(a.label, 11f).apply { maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(4) })
                setOnClickListener { dismissOverlay(); onClose(); if (!dev.launch(a.pkg)) dev.toast("تعذّر فتح ${a.label}") }
            }
        }
        val sv = ScrollView(svc).apply { isVerticalScrollBarEnabled = false; addView(grid(cells, 4, 4)) }
        card.put(sv, 8, minOf(d(380), (dm.heightPixels * 0.5f).toInt()))
        showOverlay(card)
    }

    // ================= قفل بالسحب لفوق من تحت =================
    private var downY = 0f
    private var swiping = false
    private val slop = ViewConfiguration.get(svc).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> if (!swiping && downY > dm.heightPixels * 0.80f && downY - e.rawY > slop * 2) { swiping = true; return true }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (swiping) translationY = minOf(0f, e.rawY - downY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                swiping = false
                if (translationY < -dm.heightPixels * 0.18f) close() else animate().translationY(0f).setDuration(180).start()
            }
        }
        return true
    }

    /** قفل بحركة سحب لفوق زي لوحة النظام. */
    fun close() {
        if (disposed) return
        animate().translationY(-dm.heightPixels.toFloat()).setDuration(220).withEndAction { onClose() }.start()
    }

    fun dispose() {
        disposed = true
        ui.removeCallbacksAndMessages(null)
        refreshers.clear(); slowRefreshers.clear()
    }
}
