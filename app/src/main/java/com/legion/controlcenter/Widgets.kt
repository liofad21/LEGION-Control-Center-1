package com.legion.controlcenter

import android.content.Context
import android.graphics.*
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** سلايدر أحمر متوهّج للسطوع والصوت. */
class GlowSlider(ctx: Context) : View(ctx) {
    var max = 100
    var progress = 0
        set(v) { field = v.coerceIn(0, max); invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var isDragging = false
        private set

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(40))
    }

    override fun onDraw(c: Canvas) {
        val pad = context.dpf(14f)
        val th = context.dpf(12f)
        val cy = height / 2f
        val f = if (max == 0) 0f else progress.toFloat() / max
        val tx = pad + (width - 2 * pad) * f

        rect.set(pad, cy - th / 2, width - pad, cy + th / 2)
        p.shader = null; p.style = Paint.Style.FILL; p.color = Color.parseColor("#FF0E0304")
        c.drawRoundRect(rect, th / 2, th / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1f); p.color = Color.parseColor("#88FF3A48")
        c.drawRoundRect(rect, th / 2, th / 2, p)

        rect.set(pad, cy - th / 2, max(tx, pad + th), cy + th / 2)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(pad, 0f, max(tx, pad + th), 0f, Color.parseColor("#FF5E000E"), Color.parseColor("#FFFF2A3A"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, th / 2, th / 2, p)
        p.shader = null

        // توهّج وإبهام السلايدر
        p.style = Paint.Style.FILL
        p.color = Color.argb(70, 255, 30, 45)
        c.drawCircle(tx, cy, context.dpf(17f), p)
        p.shader = RadialGradient(tx - context.dpf(3f), cy - context.dpf(3f), context.dpf(13f),
            intArrayOf(Color.WHITE, Color.parseColor("#FFF0B4BA")), null, Shader.TileMode.CLAMP)
        c.drawCircle(tx, cy, context.dpf(11f), p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.5f); p.color = Color.parseColor("#FFFF4A58")
        c.drawCircle(tx, cy, context.dpf(11f), p)
    }

    private fun update(x: Float) {
        val pad = context.dpf(14f)
        val f = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val v = (f * max).roundToInt()
        if (v != progress) { progress = v; onChange?.invoke(v) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); isDragging = true; update(e.x) }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return true
    }
}

/** شريط تقدّم رفيع (التخزين / الرام / الميديا). */
class MiniBar(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        p.shader = null; p.style = Paint.Style.FILL; p.color = Color.parseColor("#FF1A0507")
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Color.parseColor("#66FF3A48")
        c.drawRoundRect(r, h / 2, h / 2, p)
        val w = max(width * fraction, if (fraction > 0f) h else 0f)
        if (w > 0f) {
            r.set(0f, 0f, w, h)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#FF8A0012"), Color.parseColor("#FFFF2A3A"), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.shader = null
        }
    }
}

/** إطار نيون أحمر للأقسام (حدود مزدوجة مضيئة) بدل الصور المقصوصة. */
class NeonDrawable(private val ctx: Context, private val radiusDp: Float = 16f, private val fillAlpha: Int = 0xCC) : android.graphics.drawable.Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds
        val rad = ctx.dpf(radiusDp)
        val g = ctx.dpf(5f)
        r.set(b.left + g, b.top + g, b.right - g, b.bottom - g)
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(fillAlpha, 24, 4, 8), Color.argb(fillAlpha, 6, 1, 3), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, fill)
        for (i in 4 downTo 1) {
            glow.strokeWidth = ctx.dpf(1.2f) * i
            glow.color = Color.argb(14 + (4 - i) * 6, 255, 20, 40)
            c.drawRoundRect(r, rad, rad, glow)
        }
        line.strokeWidth = ctx.dpf(1.6f); line.color = Color.parseColor("#FFFF2A3C")
        c.drawRoundRect(r, rad, rad, line)
        line.strokeWidth = ctx.dpf(0.7f); line.color = Color.parseColor("#88FF9AA2")
        r.inset(ctx.dpf(2.5f), ctx.dpf(2.5f))
        c.drawRoundRect(r, rad - ctx.dpf(2f), rad - ctx.dpf(2f), line)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** الخلفية: قوس الورد والجناحين والأضلاع الحمرا، بتتمدّد على أي مقاس. left = بداية منطقة اللوحة. */
