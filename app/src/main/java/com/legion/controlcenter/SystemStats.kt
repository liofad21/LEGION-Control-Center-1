package com.legion.controlcenter

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import java.io.File

/**
 * قراءات النظام الحقيقية. أي قيمة أندرويد مبيسمحش للتطبيقات تقراها بترجع -1 (والواجهة بتعرض "--") بدل ما نألف رقم.
 *  - CPU: /proc/stat بيتقفل على أندرويد 8+، فبنحسب الحِمل من تردد المعالج الحالي مقابل الأقصى (تقدير).
 *  - GPU: عدّاد Adreno الرسمي لو متاح للقراءة، وإلا تردد الـ GPU، وإلا -1.
 *  - الحرارة: من thermal zones لو متاحة، وإلا حرارة البطارية.
 */
class SystemStats(private val ctx: Context) {

    class Battery(val pct: Int, val charging: Boolean, val full: Boolean, val tempC: Float, val remainingMin: Int, val plugged: Boolean)

    private var lastTotal = 0L
    private var lastIdle = 0L
    private var procStatOk = true

    fun battery(): Battery {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0) lvl * 100 / scale else lvl
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING
        val full = st == BatteryManager.BATTERY_STATUS_FULL
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var rem = -1
        try {
            if (charging && Build.VERSION.SDK_INT >= 28) {
                val ms = bm.computeChargeTimeRemaining()
                if (ms > 0) rem = (ms / 60000).toInt()
            } else if (!plugged) {
                val cnt = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)   // µAh
                val cur = Math.abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) // µA
                if (cnt > 0 && cur > 1000) rem = (cnt * 60 / cur).toInt()
            }
        } catch (_: Exception) {}
        return Battery(pct, charging, full, temp, rem, plugged)
    }

    /** نسبة الرام المستخدمة + (مستخدم، إجمالي) بالجيجا. */
    fun ram(): Triple<Float, Float, Float> {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val used = (mi.totalMem - mi.availMem) / 1e9f
        val tot = mi.totalMem / 1e9f
        return Triple(used * 100f / tot, used, tot)
    }

    fun storage(): Triple<Float, Float, Float> {
        val s = StatFs(Environment.getDataDirectory().path)
        val tot = s.totalBytes / 1e9f
        val used = tot - s.availableBytes / 1e9f
        return Triple(used * 100f / tot, used, tot)
    }

    private fun readLong(path: String): Long = try { File(path).readText().trim().toLong() } catch (_: Exception) { -1 }

    /** حِمل المعالج 0..100 أو -1. */
    fun cpuLoad(): Float {
        if (procStatOk) {
            try {
                val l = File("/proc/stat").bufferedReader().use { it.readLine() }
                val f = l.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
                val idle = f[3] + f[4]; val total = f.sum()
                val first = lastTotal == 0L
                val dt = total - lastTotal; val di = idle - lastIdle
                lastTotal = total; lastIdle = idle
                if (!first && dt > 0) return ((dt - di) * 100f / dt).coerceIn(0f, 100f)
            } catch (_: Exception) { procStatOk = false }
        }
        var sum = 0f; var n = 0
        for (c in 0 until 16) {
            val base = "/sys/devices/system/cpu/cpu$c/cpufreq/"
            val cur = readLong(base + "scaling_cur_freq")
            if (cur < 0) continue
            val max = readLong(base + "cpuinfo_max_freq")
            val min = readLong(base + "cpuinfo_min_freq").coerceAtLeast(0)
            if (max <= min) continue
            sum += ((cur - min).toFloat() / (max - min)).coerceIn(0f, 1f); n++
        }
        return if (n > 0) sum * 100f / n else -1f
    }

    fun gpuLoad(): Float {
        val busy = try { File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readText().trim().removeSuffix("%").trim().toFloat() } catch (_: Exception) { -1f }
        if (busy >= 0) return busy.coerceIn(0f, 100f)
        for (b in listOf("/sys/class/kgsl/kgsl-3d0/devfreq/", "/sys/class/devfreq/kgsl-3d0/")) {
            val cur = readLong(b + "cur_freq"); val max = readLong(b + "max_freq"); val min = readLong(b + "min_freq").coerceAtLeast(0)
            if (cur > 0 && max > min) return ((cur - min).toFloat() / (max - min)).coerceIn(0f, 1f) * 100f
        }
        return -1f
    }

    private fun zoneTemp(vararg keys: String): Float {
        try {
            val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return -1f
            val vals = ArrayList<Float>()
            for (z in zones) {
                val type = try { File(z, "type").readText().trim().lowercase() } catch (_: Exception) { continue }
                if (keys.none { type.contains(it) }) continue
                val t = readLong(File(z, "temp").path)
                if (t > 0) vals.add(if (t > 1000) t / 1000f else t.toFloat())
            }
            return if (vals.isEmpty()) -1f else vals.max()
        } catch (_: Exception) { return -1f }
    }

    fun cpuTemp(): Float = zoneTemp("cpu", "soc", "tsens_tz_sensor")
    fun gpuTemp(): Float = zoneTemp("gpu")

    /** 0..6 زي PowerManager.THERMAL_STATUS_* أو -1. */
    fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= 29) (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else -1

    fun thermalLabel(): String = when (thermalStatus()) {
        0 -> "Cool"; 1 -> "Warm"; 2 -> "Hot"; 3 -> "Very hot"; 4, 5, 6 -> "Critical"; else -> "N/A"
    }

    fun wifiOn(): Boolean = try { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (_: Exception) { false }

    /** اسم الشبكة لو الأندرويد سمح (بيحتاج صلاحية موقع في أغلب النسخ)، وإلا "متصل". */
    @Suppress("DEPRECATION")
    fun wifiName(): String? = try {
        val ssid = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
        if (ssid == null || ssid == "<unknown ssid>" || ssid.isBlank()) null else ssid.trim('"')
    } catch (_: Exception) { null }

    fun internetOk(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun wifiConnected(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun vpnOn(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** مستوى إشارة الواي فاي 0..4 أو -1. */
    @Suppress("DEPRECATION")
    fun wifiLevel(): Int = try {
        val w = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        WifiManager.calculateSignalLevel(w.connectionInfo.rssi, 5)
    } catch (_: Exception) { -1 }

    fun summary(): String {
        val b = battery(); val r = ram(); val s = storage()
        fun f(v: Float) = if (v < 0) "n/a" else "%.0f".format(v)
        return "battery=${b.pct}% charging=${b.charging} battTemp=${"%.1f".format(b.tempC)}C cpuLoad=${f(cpuLoad())}% gpuLoad=${f(gpuLoad())}% " +
                "cpuTemp=${f(cpuTemp())}C gpuTemp=${f(gpuTemp())}C thermal=${thermalLabel()} ram=%.1f/%.1fGB storage=%.0f/%.0fGB wifi=${wifiConnected()} internet=${internetOk()} vpn=${vpnOn()}".format(r.second, r.third, s.second, s.third)
    }
}
