package com.legion.controlcenter

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.content.res.Configuration
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager

/** كل أفعال الجهاز في مكان واحد، بتستخدمها اللوحة والمساعد الذكي. */
class DeviceActions(private val svc: ControlAccessibilityService) {

    val prefs = svc.getSharedPreferences("legion", Context.MODE_PRIVATE)
    val audio = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private class Tile(val labels: List<String>, val fallback: () -> Intent)

    private val tiles: Map<String, Tile> = mapOf(
        "wifi" to Tile(listOf("Wi-Fi", "Internet", "WLAN", "واي فاي", "الإنترنت")) {
            if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIFI_SETTINGS)
        },
        "bluetooth" to Tile(listOf("Bluetooth", "بلوتوث")) { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) },
        "airplane" to Tile(listOf("Airplane", "Flight mode", "Flight", "وضع الطيران", "الطيران")) { Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
        "hotspot" to Tile(listOf("Hotspot", "Mobile Hotspot", "نقطة اتصال", "نقطة الاتصال")) { Intent(Settings.ACTION_WIRELESS_SETTINGS) },
        "nfc" to Tile(listOf("NFC")) { Intent(Settings.ACTION_NFC_SETTINGS) },
        "location" to Tile(listOf("Location", "الموقع")) { Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
        "battery_saver" to Tile(listOf("Battery Saver", "Power saving", "توفير الطاقة", "توفير")) { Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
        "dark" to Tile(listOf("Dark theme", "Dark mode", "Dark", "الوضع الداكن", "المظهر الداكن")) { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        "eye" to Tile(listOf("Eye comfort", "Reading mode", "Night Light", "Eye protection", "Eye", "حماية العين", "الإضاءة الليلية")) { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        "record" to Tile(listOf("Screen record", "Screen recorder", "Record screen", "تسجيل الشاشة", "مسجل الشاشة")) { Intent(Settings.ACTION_SETTINGS) },
        "cast" to Tile(listOf("Cast", "Screen cast", "Smart View", "Screen mirroring", "إرسال", "بث")) { Intent(Settings.ACTION_CAST_SETTINGS) }
    )

    fun toast(t: String) = android.widget.Toast.makeText(svc, t, android.widget.Toast.LENGTH_SHORT).show()

    fun hasSecure() = svc.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    fun canWrite() = Settings.System.canWrite(svc)
    private val cr get() = svc.contentResolver

    // ---------- الحالات ----------
    fun powerSave() = (svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    fun isDark() = svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    fun dndOn() = (svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

    fun state(key: String): Boolean? = try {
        when (key) {
            "wifi" -> (svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
            "bluetooth" -> (svc.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
            "airplane" -> Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            "rotate" -> Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            "dnd" -> dndOn()
            "hotspot" -> WifiManager::class.java.getMethod("isWifiApEnabled")
                .invoke(svc.applicationContext.getSystemService(Context.WIFI_SERVICE)) as Boolean
            "location" -> Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, 0) != 0
            "nfc" -> NfcAdapter.getDefaultAdapter(svc)?.isEnabled == true
            "battery_saver" -> powerSave()
            "dark" -> isDark()
            "eye" -> Settings.Secure.getInt(cr, "night_display_activated", 0) == 1
            "stay_awake" -> stayAwake()
            "vibrate" -> audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE
            "game_mode" -> gameMode()
            "silent" -> audio.ringerMode == AudioManager.RINGER_MODE_SILENT
            "torch" -> torchOn
            "reading" -> Settings.Secure.getInt(cr, "accessibility_display_daltonizer_enabled", 0) == 1
            "auto_brightness" -> autoBrightness()
            "pc_mode" -> Settings.Global.getInt(cr, "force_desktop_mode_on_external_displays", 0) == 1
            "vpn" -> stats.vpnOn()
            "smart_sound" -> prefs.getBoolean("voice_replies", true)
            else -> null
        }
    } catch (e: Exception) { null }

    // ---------- التبديل ----------
    /** بيدوس على زر النظام الحقيقي، ولو ملقاهوش بيستدعي openFallback بصفحة الإعدادات. */
    fun sys(key: String, openFallback: (Intent) -> Unit) {
        val t = tiles[key] ?: return
        svc.clickSystemTile(t.labels) { openFallback(t.fallback()) }
    }

    private fun secureOr(direct: () -> Unit, key: String, openFallback: (Intent) -> Unit) {
        if (hasSecure()) { try { direct(); return } catch (_: Exception) {} }
        sys(key, openFallback)
    }

    fun toggle(key: String, openFallback: (Intent) -> Unit) {
        when (key) {
            "rotate" -> {
                if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return }
                val cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1 - cur)
            }
            "dnd" -> {
                val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) { openFallback(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return }
                nm.setInterruptionFilter(if (dndOn()) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
            "location" -> secureOr({
                Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, if (state("location") == true) 0 else 3)
            }, key, openFallback)
            "battery_saver" -> secureOr({
                Settings.Global.putInt(cr, "low_power", if (powerSave()) 0 else 1)
            }, key, openFallback)
            "dark" -> secureOr({
                Settings.Secure.putInt(cr, "ui_night_mode", if (isDark()) 1 else 2)
            }, key, openFallback)
            "silent" -> try {
                audio.ringerMode = if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_SILENT
            } catch (e: SecurityException) { openFallback(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            "vibrate" -> toggleVibrate()
            "stay_awake" -> toggleStayAwake()
            "torch" -> toggleTorch()
            "auto_brightness" -> setAutoBrightness(!autoBrightness())
            "smart_sound" -> prefs.edit().putBoolean("voice_replies", !prefs.getBoolean("voice_replies", true)).apply()
            "reading" -> secureOr({
                val on = state("reading") != true
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer", 0)       // 0 = رمادي (ورق)
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", if (on) 1 else 0)
            }, key, openFallback)
            "pc_mode" -> if (hasSecure()) {
                val on = state("pc_mode") != true
                Settings.Global.putInt(cr, "force_desktop_mode_on_external_displays", if (on) 1 else 0)
                Settings.Global.putInt(cr, "enable_freeform_support", if (on) 1 else 0)
                toast(if (on) "وضع الكمبيوتر شغّال: وصّل شاشة خارجية (قد تحتاج إعادة تشغيل مرة واحدة)" else "وضع الكمبيوتر اتقفل")
            } else openFallback(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "vpn" -> openFallback(Intent("android.settings.VPN_SETTINGS"))
            "privacy" -> openFallback(Intent(if (Build.VERSION.SDK_INT >= 33) "android.settings.PRIVACY_SETTINGS" else Settings.ACTION_PRIVACY_SETTINGS))
            "sounds" -> openFallback(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS))
            "qr" -> scanQr()
            else -> sys(key, openFallback)
        }
    }

    // ---------- كشاف / QR ----------
    @Volatile var torchOn = false
    private var torchCb: android.hardware.camera2.CameraManager.TorchCallback? = null
    private fun camMgr() = svc.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    private fun torchId(): String? = try {
        camMgr().cameraIdList.firstOrNull {
            val ch = camMgr().getCameraCharacteristics(it)
            ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (_: Exception) { null }

    fun watchTorch() {
        if (torchCb != null) return
        torchCb = object : android.hardware.camera2.CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == torchId()) torchOn = enabled }
        }
        try { camMgr().registerTorchCallback(torchCb!!, null) } catch (_: Exception) {}
    }

    fun toggleTorch() {
        val id = torchId() ?: run { toast("مفيش فلاش في الجهاز"); return }
        try { camMgr().setTorchMode(id, !torchOn); torchOn = !torchOn } catch (e: Exception) { toast("تعذّر تشغيل الكشاف") }
    }

    fun scanQr() {
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("googleapp://lens")),
            Intent("com.google.zxing.client.android.SCAN"),
            Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        for (i in tries) {
            try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        toast("مفيش تطبيق كاميرا/Lens لقراءة QR")
    }

    val stats = SystemStats(svc)

    /** للمساعد: يضبط الحالة المطلوبة بس لو مختلفة. */
    fun set(key: String, enabled: Boolean): String {
        if (!tiles.containsKey(key) && key !in listOf("rotate", "dnd", "torch", "silent", "vibrate", "reading", "auto_brightness", "stay_awake")) return "إعداد غير معروف: $key"
        val cur = state(key)
        if (cur != null && cur == enabled) return "$key already ${if (enabled) "on" else "off"}"
        toggle(key) { i -> try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
        return "tapped $key toggle (state may take a second to change)"
    }

    // ---------- سطوع وصوت ----------
    fun brightness() = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
    fun setBrightness(v: Int): Boolean {
        if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return false }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255))
        return true
    }
    fun autoBrightness() = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    fun setAutoBrightness(on: Boolean) {
        if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }
    fun maxVolume() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    fun setVolume(v: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, maxVolume()), 0)

    // ---------- معدل التحديث ----------
    fun currentHz(): Float {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return wm.defaultDisplay.refreshRate
    }
    fun setRefresh(hz: Float): Boolean {
        if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return false }
        return try {
            Settings.System.putFloat(cr, "peak_refresh_rate", hz)
            Settings.System.putFloat(cr, "min_refresh_rate", hz)
            true
        } catch (e: Exception) { false }
    }

    // ---------- أوضاع الأداء ----------
    private fun powerSaveSet(on: Boolean, openFallback: (Intent) -> Unit) {
        if (powerSave() == on) return
        if (hasSecure()) { try { Settings.Global.putInt(cr, "low_power", if (on) 1 else 0); return } catch (_: Exception) {} }
        if (on) openFallback(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) else sys("battery_saver", openFallback)
    }

    fun setPerformanceMode(i: Int, openFallback: (Intent) -> Unit) {
        val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (i != 3 && prefs.getBoolean("dnd_by_us", false) && nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            prefs.edit().putBoolean("dnd_by_us", false).apply()
        }
        prefs.edit().putInt("perf", i).apply()
        when (i) {
            0 -> powerSaveSet(true, openFallback)
            1 -> powerSaveSet(false, openFallback)
            2 -> { powerSaveSet(false, openFallback); setRefresh(144f) }
            3 -> {
                powerSaveSet(false, openFallback); setRefresh(144f)
                if (nm.isNotificationPolicyAccessGranted && !dndOn()) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    prefs.edit().putBoolean("dnd_by_us", true).apply()
                }
            }
        }
    }

    // ---------- صفحة الأزرار التانية ----------
    fun stayAwake() = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60000) >= 600000

    fun toggleStayAwake() {
        if (!canWrite()) { toast("فعّل صلاحية تعديل الإعدادات من التطبيق"); return }
        if (stayAwake()) {
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, prefs.getInt("timeout_prev", 60000))
        } else {
            prefs.edit().putInt("timeout_prev", Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60000)).apply()
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 1800000)
        }
    }

    fun toggleVibrate() {
        try {
            audio.ringerMode = if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) AudioManager.RINGER_MODE_NORMAL
            else AudioManager.RINGER_MODE_VIBRATE
        } catch (e: SecurityException) { toast("فعّل صلاحية عدم الإزعاج من التطبيق") }
    }

    /** حالة الحرارة من النظام (مفيش RPM للمروحة على أندرويد). */
    fun thermal(): String {
        if (Build.VERSION.SDK_INT < 29) return "N/A"
        return when ((svc.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool"
            PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "Hot"
            PowerManager.THERMAL_STATUS_SEVERE -> "Very Hot"
            else -> "Critical"
        }
    }

    // ---------- الألعاب ----------
    class AppEntry(val label: String, val pkg: String, val icon: Drawable)

    /** (ألعاب فعلًا؟، القايمة). لو مفيش ألعاب متعرّف عليها بنرجّع كل التطبيقات. */
    @Suppress("DEPRECATION")
    fun games(): Pair<Boolean, List<AppEntry>> {
        val pm = svc.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(q, 0).filter { it.activityInfo.packageName != svc.packageName }
        val games = all.filter {
            val ai = it.activityInfo.applicationInfo
            ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        }
        val src = if (games.isNotEmpty()) games else all
        val list = src.map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        return Pair(games.isNotEmpty(), list)
    }

    fun launch(pkg: String): Boolean {
        val i = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    }

    fun gameMode() = prefs.getBoolean("game_mode", false)

    /** وضع اللعب: عدم إزعاج + 144Hz + عدّاد الأداء. وبيرجّع كل حاجة لما تقفله. */
    fun setGameMode(on: Boolean) {
        val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (on) {
            if (nm.isNotificationPolicyAccessGranted && !dndOn()) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                prefs.edit().putBoolean("game_dnd", true).apply()
            }
            setRefresh(144f)
            svc.setMonitor(true)
        } else {
            if (prefs.getBoolean("game_dnd", false) && nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            prefs.edit().putBoolean("game_dnd", false).apply()
            svc.setMonitor(false)
        }
        prefs.edit().putBoolean("game_mode", on).apply()
    }

    // ---------- الميديا ----------
    fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------- أفعال النظام ----------
    fun global(action: Int, delay: Long = 0) { svc.postDelayed(delay) { svc.performGlobalAction(action) } }

    fun openApp(name: String): String {
        val pm = svc.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(q, 0)
        val n = name.trim().lowercase()
        val hit = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == n }
            ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) }
            ?: return "لقيتش تطبيق اسمه $name"
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "مقدرتش أفتحه"
        svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "opened ${hit.loadLabel(pm)}"
    }

    fun status(): String {
        val i = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val mi = ActivityManager.MemoryInfo()
        (svc.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val s = StatFs(Environment.getDataDirectory().path)
        val tot = s.totalBytes / 1e9
        return stats.summary() + " fg=${svc.foregroundPackage()} " + "battery=$lvl% charging=$chg temp=${temp}C ram_used=%.1f/%.1fGB storage_used=%.1f/%.0fGB brightness=%d%% volume=%d%% refresh=%.0fHz wifi=%s bluetooth=%s".format(
            (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9, tot - s.availableBytes / 1e9, tot,
            brightness() * 100 / 255, volume() * 100 / maxOf(1, maxVolume()), currentHz(), state("wifi"), state("bluetooth"))
    }

    fun globalAction(name: String): String {
        val a = when (name) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return "غير مدعوم"
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return "غير مدعوم"
            "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return "أمر غير معروف"
        }
        // نستنى شوية عشان لوحة المساعد متظهرش في اللقطة
        global(a, 500)
        return "done $name"
    }
}