class BgView(ctx: Context, private val left: Int) : View(ctx) {
    private val top = Glyphs.get(ctx, R.drawable.bg_top)
    private val bot = Glyphs.get(ctx, R.drawable.bg_bottom)
    private val bl = Glyphs.get(ctx, R.drawable.bg_left)
    private val br = Glyphs.get(ctx, R.drawable.bg_right)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val s = RectF(); private val d = RectF()
    var topH = 0f; private set
    var botH = 0f; private set

    override fun onDraw(c: Canvas) {
        val w = (width - left).toFloat(); val h = height.toFloat()
        val x0 = left.toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#FF0D0204"), Color.parseColor("#FF050001"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), h, p); p.shader = null
        val k = w / top.width
        topH = top.height * k; botH = bot.height * k
        val sideW = bl.width * k
        d.set(x0, topH, x0 + sideW, h - botH); c.drawBitmap(bl, null, d, p)
        d.set(x0 + w - sideW, topH, x0 + w, h - botH); c.drawBitmap(br, null, d, p)
        d.set(x0, 0f, x0 + w, topH); c.drawBitmap(top, null, d, p)
        d.set(x0, h - botH, x0 + w, h); c.drawBitmap(bot, null, d, p)
    }
}

/** البنت (خلفية شفافة) مرسومة فوق كل حاجة وبتظهر كلها من فوق لتحت. مبتاخدش لمس. */
class GirlView(ctx: Context) : View(ctx) {
    private val bmp = Glyphs.get(ctx, R.drawable.girl)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val d = RectF()
    init { isClickable = false; isFocusable = false }
    /** عرضها بالبكسل لو رسمناها بطول الشاشة كله. */
    fun widthFor(h: Int): Int = (h * bmp.width.toFloat() / bmp.height).toInt()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = h * bmp.width / bmp.height
        d.set(0f, 0f, w, h)
        c.drawBitmap(bmp, null, d, paint)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean = false
}

/** عدّاد دايري (CPU / GPU / RAM / التخزين) بقيمة حقيقية. value = -1 معناه مش متاح. */
class RingGauge(ctx: Context, private val label: String) : View(ctx) {
    var value = 0f
    var sub = ""
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val oval = RectF()

    fun set(v: Float, subText: String) {
        sub = subText
        val target = if (v < 0) 0f else v.coerceIn(0f, 100f)
        value = v
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 500; addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val sw = MeasureSpec.getSize(w)
        setMeasuredDimension(sw, (min(sw, context.dpi(116)) + context.dpf(22f)).toInt())
    }

    override fun onDraw(c: Canvas) {
        val d = min(width.toFloat(), context.dpf(116f))
        val cx = width / 2f; val st = context.dpf(7f)
        val cy = d / 2f
        oval.set(cx - d / 2 + st, cy - d / 2 + st, cx + d / 2 - st, cy + d / 2 - st)
        p.strokeWidth = st; p.shader = null; p.color = Color.parseColor("#FF2A0A10")
        c.drawArc(oval, 0f, 360f, false, p)
        if (shown > 0.5f) {
            p.shader = SweepGradient(cx, cy, intArrayOf(Color.parseColor("#FF7A0010"), Color.parseColor("#FFFF3040"), Color.parseColor("#FF7A0010")), null)
            c.save(); c.rotate(-90f, cx, cy)
            p.setShadowLayer(context.dpf(6f), 0f, 0f, Color.parseColor("#AAFF1A2A"))
            c.drawArc(oval, 0f, 360f * shown / 100f, false, p)
            p.clearLayerDefinition(); c.restore(); p.shader = null
        }
        t.typeface = Typeface.create("sans-serif", Typeface.NORMAL); t.color = Color.parseColor("#FFE6D0D2")
        t.textSize = context.dpf(12f); c.drawText(label, cx, cy - d * 0.17f, t)
        t.color = Color.WHITE; t.textSize = context.dpf(22f)
        c.drawText(if (value < 0) "--" else "${shown.roundToInt()}%", cx, cy + d * 0.12f, t)
        t.color = Color.parseColor("#FFFFB0B6"); t.textSize = context.dpf(11.5f)
        c.drawText(sub, cx, d + context.dpf(14f), t)
    }

