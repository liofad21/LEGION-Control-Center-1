package com.legion.controlcenter

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private val refreshers = ArrayList<() -> Unit>()
    private lateinit var summary: TextView
    private val prefs by lazy { getSharedPreferences("legion", MODE_PRIVATE) }
    private fun d(v: Int) = dpi(v)

    private fun tv(t: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Ui.glass(this@MainActivity, 22f)
        setPadding(d(16), d(14), d(16), d(14))
    }

    private fun LinearLayout.put(v: View, top: Int = 0, h: Int = -2) =
        addView(v, LinearLayout.LayoutParams(-1, h).apply { topMargin = d(top) })

    private fun action(label: String, filled: Boolean = true, f: () -> Unit) =
        GlassButton(this, GlassButton.Shape.PILL, null, label).apply { on = filled; setOnClickListener { f() } }

    /** كارت صلاحية: عنوان + حالة + شرح + زرار. */
    private fun step(title: String, desc: String, ok: () -> Boolean, btn: String, go: () -> Unit): View {
        val c = card()
        val status = tv("", 13f, true)
        c.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(title, 16f, true), LinearLayout.LayoutParams(0, -2, 1f)); addView(status)
        })
        c.put(tv(desc, 13f, false, Ui.TEXT_DIM), 6)
        c.put(action(btn, true, go), 10, d(44))
        refreshers += {
            val g = ok()
            status.text = if (g) "✓ مفعّل" else "✕ مش مفعّل"
            status.setTextColor(if (g) Color.parseColor("#FF4DFF9A") else Ui.RED_SOFT)
        }
        return c
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }
    private fun listenerOn() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), d(28), d(16), d(32)) }
        col.addView(ImageView(this).apply { setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(-1, d(80)))
        summary = tv("", 14f, false, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER }
        col.put(summary, 6)

        // ---- المساعدة الذكية ----
        val ai = card()
        ai.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.avatar) }, LinearLayout.LayoutParams(d(56), d(56)))
            addView(tv("Raven — مساعدتك الذكية", 17f, true).apply { setPadding(d(12), 0, 0, 0) })
        })
        ai.put(tv("مساعدة بصوت بنت (Gemini 3.5 Flash-Lite) بتنفّذ أوامر التابلت: السطوع، الصوت، الواي فاي، فتح التطبيقات، الميديا وغيره. الشخصية بتفضل عايمة فوق أي تطبيق، اسحبها لأي مكان وإضغط عليها للكلام. لمس الشاشة 3 مرات ورا بعض بيظهرها، و3 مرات تاني بيخفيها.", 13f, false, Ui.TEXT_DIM), 8)
        val key = EditText(this).apply {
            hint = "مفتاح Gemini API"; setHintTextColor(Color.parseColor("#99C9B4B6")); setTextColor(Color.WHITE); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = Ui.glass(this@MainActivity, 16f); setPadding(d(14), d(10), d(14), d(10))
            setText(prefs.getString("gemini_key", ""))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        ai.put(key, 10, d(48))
        ai.put(action("حفظ المفتاح") {
            val k = key.text.toString().filter { !it.isWhitespace() }
            if (k.length < 20) {
                Toast.makeText(this, "المفتاح قصير أو ناقص. انسخه كامل من aistudio.google.com", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putString("gemini_key", k).apply()
                Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
            }
        }, 8, d(44))
        ai.put(tv("المفتاح مجاني من aistudio.google.com ← Get API key، وبيتخزّن على التابلت بس.", 12f, false, Ui.TEXT_DIM), 6)
        fun sw(label: String, pref: String, def: Boolean, onChange: (Boolean) -> Unit = {}): Switch = Switch(this).apply {
            fun lbl(v: Boolean) = "$label — " + if (v) "مفعّل ✓" else "مقفول"
            setTextColor(Color.WHITE); isChecked = prefs.getBoolean(pref, def); text = lbl(isChecked)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(pref, v).apply(); text = lbl(v); onChange(v) }
        }
        ai.put(sw("إظهار الشخصية العايمة", "bubble_visible", false) { ControlAccessibilityService.instance?.refreshAssistant() }, 10)
        ai.put(sw("لمس الشاشة 3 مرات يظهر/يخفي Raven", "triple_on", true), 6)
        ai.put(sw("كلمة التنبيه \"RAVEN\" (سماع طول اليوم)", "wake_on", true) { on ->
            if (on) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 2)
                } else startWake()
            } else WakeWordService.stop(this)
        }, 6)
        ai.put(tv("بتشغّل خدمة بإشعار دائم (أندرويد بيطلبه لأي تطبيق بيستخدم المايك في الخلفية). بتحتاج خدمة التعرف على الكلام بتاعة جوجل، وبتفضل شغّالة طول ما التطبيق مش متقفل بالقوة.", 11f, false, Ui.TEXT_DIM), 2)
        ai.put(action("إعدادات الـ AI: اللغة والتطبيقات المترجمة", true) {
            val sv = ControlAccessibilityService.instance
            if (sv != null) sv.openAiSettings() else Toast.makeText(this, "فعّل خدمة إمكانية الوصول الأول", Toast.LENGTH_SHORT).show()
        }, 8, d(44))
        ai.put(sw("ترجمة التطبيقات (صيني/ياباني/كوري)", "translate_on", false) { ControlAccessibilityService.instance?.setTranslation(it) }, 6)
        ai.put(sw("إدارة الحرارة التلقائية (تنزيل التحديث + تنبيه)", "auto_thermal", true), 6)
        ai.put(sw("Raven ترد بصوتها", "voice_replies", true), 6)
        ai.put(action("صلاحية المايك (للتحدث)") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
            else Toast.makeText(this, "المايك متفعّل بالفعل", Toast.LENGTH_SHORT).show()
        }, 8, d(44))
        col.put(ai, 14)

        // ---- تخصيص صورة اللوحة ----
        val gc = card()
        gc.addView(tv("تخصيص صورة اللوحة", 16f, true))
        gc.put(tv("ارفع أي صورة تحبها عشان تظهر في اللوحة (الأفضل PNG من غير خلفية). بعدها افتح اللوحة واضغط \"تخصيص\" عشان تحرّكها وتتحكم في عرضها وطولها.", 13f, false, Ui.TEXT_DIM), 6)
        gc.put(action("اختيار صورة") { pickGirl() }, 10, d(44))
        gc.put(action("استخدام الشخصية الأصلية", false) {
            java.io.File(filesDir, "girl_custom.png").delete()
            prefs.edit().putBoolean("girl_original", true).remove("girl_scale").remove("girl_dx").remove("girl_dy").remove("girl_sx").remove("girl_sy").apply()
            Toast.makeText(this, "تم", Toast.LENGTH_SHORT).show()
        }, 8, d(44))
        gc.put(action("إزالة الصورة (مساحة فاضية)", false) {
            java.io.File(filesDir, "girl_custom.png").delete()
            prefs.edit().putBoolean("girl_original", false).apply()
            Toast.makeText(this, "اتشالت الصورة", Toast.LENGTH_SHORT).show()
        }, 8, d(44))
        col.put(gc, 14)

        col.put(action("امنح كل الصلاحيات (خطوة بخطوة)") { startGrantChain() }, 14, d(48))

        // ---- الصلاحيات ----
        col.put(tv("الصلاحيات", 18f, true), 18)
        col.put(step("1) إمكانية الوصول", "أهم خطوة: بتعرض اللوحة فوق النظام وبتدوس على أزرار النظام وبتاخد اللقطة. على أندرويد 13+ لو الصلاحية رمادية افتح معلومات التطبيق ← النقط التلاتة ← \"السماح بالإعدادات المقيّدة\".",
            { a11yOn() }, "فتح إمكانية الوصول") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, 8)
        col.put(action("معلومات التطبيق (الإعدادات المقيّدة)", false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, 6, d(40))
        col.put(step("2) تعديل إعدادات النظام", "للسطوع وتدوير الشاشة ومعدل التحديث.",
            { Settings.System.canWrite(this) }, "منح الصلاحية") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }, 10)
        col.put(step("3) عدم الإزعاج", "لزر Do Not Disturb ووضع Extreme.",
            { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted }, "منح الصلاحية") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }, 10)
        col.put(step("4) قراءة الإشعارات (الميديا)", "عشان كارت الميديا يعرض اسم الأغنية والفنان والتقدّم ويتحكم فيها.",
            { listenerOn() }, "منح الصلاحية") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, 10)

        // ---- ADB ----
        val adb = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val c = card()
        c.addView(tv("اختياري: أزرار مباشرة", 16f, true))
        c.put(tv("بيخلي الوضع الداكن وتوفير الطاقة والموقع وأوضاع الأداء تشتغل مباشرة من غير ما تدوس على زر النظام. وصّل التابلت بالكمبيوتر وشغّل:", 13f, false, Ui.TEXT_DIM), 6)
        c.put(tv(adb, 12f, false, Color.parseColor("#FFFF8C94")).apply {
            setTextIsSelectable(true); layoutDirection = View.LAYOUT_DIRECTION_LTR; typeface = Typeface.MONOSPACE
            background = Ui.glass(this@MainActivity, 12f); setPadding(d(12), d(10), d(12), d(10))
        }, 8)
        c.put(action("نسخ الأمر", false) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", adb))
            Toast.makeText(this, "اتنسخ", Toast.LENGTH_SHORT).show()
        }, 8, d(40))
        refreshers += {}
        col.put(c, 14)

        // ---- تجربة ----
        col.put(action("افتح Control Center الآن") {
            val s = ControlAccessibilityService.instance
            if (s != null) s.showPanel() else Toast.makeText(this, "فعّل خدمة إمكانية الوصول الأول", Toast.LENGTH_SHORT).show()
        }, 18, d(52))
        col.put(action("تشخيص: أسماء أزرار لوحة النظام", false) {
            val sv = ControlAccessibilityService.instance
            if (sv == null) Toast.makeText(this, "فعّل خدمة إمكانية الوصول الأول", Toast.LENGTH_SHORT).show()
            else sv.dumpTiles { names ->
                runOnUiThread {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("tiles", names.joinToString("\n")))
                    Toast.makeText(this, "لقيت ${names.size} زر واتنسخوا للحافظة", Toast.LENGTH_LONG).show()
                }
            }
        }, 8, d(44))
        col.put(tv("الاستخدام: اسحب لتحت من أعلى الشاشة، أو افتح لوحة الإشعارات العادية وهتتبدّل تلقائيًا. اسحب لفوق على الصورة لقفل اللوحة.", 12f, false, Ui.TEXT_DIM), 12)

        // محتوى متوسّط العرض على التابلت
        val wrap = FrameLayout(this)
        wrap.addView(col, FrameLayout.LayoutParams(minOf(d(620), resources.displayMetrics.widthPixels), -2, Gravity.CENTER_HORIZONTAL))
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        if (intent?.getBooleanExtra("pick_girl", false) == true) { intent.removeExtra("pick_girl"); pickGirl() }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(wrap) })
    }

    // ---- سلسلة الصلاحيات: بتفتح كل شاشة صلاحية ناقصة واحدة ورا التانية ----
    private var chain = -1
    private fun startGrantChain() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT), 3)
        chain = 0
    }

    private fun nextChainStep() {
        if (chain < 0) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val steps = listOf<Pair<() -> Boolean, Intent>>(
            { pm.isIgnoringBatteryOptimizations(packageName) } to Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            { a11yOn() } to Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            { Settings.System.canWrite(this) } to Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")),
            { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted } to Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            { listenerOn() } to Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        )
        while (chain < steps.size) {
            val (ok, intent) = steps[chain]
            chain++
            if (!ok()) { try { startActivity(intent) } catch (_: Exception) {}; return }
        }
        chain = -1
        Toast.makeText(this, "كل الصلاحيات اتراجعت. (الأزرار المباشرة محتاجة أمر ADB الموجود تحت)", Toast.LENGTH_LONG).show()
    }

    private fun pickGirl() {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 41)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 41 || res != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 2400) sample *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
            if (bmp == null) { Toast.makeText(this, "مقدرتش أقرا الصورة", Toast.LENGTH_LONG).show(); return }
            java.io.FileOutputStream(java.io.File(filesDir, "girl_custom.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            prefs.edit().remove("girl_scale").remove("girl_dx").remove("girl_dy").remove("girl_sx").remove("girl_sy").apply()
            Toast.makeText(this, "تم. افتح اللوحة وعدّل المكان والحجم.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "فشل: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun startWake() {
        try { WakeWordService.start(this) } catch (e: Exception) { Toast.makeText(this, "تعذّر تشغيل كلمة التنبيه: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && prefs.getBoolean("wake_on", true)) startWake()
        if (code == 3) nextChainStep()
    }

    override fun onResume() {
        super.onResume(); refreshers.forEach { it() }; updateSummary(); if (chain > 0) nextChainStep()
        if (prefs.getBoolean("wake_on", true) && !WakeWordService.isRunning() &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startWake()
    }

    private fun updateSummary() {
        val n = listOf(a11yOn(), Settings.System.canWrite(this),
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted, listenerOn()).count { it }
        summary.text = "تم تفعيل $n من 4 صلاحيات" + (if (prefs.getBoolean("wake_on", true)) "\nكلمة RAVEN: " + (prefs.getString("wake_status", "لسه مبدأتش") ?: "") else "")
    }
}
