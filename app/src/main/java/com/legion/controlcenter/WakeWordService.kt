package com.legion.controlcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * كلمة التنبيه "RAVEN": خدمة Foreground (بإشعار دائم زي ما أندرويد بيطلب) بتسمع بـ SpeechRecognizer الخاص بالنظام
 * (أوفلاين لو الجهاز عنده حزمة لغة) وبتفتح Raven لما تسمع الكلمة. بتتوقف مؤقتًا لما Raven نفسها بتسجّل عشان المايك مبيتشاركش.
 * حدود: محتاجة خدمة Google/معرّف كلام على الجهاز، وأندرويد بيمنع تشغيلها تلقائيًا بعد إعادة التشغيل (افتح التطبيق مرة).
 */
class WakeWordService : Service() {

    companion object {
        @Volatile private var inst: WakeWordService? = null
        const val CH = "raven_wake"
        fun start(c: Context) {
            val i = Intent(c, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, WakeWordService::class.java)) }
        fun isRunning() = inst != null
        fun pause() { inst?.pauseListening() }
        fun resume() { inst?.resumeListening() }
        private val WAKE = Regex("(r[aeiy]+[vwf]+[aeiy]*n|rayven|ر[اىي]?[فڤ][يىا]?ن|ريڤ|ريف)", RegexOption.IGNORE_CASE)
        private fun status(c: Context, t: String) { c.getSharedPreferences("legion", Context.MODE_PRIVATE).edit().putString("wake_status", t).apply() }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var paused = false
    private var lastWake = 0L
    private var fails = 0
    private var langIdx = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Raven wake word", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this))
            .setContentTitle("Raven بتسمع").setContentText("قول \"Raven\" لتفعيل المساعدة")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(77, n)
        ui.post { listen() }
    }

    private fun listen() {
        if (paused || inst == null) return
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status(this, "مفيش خدمة تعرف على الكلام على الجهاز (ثبّت/فعّل Google أو Speech Services)")
            ui.post { android.widget.Toast.makeText(this, "كلمة RAVEN: مفيش خدمة تعرف على الكلام على التابلت. ثبّت تطبيق Google أو Speech Services.", android.widget.Toast.LENGTH_LONG).show() }
            stopSelf(); return
        }
        langIdx++
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun check(b: Bundle?): Boolean {
                val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
                if (list.any { WAKE.containsMatchIn(it) }) { wake(); return true }
                return false
            }
            override fun onPartialResults(b: Bundle?) { check(b) }
            override fun onResults(b: Bundle?) { fails = 0; if (!check(b)) restart(250) }
            override fun onError(e: Int) {
                fails++
                status(this@WakeWordService, "خطأ التعرف على الكلام: كود $e")
                // 5 = العميل اتلغى، 7 = مفيش كلام (طبيعي)، 8 = مشغول
                restart(if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 200 else minOf(8000L, 800L * fails))
            }
            override fun onReadyForSpeech(p: Bundle?) { status(this@WakeWordService, "سامعة ✓") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (langIdx % 2 == 0) "en-US" else "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        })
    }

    private fun restart(delay: Long) { ui.removeCallbacksAndMessages(null); ui.postDelayed({ listen() }, delay) }

    private fun wake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 4000) return
        lastWake = now
        rec?.cancel()
        ControlAccessibilityService.instance?.openAssistant(true)
            ?: ui.post { android.widget.Toast.makeText(this, "فعّل خدمة إمكانية الوصول عشان Raven تظهر", android.widget.Toast.LENGTH_LONG).show() }
    }

    fun pauseListening() { ui.post { paused = true; ui.removeCallbacksAndMessages(null); rec?.cancel() } }
    fun resumeListening() { ui.postDelayed({ paused = false; listen() }, 1200) }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onDestroy() {
        inst = null
        ui.removeCallbacksAndMessages(null)
        rec?.destroy(); rec = null
        super.onDestroy()
    }
}
