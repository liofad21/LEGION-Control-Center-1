package com.legion.controlcenter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Raven: شخصية عايمة فوق أي تطبيق. الصوت بيتسجّل من المايك مباشرة (AudioRecord) ويتبعت لـ Gemini كصوت،
 * فمفيش اعتماد على خدمة التعرف على الكلام بتاعة النظام. الرد بصوت بنت من Gemini TTS.
 */
class AssistantOverlay(private val svc: ControlAccessibilityService, private val dev: DeviceActions) {

    enum class Task { CHAT, TRANSCRIBE, CALL }

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = dev.prefs
    private val client get() = svc.gemini

    private var bubble: ImageView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var chat: LinearLayout? = null
    private var log: TextView? = null
    private var logScroll: ScrollView? = null
    private var input: EditText? = null
    private var micBtn: GlassButton? = null
    private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private val pulses = ArrayList<ObjectAnimator>()
    @Volatile private var busy = false
    @Volatile private var recording = false
    @Volatile private var cancelRec = false
    @Volatile private var speaking = false

    /** المستوى الحقيقي للصوت (تسجيل أو تشغيل) للموجة في اللوحة. */
    @Volatile var level = 0f
    val active get() = recording || speaking

    private fun metrics() = DisplayMetrics().also {
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
    }

    val isShowing get() = bubble != null
    val isChatOpen get() = chat != null

