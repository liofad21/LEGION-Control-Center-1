package com.legion.controlcenter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class ControlAccessibilityService : AccessibilityService() {

    companion object { @Volatile var instance: ControlAccessibilityService? = null }

    private lateinit var wm: WindowManager
    lateinit var dev: DeviceActions
    private var panel: ControlPanel? = null
    private var trigger: View? = null
    private var tapWatcher: View? = null
    private var monitor: MonitorOverlay? = null
    var assistant: AssistantOverlay? = null
        private set
    private var toolbox: ToolboxOverlay? = null
    private var autoTap: AutoTapOverlay? = null
    private var translation: TranslationOverlay? = null
    val keyMapper by lazy { KeyMapper(this) }
    private var lastShow = 0L
    @Volatile private var suppress = false
    @Volatile private var lastPkg = ""
    private val handler = Handler(Looper.getMainLooper())

    /** عميل Gemini واحد مشترك بين Raven والترجمة. */
    val gemini: GeminiClient by lazy {
        GeminiClient({ dev.prefs.getString("gemini_key", "") ?: "" }) { n, a -> assistant?.runTool(n, a) ?: "المساعدة مش شغّالة" }
    }

    fun postDelayed(ms: Long, r: () -> Unit) { handler.postDelayed({ r() }, ms) }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dev = DeviceActions(this)
        dev.watchTorch()
        addTrigger()
        addTapWatcher()
        assistant = AssistantOverlay(this, dev)
        translation = TranslationOverlay(this)
        refreshAssistant()
        setTranslation(dev.prefs.getBoolean("translate_on", false), persist = false)
        initThermal()
        if (dev.prefs.getBoolean("wake_on", false) && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { WakeWordService.start(this) } catch (_: Exception) {}
        }
    }

    // ================= Raven =================
    /** بتتنادى من التطبيق: بتظهر/تخفي الشخصية حسب آخر حالة محفوظة. */
    fun refreshAssistant() {
        if (dev.prefs.getBoolean("bubble_visible", false)) assistant?.show() else assistant?.hide()
    }

    fun setBubbleVisible(v: Boolean) {
        dev.prefs.edit().putBoolean("bubble_visible", v).apply()
        refreshAssistant()
    }

    fun toggleBubble() = setBubbleVisible(assistant?.isShowing != true)

    /** يفتح Raven (للأزرار وكلمة التنبيه). */
    fun openAssistant(listen: Boolean) {
        hidePanel()
        setBubbleVisible(true)
        assistant?.openChat(listen)
    }

    // ================= اللمس 3 مرات =================
    private val taps = ArrayList<Long>()
    private var lastToggle = 0L

    private fun addTapWatcher() {
        val v = object : View(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onScreenTap()
                return false
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        tapWatcher = v
        wm.addView(v, lp)
    }

    private fun onScreenTap() {
        if (!dev.prefs.getBoolean("triple_on", true)) return
        if (panel != null || assistant?.isChatOpen == true || dev.gameMode()) return
        val now = System.currentTimeMillis()
        if (now - lastToggle < 1000) return
        taps.add(now)
        while (taps.size > 3) taps.removeAt(0)
        if (taps.size == 3 && taps[2] - taps[0] < 900) {
            taps.clear(); lastToggle = now
            handler.post { toggleBubble() }
        }
    }

    // ================= الأحداث =================
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> { translation?.schedule(); return }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> { translation?.onScroll(); return }
        }
        if (suppress || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        if (pkg == "com.android.systemui") {
            val cls = e.className?.toString() ?: return
            val isShade = cls.contains("QuickSettings", true) || cls.contains("QSPanel", true) ||
                    cls.contains("NotificationShade", true) || cls.contains("NotificationPanel", true)
            if (!isShade || now - lastShow < 800) return
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            else performGlobalAction(GLOBAL_ACTION_BACK)
            showPanel()
        } else {
            lastPkg = pkg
            translation?.schedule(250)
            if (panel != null && now - lastShow > 900) hidePanel() // المستخدم راح لتطبيق تاني (هوم مثلًا)
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean = try { keyMapper.handleKey(event) } catch (_: Exception) { false }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({
            if (panel != null) { hidePanel(); showPanel(false) }
            assistant?.let { if (it.isShowing) { it.hide(); handler.postDelayed({ refreshAssistant() }, 250) } }
            translation?.schedule(300)
        }, 300)
    }

    // ================= معلومات الشاشة =================
    fun foregroundPackage(): String = lastPkg.ifEmpty { try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" } }

    /** النص الظاهر على الشاشة الحالية (لتلخيصه). */
    fun screenText(max: Int = 8000): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40 || sb.length > max || !n.isVisibleToUser) return
            val t = (n.text ?: n.contentDescription)?.toString()
            if (!t.isNullOrBlank()) sb.append(t.trim()).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        try { walk(rootInActiveWindow, 0) } catch (_: Exception) {}
        return sb.toString().take(max)
    }

    /** لقطة شاشة كـ Bitmap (Android 11+). بترجّع null لو مش متاحة. */
    fun takeScreenshotBitmap(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val hb = r.hardwareBuffer
                    val bmp = try { Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
                    try { hb.close() } catch (_: Exception) {}
                    cb(bmp)
                }
                override fun onFailure(errorCode: Int) { cb(null) }
            })
        } catch (_: Exception) { cb(null) }
    }

    // ================= الترجمة =================
    fun setTranslation(on: Boolean, persist: Boolean = true) {
        if (persist) dev.prefs.edit().putBoolean("translate_on", on).apply()
        translation?.setEnabled(on)
        assistant?.bringToFront()
    }
    fun translationOn() = dev.prefs.getBoolean("translate_on", false)

    // ================= الحرارة =================
    private var lastThermalMsg = 0L
    private var loweredHz = false

    private fun initThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.addThermalStatusListener(mainExecutor) { s ->
            if (!dev.prefs.getBoolean("auto_thermal", true)) return@addThermalStatusListener
            val now = System.currentTimeMillis()
            if (s >= PowerManager.THERMAL_STATUS_SEVERE) {
                if (!loweredHz && dev.currentHz() > 100f) { dev.setRefresh(90f); loweredHz = true }
                if (now - lastThermalMsg > 5 * 60_000) {
                    lastThermalMsg = now
                    assistant?.proactive("ريو، التابلت سخن أوي. نزّلت معدل التحديث لـ 90 هرتز عشان يبرد. لو بتلعب خد استراحة شوية.")
                }
            } else if (s >= PowerManager.THERMAL_STATUS_MODERATE && dev.gameMode()) {
                if (now - lastThermalMsg > 10 * 60_000) {
                    lastThermalMsg = now
                    assistant?.proactive("ريو، التابلت بدأ يسخن. لو حبيت اقفلي وضع اللعب أو قلّل السطوع.")
                }
            } else if (s <= PowerManager.THERMAL_STATUS_LIGHT && loweredHz) {
                loweredHz = false
                dev.setRefresh(if (dev.gameMode()) 144f else 120f)
            }
        }
    }

    fun thermalAdvice(): String {
        val st = dev.stats
        val s = st.thermalStatus()
        val adv = when {
            s >= 4 -> "حرج: وقّف أي لعبة أو شحن، وقلّل السطوع، وسيبه يبرد."
            s == 3 -> "سخن جدًا: انزل معدل التحديث 90Hz، شغّل توفير الطاقة، وفصل الشاحن لو بتشحن وبتلعب."
            s == 2 -> "سخن: قلّل السطوع وقفل التطبيقات الخلفية، وفكّ الغطاء لو موجود."
            s in 0..1 -> "الحرارة طبيعية."
            else -> "حالة الحرارة غير متاحة على النظام ده."
        }
        return "thermal=${st.thermalLabel()} battTemp=${"%.1f".format(st.battery().tempC)}C cpuLoad=${st.cpuLoad().toInt()}% ram=${st.ram().first.toInt()}% advice: $adv"
    }

    // ================= أدوات الألعاب =================
    fun toggleToolbox() {
        val t = toolbox
        if (t != null && t.isShowing) { t.hide(); return }
        toolbox = ToolboxOverlay(this, dev) { toolbox = null }.also { it.show() }
        assistant?.bringToFront()
    }

    fun toggleAutoTap() {
        val t = autoTap
        if (t != null && t.isShowing) { t.hide(); return }
        autoTap = AutoTapOverlay(this) { autoTap = null }.also { it.show() }
        assistant?.bringToFront()
    }

    fun isAutoTapRunning() = autoTap?.isShowing == true

    // ================= أزرار النظام =================
    /** بيفتح لوحة النظام ورا لوحتنا ويدوس على الزر بالاسم. onFail لو الزر مالقيناهوش. */
    fun clickSystemTile(labels: List<String>, onFail: () -> Unit) {
        suppress = true
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        var tries = 0
        fun attempt() {
            var done = false
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() != "com.android.systemui") continue
                for (l in labels) {
                    for (n in root.findAccessibilityNodeInfosByText(l)) {
                        var c: AccessibilityNodeInfo? = n
                        while (c != null && !c.isClickable) c = c.parent
                        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done = true; break }
                    }
                    if (done) break
                }
                if (done) break
                // لو الزر في صفحة تانية من لوحة النظام: اسحب الصفحة
                if (tries in 2..5) {
                    val pager = findScrollable(root)
                    pager?.performAction(if (tries % 2 == 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
            }
            if (done || ++tries >= 10) {
                handler.postDelayed({
                    if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    else performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({ suppress = false }, 700)
                    if (!done) onFail()
                }, 300)
            } else handler.postDelayed({ attempt() }, 250)
        }
        handler.postDelayed({ attempt() }, 450)
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) findScrollable(n.getChild(i))?.let { return it }
        return null
    }

    /** يطلع أسماء كل أزرار لوحة النظام (للتشخيص لو زر مش بيشتغل). */
    fun dumpTiles(done: (List<String>) -> Unit) {
        suppress = true
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        handler.postDelayed({
            val names = LinkedHashSet<String>()
            fun walk(n: AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 30) return
                if (n.isClickable) (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { names.add(it.replace('\n', ' ')) }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
            for (w in windows) { val r = w.root ?: continue; if (r.packageName?.toString() == "com.android.systemui") walk(r, 0) }
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ suppress = false }, 700)
            done(names.toList())
        }, 1200)
    }

    // ================= اللوحة =================
    private fun addTrigger() {
        val dm = resources.displayMetrics
        val v = View(this)
        var startY = 0f
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> startY = ev.rawY
                MotionEvent.ACTION_MOVE -> if (ev.rawY - startY > 60 * dm.density) { showPanel(); startY = Float.MAX_VALUE }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (24 * dm.density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        trigger = v
        wm.addView(v, lp)
    }

    fun showPanel(animate: Boolean = true) {
        if (panel != null) return
        lastShow = System.currentTimeMillis()
        val p = ControlPanel(this, dev, animate) { hidePanel() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        panel = p
        wm.addView(p, lp)
        assistant?.bringToFront() // الشخصية دايمًا فوق اللوحة
    }

    fun hidePanel() {
        panel?.let { p -> p.dispose(); try { wm.removeView(p) } catch (_: Exception) {} }
        panel = null
        lastShow = System.currentTimeMillis()
    }

    fun toggleMonitor() {
        val m = monitor
        if (m != null && m.isShowing) { m.hide(); return }
        monitor = MonitorOverlay(this) { monitor = null }.also { it.show() }
        assistant?.bringToFront()
    }
    fun isMonitorOn() = monitor?.isShowing == true
    fun setMonitor(on: Boolean) { if (on != isMonitorOn()) toggleMonitor() }

    override fun onDestroy() {
        hidePanel(); monitor?.hide(); assistant?.hide(); toolbox?.hide(); autoTap?.hide(); translation?.setEnabled(false)
        keyMapper.closeEditor()
        trigger?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        tapWatcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        instance = null
        super.onDestroy()
    }
}
