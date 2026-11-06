package com.legion.controlcenter

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * تسجيل صوت حقيقي من المايك (16kHz, mono, PCM16) مع كشف بداية ونهاية الكلام، وبيرجّع ملف WAV جاهز لـ Gemini.
 * ده بيلغي الاعتماد على خدمة SpeechRecognizer اللي كانت بتفشل على كتير من الأجهزة.
 * لازم يتنادى من thread خلفي (بيعمل blocking).
 */
object VoiceCapture {
    const val RATE = 16000

    class Result(val wav: ByteArray?, val error: String?)

    /**
     * @param noSpeechMs لو محدش اتكلم في المدة دي بيرجّع null
     * @param endSilenceMs سكوت بعد الكلام بيعتبر نهاية الجملة
     * @param cancelled بترجّع true لو المستخدم لغى
     */
    fun record(
        maxMs: Int = 15000, noSpeechMs: Int = 7000, endSilenceMs: Int = 1000,
        onLevel: (Float) -> Unit, cancelled: () -> Boolean
    ): Result {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return Result(null, "الجهاز مش بيدعم تسجيل 16kHz")
        val chunk = RATE / 50 // 20ms
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 2 * 8))
        } catch (e: SecurityException) { return Result(null, "صلاحية المايك مش متاحة") }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return Result(null, "المايك مشغول بتطبيق تاني أو غير متاح") }

        val pcm = ByteArrayOutputStream()
        val pre = ArrayDeque<ShortArray>()
        val buf = ShortArray(chunk)
        var floor = -1f
        var speech = false
        var voiced = 0
        var silentMs = 0
        var elapsed = 0
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return Result(null, "المايك مشغول بتطبيق تاني")
            while (elapsed < maxMs && !cancelled()) {
                val n = rec.read(buf, 0, chunk)
                if (n <= 0) { if (n < 0) return Result(null, "خطأ في قراءة المايك ($n)"); continue }
                elapsed += n * 1000 / RATE
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = (sqrt(sum / n) / 32768.0).toFloat()
                onLevel((rms * 9f).coerceIn(0f, 1f))
                if (floor < 0f) floor = rms else if (!speech) floor = floor * 0.95f + rms * 0.05f
                val thr = maxOf(0.015f, floor * 2.8f)
                val chunkCopy = buf.copyOf(n)
                if (!speech) {
                    pre.addLast(chunkCopy); if (pre.size > 15) pre.removeFirst()
                    if (rms > thr) voiced++ else voiced = 0
                    if (voiced >= 3) {
                        speech = true
                        for (c in pre) writeShorts(pcm, c)
                        pre.clear()
                    } else if (elapsed >= noSpeechMs) return Result(null, null)
                } else {
                    writeShorts(pcm, chunkCopy)
                    if (rms > thr * 0.8f) silentMs = 0 else silentMs += n * 1000 / RATE
                    if (silentMs >= endSilenceMs) break
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            onLevel(0f)
        }
        if (!speech || pcm.size() < RATE * 2 / 4) return Result(null, null)
        return Result(wav(pcm.toByteArray()), null)
    }

    private fun writeShorts(o: ByteArrayOutputStream, s: ShortArray) {
        val bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in s) bb.putShort(v)
        o.write(bb.array())
    }

    fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}