    private fun Paint.clearLayerDefinition() = clearShadowLayer()
}

/** موجة صوت: بتتحرّك بس لما المساعدة بتسمع أو بتتكلم (المستوى الحقيقي للصوت). */
class WaveView(ctx: Context) : View(ctx) {
    @Volatile var level = 0f      // 0..1
    var active = false
        set(v) { field = v; if (v) post(tick) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val hist = FloatArray(64)
    private var phase = 0f
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            phase += 0.35f
            System.arraycopy(hist, 1, hist, 0, hist.size - 1)
            val l = if (active) max(level, 0.08f + 0.06f * Math.sin(phase.toDouble()).toFloat().let { abs(it) }) else 0f
            hist[hist.size - 1] = l
            invalidate()
            if (active || hist.any { it > 0.01f }) postDelayed(this, 40)
        }
    }
    override fun onDraw(c: Canvas) {
        val n = hist.size; val step = width.toFloat() / n; val cy = height / 2f
        p.strokeWidth = step * 0.5f
        for (i in 0 until n) {
            val dist = abs(i - n / 2f) / (n / 2f)
            val a = (1f - dist * 0.8f)
            val h = (max(hist[i], 0.03f) * height * 0.95f * a).coerceAtLeast(context.dpf(2f))
            p.color = Color.argb((255 * (0.35f + 0.65f * a)).toInt(), 255, 40, 55)
            val x = step * i + step / 2
            c.drawLine(x, cy - h / 2, x, cy + h / 2, p)
        }
    }
}

/** شريط البطارية: بيتحرك بلمعة لما بتشحن. */
class ChargeBar(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) {
            val t = v.coerceIn(0f, 1f)
            anim?.cancel()
            anim = ValueAnimator.ofFloat(field, t).apply { duration = 500; addUpdateListener { field2 = it.animatedValue as Float; invalidate() }; start() }
            field = t
        }
    var charging = false
        set(v) { field = v; if (v) { shine.start() } else { shine.cancel(); invalidate() } }
    private var field2 = 0f
    private var anim: ValueAnimator? = null
    private var sh = 0f
    private val shine = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { sh = it.animatedValue as Float; invalidate() }
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDetachedFromWindow() { shine.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        r.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL; p.shader = null; p.color = Color.parseColor("#FF160407")
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1f); p.color = Color.parseColor("#88B0A0A0")
        c.drawRoundRect(r, h / 2, h / 2, p)
        val fw = max(w * field2, if (field2 > 0f) h else 0f)
        r.set(context.dpf(2f), context.dpf(2f), fw - context.dpf(2f), h - context.dpf(2f))
        if (r.width() > 0) {
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 0f, fw, 0f, Color.parseColor("#FF9A0014"), Color.parseColor("#FFFF4050"), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
            if (charging) {
                val x = r.left + (r.width() + h * 3) * sh - h * 3
                p.shader = LinearGradient(x, 0f, x + h * 3, 0f, Color.TRANSPARENT, Color.parseColor("#99FFFFFF"), Shader.TileMode.CLAMP)
                c.save(); c.clipRect(r); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p); c.restore()
            }
            p.shader = null
        }
    }
}