    // ================= الشخصية العايمة =================
    fun show() {
        if (bubble != null) return
        val size = svc.dpi(88)
        val img = ImageView(svc).apply {
            setImageResource(R.drawable.avatar)
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = svc.dpf(8f)
            scaleX = 0.2f; scaleY = 0.2f; alpha = 0f
        }
        val m = metrics()
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", m.widthPixels - size - svc.dpi(12)).coerceIn(0, maxOf(0, m.widthPixels - size))
            y = prefs.getInt("bubble_y", m.heightPixels / 2).coerceIn(0, maxOf(0, m.heightPixels - size))
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        var lastTap = 0L
        val pendingChat = Runnable { toggleChat() }
        img.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - sx) > 14 || Math.abs(e.rawY - sy) > 14) moved = true
                    if (moved) {
                        val mm = metrics()
                        lp.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, mm.widthPixels - size)
                        lp.y = (oy + (e.rawY - sy)).toInt().coerceIn(0, mm.heightPixels - size)
                        try { wm.updateViewLayout(img, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("bubble_x", lp.x).putInt("bubble_y", lp.y).apply()
                    else {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTap < 400) {            // دوسة مرتين = اخفاء
                            lastTap = 0L; img.removeCallbacks(pendingChat)
                            svc.setBubbleVisible(false)
                        } else { lastTap = now; img.postDelayed(pendingChat, 400) }
                    }
                }
            }
            true
        }
        bubble = img; bubbleLp = lp
        wm.addView(img, lp)
        img.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator()).start()
    }

    /** يرجّع الشخصية فوق كل الشبابيك (لما لوحة تانية تتفتح). */
    fun bringToFront() {
        val b = bubble ?: return; val lp = bubbleLp ?: return
        try { wm.removeView(b); wm.addView(b, lp) } catch (_: Exception) {}
        chat?.let { c -> try { val l = c.layoutParams; wm.removeView(c); wm.addView(c, l) } catch (_: Exception) {} }
    }

    fun hide() {
        cancelRec = true
        closeChat()
        val b = bubble
        bubble = null
        if (b != null) {
            b.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(180).withEndAction {
                try { wm.removeView(b) } catch (_: Exception) {}
            }.start()
        }
        stopSpeaking()
        tts?.shutdown(); tts = null
    }

    // ================= الشات =================
    /** لما حد ينادي "Raven": تفتح وتقول ترحيب وتسمع على طول (زي Gemini). */
    fun wakeGreet() {
        if (bubble == null) show()
        if (chat == null) buildChat()
        if (recording) return
        val g = "أيوة ريو، أقدر أساعدك إزاي؟"
        say("Raven", g)
        WakeWordService.pause()
        thread {
            speak(g)
            ui.post { if (chat != null) listen(Task.CHAT) else WakeWordService.resume() }
        }
    }

    fun toggleChat() { if (chat == null) openChat() else closeChat() }

    fun openChat(listenNow: Boolean = false, task: Task = Task.CHAT) {
        if (bubble == null) show()
        if (chat == null) buildChat()
        if (listenNow) ui.postDelayed({ listen(task) }, 250)
    }

    private fun buildChat() {
        val m = metrics()
        val w = minOf(svc.dpi(430), (m.widthPixels * 0.92f).toInt())
        val h = minOf(svc.dpi(540), (m.heightPixels * 0.62f).toInt())
        val root = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = NeonDrawable(svc, 22f, 0xEE)
            setPadding(svc.dpi(14), svc.dpi(14), svc.dpi(14), svc.dpi(12))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(svc).apply { setImageResource(R.drawable.avatar) }, LinearLayout.LayoutParams(svc.dpi(34), svc.dpi(34)))
        head.addView(TextView(svc).apply {
            text = "Raven"; setTextColor(Color.WHITE); textSize = 16f; setPadding(svc.dpi(10), 0, 0, 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.ITALIC)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val gear = GlassButton(svc, GlassButton.Shape.FLAT, Glyphs.get(svc, R.drawable.g_settings)).apply { setOnClickListener { svc.openAiSettings() } }
        head.addView(gear, LinearLayout.LayoutParams(svc.dpi(36), svc.dpi(36)))
        val x = GlassButton(svc, GlassButton.Shape.FLAT, Glyphs.get(svc, R.drawable.g_close)).apply { setOnClickListener { closeChat() } }
        head.addView(x, LinearLayout.LayoutParams(svc.dpi(36), svc.dpi(36)))
        root.addView(head)
        val chipRow = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        fun chip(t: String, f: () -> Unit) = chipRow.addView(GlassButton(svc, GlassButton.Shape.PILL, null, t).apply { setOnClickListener { f() } },
            LinearLayout.LayoutParams(svc.dpi(104), svc.dpi(38)).apply { marginEnd = svc.dpi(6) })
        chip("تلخيص الشاشة") { summarizeScreen() }
        chip("تحليل صورة") { analyzeScreen() }
        chip("ترجمة التطبيق") {
            val on = !svc.translationOn(); svc.setTranslation(on)
            say("Raven", if (on) "الترجمة شغّالة. افتح التطبيق وهتلاقيه مترجم. الإعدادات (⚙) فيها اللغة والتطبيقات." else "الترجمة اتقفلت.")
        }
        chip("إجابة سريعة") { listen(Task.CHAT) }
        chip("تفريغ صوتي") { listen(Task.TRANSCRIBE) }
        chip("مساعد مكالمات") { listen(Task.CALL) }
        chip("مساعد دراسة") { startStudy() }
        chip("إعدادات AI") { svc.openAiSettings() }
        root.addView(android.widget.HorizontalScrollView(svc).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) },
            LinearLayout.LayoutParams(-1, svc.dpi(42)).apply { topMargin = svc.dpi(8) })
        val sv = ScrollView(svc).apply { isVerticalScrollBarEnabled = false }
        val tv = TextView(svc).apply { setTextColor(Color.parseColor("#FFEBDADA")); textSize = 14f; setLineSpacing(0f, 1.2f); setTextIsSelectable(true) }
        sv.addView(tv)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = svc.dpi(8); bottomMargin = svc.dpi(8) })

        val row = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(svc).apply {
            hint = "اكتبلي أو اضغط المايك"; setHintTextColor(Color.parseColor("#99C9B4B6")); setTextColor(Color.WHITE)
            textSize = 14f; maxLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = Ui.glass(svc, 18f)
            setPadding(svc.dpi(14), svc.dpi(8), svc.dpi(14), svc.dpi(8))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val mic = GlassButton(svc, GlassButton.Shape.ORB, Glyphs.get(svc, R.drawable.g_mic), "", 46f).apply { setOnClickListener { listen(Task.CHAT) } }
        val snd = GlassButton(svc, GlassButton.Shape.ORB, Glyphs.get(svc, R.drawable.g_next), "", 46f).apply { on = true; setOnClickListener { send() } }
        row.addView(mic, LinearLayout.LayoutParams(svc.dpi(54), -2).apply { marginStart = svc.dpi(6) })
        row.addView(snd, LinearLayout.LayoutParams(svc.dpi(54), -2))
        root.addView(row)

        log = tv; logScroll = sv; input = et; micBtn = mic; chat = root
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            this.x = svc.dpi(12); this.y = svc.dpi(24)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(root, lp)
        if (tv.text.isEmpty()) say("Raven", "أهلاً ريو! قولي أو اكتبلي أي حاجة، وأنا هنفّذها في التابلت.")
        bringToFront()
    }

    fun closeChat() {
        cancelRec = true
        chat?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        chat = null; log = null; input = null; logScroll = null; micBtn = null
    }

    fun say(who: String, text: String) {
        ui.post {
            val l = log ?: return@post
            l.append((if (l.text.isEmpty()) "" else "\n\n") + who + ": " + text)
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ================= إرسال وتنفيذ =================
    private fun send() {
        val t = input?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        input?.setText("")
        ask(t)
    }

    private fun keyMissing(): Boolean {
        val k = (prefs.getString("gemini_key", "") ?: "").filter { !it.isWhitespace() }
        if (k.isBlank()) {
            say("Raven", "محتاجة مفتاح Gemini API. افتح تطبيق Legion Control Center وحطه في خانة Raven.")
            return true
        }
        if (k.length < 20) {
            say("Raven", "المفتاح المحفوظ مش مفتاح Gemini صحيح (قصير أو ناقص). افتح التطبيق، امسح الخانة وحط المفتاح من aistudio.google.com.")
            return true
        }
        return false
    }

    private fun errorText(e: Exception) = when {
        e.message == "NO_KEY" -> "المفتاح مش موجود."
        e.message?.startsWith("HTTP 429") == true -> "وصلنا لحد الاستخدام المجاني لـ Gemini. جرّب بعد شوية."
        e.message?.startsWith("HTTP 403") == true || e.message?.startsWith("HTTP 400") == true && e.message!!.contains("API key", true) -> "المفتاح مش شغّال: ${e.message}"
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException -> "مفيش إنترنت دلوقتي."
        else -> "حصل خطأ: ${e.message}"
    }

    /** نص عادي أو صوت (wav) لـ Raven. */
    fun ask(text: String?, wav: ByteArray? = null, extraSystem: String? = null) {
        if (busy) { say("Raven", "ثانية واحدة، بخلص اللي في إيدي."); return }
        if (keyMissing()) return
        say("ريو", text ?: "(رسالة صوتية)")
        if (text != null && studyMode && (text.contains("خلصنا") || text.contains("خلّصنا"))) studyMode = false
        val sys = extraSystem ?: if (studyMode) studySystem else null
        busy = true; stopSpeaking()
        thread {
            try {
                val reply = client.chat(text, wav, null, sys)
                say("Raven", reply)
                speak(reply)
            } catch (e: Exception) { say("Raven", errorText(e)) }
            finally { busy = false }
        }
    }

    /** تلخيص النص الظاهر على الشاشة. */
    fun summarizeScreen() {
        if (keyMissing()) return
        svc.hidePanel(); openChat()
        say("ريو", "لخّصي اللي على الشاشة")
        ui.postDelayed({
            val t = svc.screenText()
            if (t.isBlank()) { say("Raven", "مش قادرة أقرا نص من الشاشة دي (ممكن تكون صورة أو لعبة)."); return@postDelayed }
            runOneShot("Summarize the screen content below in 2-3 short sentences in the user's language (Egyptian Arabic if the content is Arabic). No markdown.", listOf(client.text(t)))
        }, 600)
    }

    /** تحليل لقطة الشاشة بالصورة. */
    fun analyzeScreen() {
        if (keyMissing()) return
        svc.hidePanel(); openChat()
        say("ريو", "حلّلي الصورة اللي على الشاشة")
        ui.postDelayed({
            svc.takeScreenshotBitmap { bmp ->
                if (bmp == null) { say("Raven", "مقدرتش آخد لقطة للشاشة (محتاجة Android 11+ وإمكانية الوصول مفعّلة)."); return@takeScreenshotBitmap }
                thread {
                    val scaled = Bitmap.createScaledBitmap(bmp, 1024, (1024f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
                    val o = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 80, o)
                    runOneShotSync("Analyze this screenshot: say what it shows and any key useful details, in 2-3 short sentences, in Egyptian Arabic unless the screen text is English. No markdown.",
                        listOf(client.jpegPart(o.toByteArray()), client.text("حلّلي الصورة دي")))
                }
            }
        }, 600)
    }

    private fun runOneShot(system: String, parts: List<JSONObject>) = thread { runOneShotSync(system, parts) }

    private fun runOneShotSync(system: String, parts: List<JSONObject>, speakIt: Boolean = true, copy: Boolean = false) {
        busy = true
        try {
            val r = client.oneShot(system, parts).ifBlank { "مفيش نتيجة." }
            say("Raven", r)
            if (copy) ui.post { (svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("raven", r)) }
            if (speakIt) speak(r)
        } catch (e: Exception) { say("Raven", errorText(e)) } finally { busy = false }
    }

    @Volatile var studyMode = false

    /** مساعد الدراسة: Raven بتتحول لمُدرّسة بتشرح خطوة خطوة. */
    fun startStudy() {
        studyMode = true
        openChat()
        say("Raven", "وضع الدراسة شغّال يا ريو. اكتبلي السؤال أو قوله بصوتك وهشرحه خطوة خطوة. وتقدر تقولي \"خلصنا\" عشان أرجع عادي.")
    }

    private val studySystem = "دلوقتي إنتي مُدرّسة صبورة وودودة. اشرحي خطوة خطوة بأسلوب بسيط، وبعد الشرح اسألي سؤال قصير تتأكدي بيه إن ريو فهم. الرد ممكن يبقى أطول شوية من المعتاد لكن يفضل واضح. لو ريو قال إنه خلّص، ارجعي لشخصيتك العادية."

    /** لما حد يكتب/يتكلم لـ Raven من الأزرار السريعة. */
    fun quickAsk(prompt: String) { svc.hidePanel(); openChat(); ask(prompt) }

    fun proactive(msg: String) {
        say("Raven", msg)
        if (prefs.getBoolean("voice_replies", true) && !busy) thread { speak(msg) }
    }

    /** الأدوات اللي Gemini بينفذها. بتتنفذ على الـ thread الرئيسي وبترجع نتيجة نصية. */
    fun runTool(name: String, a: JSONObject): String {
        // أدوات التحكم في التطبيقات بتستنّى ظهور العناصر، فبتشتغل على الـ thread الحالي مش الـ UI
        when (name) {
            "tap_text" -> { ui.post { closeChat() }; return if (svc.tapText(a.optString("text"))) "tapped '${a.optString("text")}'" else "not found on screen: ${a.optString("text")}" }
            "type_text" -> { ui.post { closeChat() }; return if (svc.typeText(a.optString("text"), a.optBoolean("submit", false))) "typed" else "no text field found" }
            "scroll_screen" -> { ui.post { closeChat() }; return if (svc.scrollScreen(a.optString("direction") != "up")) "scrolled" else "nothing scrollable" }
            "web_search" -> {
                val q = a.optString("query")
                ui.post { closeChat() }
                return try {
                    svc.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "opened google results for: $q"
                } catch (e: Exception) { "failed: ${e.message}" }
            }
        }
        var out = "error"
        val latch = java.util.concurrent.CountDownLatch(1)
        ui.post {
            out = try {
                when (name) {
                    "set_brightness" -> { val p = a.optInt("percent", 50).coerceIn(1, 100); if (dev.setBrightness(p * 255 / 100)) "brightness set to $p%" else "failed: WRITE_SETTINGS permission missing" }
                    "set_volume" -> { val p = a.optInt("percent", 50).coerceIn(0, 100); dev.setVolume(p * dev.maxVolume() / 100); "volume set to $p%" }
                    "set_toggle" -> dev.set(a.optString("name"), a.optBoolean("enabled"))
                    "set_refresh_rate" -> { val hz = a.optInt("hz", 120); if (dev.setRefresh(hz.toFloat())) "refresh rate requested: $hz Hz (device may ignore)" else "failed" }
                    "set_performance_mode" -> {
                        val i = listOf("power_saving", "balanced", "performance", "extreme").indexOf(a.optString("mode"))
                        if (i < 0) "unknown mode" else { dev.setPerformanceMode(i) { s -> try { svc.startActivity(s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }; "mode set" }
                    }
                    "set_game_mode" -> { dev.setGameMode(a.optBoolean("enabled")); "game mode ${if (a.optBoolean("enabled")) "on" else "off"}" }
                    "media_control" -> {
                        val k = when (a.optString("action")) { "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                        dev.mediaKey(k); "sent"
                    }
                    "system_action" -> dev.globalAction(a.optString("action"))
                    "open_app" -> { closeChat(); dev.openApp(a.optString("name")) }
                    "open_control_center" -> { closeChat(); svc.showPanel(); "opened" }
                    "toggle_performance_monitor" -> { svc.toggleMonitor(); "toggled" }
                    "toggle_chinese_translation" -> { svc.setTranslation(a.optBoolean("enabled")); "translation ${if (a.optBoolean("enabled")) "on" else "off"}" }
                    "read_screen" -> svc.screenText().ifBlank { "no readable text on screen" }
                    "get_status" -> dev.status()
                    "thermal_advice" -> svc.thermalAdvice()
                    else -> "unknown tool"
                }
            } catch (e: Exception) { "error: ${e.message}" }
            latch.countDown()
        }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    // ================= الصوت (رد) =================
    fun speak(text: String) {
        if (!prefs.getBoolean("voice_replies", true)) return
        speaking = true
        startPulse()
        try {
            val pcm = client.speak(text)
            val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track = t
            t.write(pcm, 0, pcm.size); t.play()
            val total = pcm.size / 2
            while (track === t && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val pos = t.playbackHeadPosition
                if (pos >= total) break
                var s = 0.0; var n = 0
                var i = pos
                while (i < minOf(pos + 480, total)) {
                    val v = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
                    val sv = v.toShort().toInt(); s += sv.toDouble() * sv; n++; i++
                }
                level = if (n > 0) (sqrt(s / n) / 32768.0 * 6).toFloat().coerceIn(0f, 1f) else 0f
                Thread.sleep(40)
            }
            try { t.release() } catch (_: Exception) {}
            if (track === t) track = null
        } catch (e: Exception) {
            ui.post { fallbackTts(text) }
        } finally { speaking = false; level = 0f; ui.post { stopPulse() } }
    }

    private fun fallbackTts(text: String) {
        val speakNow = { tts?.setPitch(1.3f); tts?.setSpeechRate(1.05f); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "raven"); Unit }
        if (tts == null) {
            tts = TextToSpeech(svc) { s -> if (s == TextToSpeech.SUCCESS) { tts?.language = Locale("ar"); speakNow() } }
        } else speakNow()
    }

    fun stopSpeaking() {
        speaking = false
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        track = null; tts?.stop()
    }

    private fun startPulse() {
        ui.post {
            val b = bubble ?: return@post
            stopPulse()
            for (prop in listOf(View.SCALE_X, View.SCALE_Y)) {
                pulses += ObjectAnimator.ofFloat(b, prop, 1f, 1.1f).apply {
                    duration = 500; repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE; start()
                }
            }
        }
    }
    private fun stopPulse() {
        pulses.forEach { it.cancel() }; pulses.clear()
        bubble?.apply { scaleX = 1f; scaleY = 1f }
    }

    // ================= المايك (سماع) =================
    fun listen(task: Task = Task.CHAT) {
        if (recording) { stopRecordingFlag = true; return }
        if (svc.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            say("Raven", "محتاجة صلاحية المايك. افتح التطبيق واضغط 'صلاحية المايك'."); return
        }
        if (keyMissing()) return
        stopSpeaking()
        cancelRec = false; stopRecordingFlag = false
        recording = true
        micBtn?.on = true
        startPulse()
        say("Raven", when (task) { Task.CHAT -> "سامعاك…"; Task.TRANSCRIBE -> "بسجّل وهفرّغ كلامك. اسكت لما تخلّص."; Task.CALL -> "بسمع المحادثة وهلخّصها لما تخلّص." })
        WakeWordService.pause()
        thread {
            val r = VoiceCapture.record(
                maxMs = if (task == Task.CHAT) 15000 else 180000,
                noSpeechMs = 8000,
                endSilenceMs = if (task == Task.CHAT) 1000 else 6000,
                onLevel = { level = it },
                cancelled = { cancelRec || stopRecordingFlag }
            )
            recording = false; level = 0f
            ui.post { micBtn?.on = false; stopPulse() }
            WakeWordService.resume()
            if (cancelRec) return@thread
            when {
                r.error != null -> say("Raven", r.error)
                r.wav == null -> say("Raven", "ماسمعتش حاجة، جرّب تاني.")
                task == Task.CHAT -> ask(null, r.wav)
                task == Task.TRANSCRIBE -> {
                    say("ريو", "(تسجيل صوتي)")
                    runOneShotSync("Transcribe the audio verbatim in its original language. Output only the transcript, no commentary.", listOf(client.wavPart(r.wav), client.text("فرّغ الكلام")), speakIt = false, copy = true)
                    say("Raven", "(النص اتنسخ للحافظة)")
                }
                else -> {
                    say("ريو", "(محادثة مسجّلة)")
                    runOneShotSync("Summarize this conversation: the key points and any action items, concisely, in the speakers' language.", listOf(client.wavPart(r.wav), client.text("لخّص المحادثة")))
                }
            }
        }
    }

    @Volatile private var stopRecordingFlag = false
}
