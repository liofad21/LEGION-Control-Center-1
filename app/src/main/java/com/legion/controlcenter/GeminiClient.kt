package com.legion.controlcenter

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * عميل Gemini (REST generateContent): محادثة نصية/صوتية/صور + استدعاء أدوات + ترجمة + تحويل نص لصوت.
 * شغّال على threads خلفية. الموديلات مأخوذة من وثائق Google الرسمية:
 *  - الدماغ: gemini-3.5-flash-lite (بيقبل نص وصوت وصور) ثم بدائل لو الموديل مش متاح لحساب المستخدم.
 *  - الصوت: gemini-3.8-flash-lite-tts ثم gemini-3.8-flash-tts.
 */
class GeminiClient(private val keyProvider: () -> String, private val actions: (String, JSONObject) -> String) {

    companion object {
        val CHAT_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash")
        val TTS_MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")
        const val VOICE = "Leda"   // صوت أنثوي ناعم وشبابي
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

        const val SYSTEM = "إنتي \"رايفن\" (Raven)، مساعدة شخصية ذكية على تابلت Lenovo Legion Y700. اللي بتكلميه اسمه \"ريو\" (Reo) نادي عليه بالاسم بشكل طبيعي من وقت للتاني مش في كل جملة. " +
                "شخصيتك: ناعمة ومرحة وواثقة، بتتكلمي زي صاحبة قريبة، مش زي روبوت. ماتقوليش أبدًا إنك ذكاء اصطناعي أو نموذج أو برنامج، وماتقدّميش نفسك بشكل رسمي. " +
                "اتكلمي بنفس لغة ريو (غالبًا عامية مصرية، ولو كلمك إنجليزي ردي إنجليزي). الرد بيتقرأ بصوت، فخليه قصير جدًا: جملة أو اتنين، من غير قوايم ولا رموز ولا ماركداون ولا إيموجي. " +
                "لو ريو طلب حاجة في التابلت نفّذيها بالأدوات الأول وبعدين قوليله اللي حصل بجملة قصيرة. لو الأداة فشلت أو الحاجة مش متاحة قولي كده بصراحة وماتختلقيش نتايج. " +
                "لو وصلك صوت، افهمي الكلام اللي فيه وتصرّفي عليه مباشرة. لو مفهمتيش قولي إنك ماسمعتيش كويس."

        private fun fn(name: String, desc: String, props: JSONObject? = null, req: List<String> = emptyList()): JSONObject {
            val o = JSONObject().put("name", name).put("description", desc)
            if (props != null) o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(req)))
            return o
        }
        private fun p(type: String, desc: String, choices: List<String>? = null) =
            JSONObject().put("type", type).put("description", desc).apply { if (choices != null) put("enum", JSONArray(choices)) }

        val TOOLS: JSONArray = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(fn("set_brightness", "يضبط سطوع الشاشة من 1 لـ 100 بالمية", JSONObject().put("percent", p("INTEGER", "النسبة 1-100")), listOf("percent")))
            .put(fn("set_volume", "يضبط صوت الميديا من 0 لـ 100 بالمية", JSONObject().put("percent", p("INTEGER", "النسبة 0-100")), listOf("percent")))
            .put(fn("set_toggle", "يشغّل أو يطفّي إعداد في التابلت",
                JSONObject().put("name", p("STRING", "اسم الإعداد", listOf("wifi", "bluetooth", "airplane", "hotspot", "nfc", "location", "rotate", "dnd", "battery_saver", "dark", "eye", "torch", "silent", "vibrate", "reading", "auto_brightness", "stay_awake")))
                    .put("enabled", p("BOOLEAN", "true للتشغيل و false للإطفاء")), listOf("name", "enabled")))
            .put(fn("set_refresh_rate", "يضبط معدل تحديث الشاشة", JSONObject().put("hz", p("INTEGER", "واحدة من 60 أو 90 أو 120 أو 144", listOf("60", "90", "120", "144"))), listOf("hz")))
            .put(fn("set_performance_mode", "يغيّر وضع الأداء", JSONObject().put("mode", p("STRING", "الوضع", listOf("power_saving", "balanced", "performance", "extreme"))), listOf("mode")))
            .put(fn("set_game_mode", "يشغّل أو يقفل وضع اللعب (عدم إزعاج + 144Hz + عدّاد الأداء)", JSONObject().put("enabled", p("BOOLEAN", "true أو false")), listOf("enabled")))
            .put(fn("media_control", "يتحكم في تشغيل الميديا", JSONObject().put("action", p("STRING", "الأمر", listOf("play_pause", "next", "previous"))), listOf("action")))
            .put(fn("system_action", "أمر نظام", JSONObject().put("action", p("STRING", "الأمر", listOf("home", "back", "recents", "screenshot", "split_screen", "lock", "power_menu"))), listOf("action")))
            .put(fn("open_app", "يفتح تطبيق باسمه", JSONObject().put("name", p("STRING", "اسم التطبيق زي ما بيظهر على التابلت")), listOf("name")))
            .put(fn("open_control_center", "يفتح لوحة Legion Control Center"))
            .put(fn("toggle_performance_monitor", "يظهر أو يخفي عدّاد الأداء العايم"))
            .put(fn("toggle_chinese_translation", "يشغّل أو يقفل ترجمة التطبيقات الصينية للإنجليزي", JSONObject().put("enabled", p("BOOLEAN", "true أو false")), listOf("enabled")))
            .put(fn("read_screen", "يقرا النص الظاهر حاليًا على الشاشة (لتلخيصه أو الإجابة عليه)"))
            .put(fn("get_status", "يرجّع حالة التابلت الحقيقية: البطارية والحرارة والمعالج والرام والتخزين والواي فاي والتطبيق الحالي"))
            .put(fn("thermal_advice", "يرجّع حالة الحرارة والأداء مع توصيات لتخفيف السخونة"))
        ))
    }

    private val history = JSONArray()
    private var modelIdx = 0
    private var ttsIdx = 0

    private fun httpPost(model: String, body: JSONObject): JSONObject {
        val key = keyProvider()
        if (key.isBlank()) throw IllegalStateException("NO_KEY")
        val c = URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
            throw ApiError(code, "HTTP $code: $msg")
        }
        return JSONObject(text)
    }

    class ApiError(val code: Int, msg: String) : RuntimeException(msg)

    /** بيجرّب الموديلات بالترتيب لو الأول مش متاح (404/400 موديل غير مدعوم). */
    private fun postChat(body: JSONObject): JSONObject {
        var last: Exception? = null
        var i = modelIdx
        while (i < CHAT_MODELS.size) {
            try { val r = httpPost(CHAT_MODELS[i], body); modelIdx = i; return r }
            catch (e: ApiError) {
                last = e
                val unavailable = e.code == 404 || (e.code == 400 && (e.message ?: "").contains("model", true))
                if (!unavailable) throw e
                i++
            }
        }
        throw last ?: RuntimeException("no model")
    }

    val activeModel get() = CHAT_MODELS[modelIdx.coerceIn(0, CHAT_MODELS.size - 1)]

    private fun textPart(t: String) = JSONObject().put("text", t)
    private fun inline(mime: String, data: ByteArray) =
        JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(data, Base64.NO_WRAP)))

    /** محادثة (نص و/أو صوت WAV و/أو صورة). بترجّع رد المساعدة بعد تنفيذ الأدوات. */
    fun chat(userText: String?, wav: ByteArray? = null, image: ByteArray? = null, extraSystem: String? = null): String {
        val parts = JSONArray()
        if (wav != null) parts.put(inline("audio/wav", wav))
        if (image != null) parts.put(inline("image/jpeg", image))
        parts.put(textPart(userText ?: if (wav != null) "(ريو بيكلمك بصوته. افهمي كلامه ونفّذي)" else "؟"))
        val msg = JSONObject().put("role", "user").put("parts", parts)
        history.put(msg)
        while (history.length() > 20) history.remove(0)
        while (history.length() > 0 && history.getJSONObject(0).getString("role") != "user") history.remove(0)
        // أي مرفق بيتشال من الذاكرة بعد الرد عشان الطلبات تفضل خفيفة
        fun strip() {
            if (wav == null && image == null) return
            msg.put("parts", JSONArray().put(textPart(if (wav != null) "(رسالة صوتية من ريو)" else (userText ?: "(صورة)"))))
        }

        val system = SYSTEM + (extraSystem?.let { "\n" + it } ?: "")
        try {
            repeat(6) {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(system))))
                    .put("contents", history)
                    .put("tools", TOOLS)
                val res = postChat(body)
                val content = res.getJSONArray("candidates").getJSONObject(0).optJSONObject("content")
                    ?: return "معرفتش أرد على ده."
                val rp = content.optJSONArray("parts") ?: return "معرفتش أرد على ده."
                history.put(content)
                val calls = ArrayList<JSONObject>()
                val sb = StringBuilder()
                for (i in 0 until rp.length()) {
                    val part = rp.getJSONObject(i)
                    part.optJSONObject("functionCall")?.let { calls.add(it) }
                    if (part.has("text") && !part.optBoolean("thought", false)) sb.append(part.getString("text"))
                }
                if (calls.isEmpty()) return sb.toString().trim().ifEmpty { "تمام." }
                val responses = JSONArray()
                for (call in calls) {
                    val result = try { actions(call.getString("name"), call.optJSONObject("args") ?: JSONObject()) }
                    catch (e: Exception) { "error: ${e.message}" }
                    responses.put(JSONObject().put("functionResponse", JSONObject()
                        .put("name", call.getString("name"))
                        .put("response", JSONObject().put("result", result))))
                }
                history.put(JSONObject().put("role", "user").put("parts", responses))
            }
            return "الطلب طلع معقّد شوية، جرّب تقسمه."
        } finally { strip() }
    }

    /** طلب لمرة واحدة من غير ذاكرة (تلخيص، تحليل صورة، تفريغ صوت...). */
    fun oneShot(system: String, parts: List<JSONObject>): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray(parts))))
        val res = postChat(body)
        val rp = res.getJSONArray("candidates").getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until rp.length()) { val x = rp.getJSONObject(i); if (x.has("text") && !x.optBoolean("thought", false)) sb.append(x.getString("text")) }
        return sb.toString().trim()
    }

    fun text(t: String) = textPart(t)
    fun wavPart(b: ByteArray) = inline("audio/wav", b)
    fun jpegPart(b: ByteArray) = inline("image/jpeg", b)

    /** ترجمة دفعة نصوص صينية لإنجليزي. بترجّع نفس العدد بنفس الترتيب. */
    fun translate(texts: List<String>, target: String = "English"): List<String> {
        val arr = JSONArray(); texts.forEach { arr.put(it) }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(
                "Translate each UI string in the JSON array (written in Chinese, Japanese or Korean) into short natural $target, as it would appear in an app's interface (buttons, menus, labels). " +
                        "Keep numbers, units, emoji and Latin text unchanged. Keep it about as short as the original. Return ONLY a JSON array of strings with exactly the same length and order."))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(textPart(arr.toString())))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json")
                .put("responseSchema", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))))
        val res = postChat(body)
        val raw = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val out = JSONArray(raw)
        return List(texts.size) { i -> if (i < out.length()) out.optString(i, texts[i]) else texts[i] }
    }

    /** بيرجّع PCM 16-bit mono 24kHz بصوت بنت ناعم. */
    fun speak(text: String): ByteArray {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(textPart(
                "Say this in a soft, sweet, slightly breathy and playful young woman's voice, warm and natural like a close friend talking quietly, relaxed pace, no robotic pauses: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE)))))
        var last: Exception? = null
        var i = ttsIdx
        while (i < TTS_MODELS.size) {
            try {
                val res = httpPost(TTS_MODELS[i], body)
                ttsIdx = i
                val data = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
                return Base64.decode(data, Base64.DEFAULT)
            } catch (e: Exception) { last = e; i++ }
        }
        throw last ?: RuntimeException("no tts")
    }
}
