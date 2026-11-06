package com.legion.controlcenter

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * زر زجاجي مرسوم بالكود: دايرة مضيئة (ORB) أو كارت (CARD) أو كبسولة (PILL) أو رمز بس (FLAT).
 * لما يتفعّل بيتحوّل بحركة ناعمة من زجاج غامق لأحمر متوهّج.
 */
class GlassButton(
    ctx: Context,
    private val shape: Shape,
    private var glyph: Bitmap?,
    labelInit: String = "",
    private val orbMaxDp: Float = 58f,
    private val cardHeightDp: Int = 84
) : View(ctx) {

    enum class Shape { ORB, CARD, PILL, FLAT, WIDE }

    private var label: String = labelInit

    fun setLabel(t: String) {
        if (t == label) return
        label = t; contentDescription = t
        if (width > 0) buildLabel(width)
        invalidate()
    }

    private var level = 0f
    private var anim: ValueAnimator? = null
    private val ev = ArgbEvaluator()

    var on: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                anim?.cancel()
                anim = ValueAnimator.ofFloat(level, if (v) 1f else 0f).apply {
                    duration = 220
                    addUpdateListener { level = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val body = RectF()
    private val sheen = RectF()
    private val dst = RectF()
    private var offShader: Shader? = null
    private var onShader: Shader? = null
    private var sheenShader: Shader? = null
    private var layout: StaticLayout? = null
    private var layoutW = 0

    init {
        isClickable = true
        contentDescription = label
    }

    fun setGlyph(b: Bitmap) { glyph = b; invalidate() }

    private fun orbSize(w: Int): Float = min(w - context.dpf(4f), context.dpf(orbMaxDp))

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) context.dpi(orbMaxDp.toInt() + 8)
        else MeasureSpec.getSize(wSpec)
        val h = when (shape) {
            Shape.ORB -> (context.dpf(5f) + orbSize(w) + context.dpf(5f) +
                    (if (label.isNotEmpty()) context.dpf(32f) else 0f)).toInt()
            Shape.CARD -> context.dpi(cardHeightDp)
            Shape.WIDE -> context.dpi(52)
            Shape.PILL -> context.dpi(38)
            Shape.FLAT -> context.dpi(40)
        }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val wf = w.toFloat(); val hf = h.toFloat()
        when (shape) {
            Shape.ORB -> {
                val o = orbSize(w); val cy = context.dpf(5f) + o / 2
                body.set(wf / 2 - o / 2, cy - o / 2, wf / 2 + o / 2, cy + o / 2)
            }
            Shape.CARD, Shape.PILL, Shape.WIDE -> body.set(context.dpf(3f), context.dpf(3f), wf - context.dpf(3f), hf - context.dpf(3f))
            Shape.FLAT -> body.set(0f, 0f, wf, hf)
        }
        offShader = LinearGradient(0f, body.top, 0f, body.bottom, Ui.GLASS_TOP, Ui.GLASS_BOTTOM, Shader.TileMode.CLAMP)
        onShader = if (shape == Shape.ORB) {
            RadialGradient(
                body.centerX(), body.top + body.height() * 0.35f, body.width() * 0.8f,
                intArrayOf(Color.parseColor("#FFFF5560"), Color.parseColor("#FFE30015"), Color.parseColor("#FF7A0008")),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, body.top, 0f, body.bottom,
                intArrayOf(Color.parseColor("#FFD3001A"), Color.parseColor("#FF8A000E"), Color.parseColor("#FF4A0008")),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
        }
        sheen.set(
            body.left + body.width() * 0.18f, body.top + body.height() * 0.06f,
            body.right - body.width() * 0.18f, body.top + body.height() * 0.46f
        )
        sheenShader = LinearGradient(0f, sheen.top, 0f, sheen.bottom, Color.parseColor("#55FFFFFF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        buildLabel(w)
    }

    private fun buildLabel(w: Int) {
        if (label.isEmpty()) { layout = null; return }
        textPaint.textSize = when (shape) {
            Shape.ORB -> context.dpf(11f)
            Shape.CARD -> context.dpf(11f)
            Shape.WIDE -> context.dpf(13f)
            else -> context.dpf(13f)
        }
        layoutW = max(1, w - context.dpi(if (shape == Shape.WIDE) 62 else 6))
        val sp = android.text.SpannableString(label)
        val nl = label.indexOf('\n')
        if (nl >= 0) {
            sp.setSpan(android.text.style.RelativeSizeSpan(0.86f), nl + 1, label.length, 0)
            sp.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FFC9B4B6")), nl + 1, label.length, 0)
        }
        layout = StaticLayout.Builder.obtain(sp, 0, sp.length, textPaint, layoutW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(if (shape == Shape.CARD) 3 else 2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onDraw(c: Canvas) {
        val radius = when (shape) {
            Shape.ORB -> body.width() / 2
            Shape.CARD -> context.dpf(16f)
            Shape.WIDE -> context.dpf(14f)
            Shape.PILL -> body.height() / 2
            Shape.FLAT -> 0f
        }
        if (shape != Shape.FLAT) {
            if (level > 0.01f) {
                glowPaint.strokeWidth = context.dpf(2f)
                for (i in 1..4) {
                    glowPaint.color = Color.argb((level * 80f / i).toInt(), 255, 30, 40)
                    val g = context.dpf(1.7f) * i
                    c.drawRoundRect(body.left - g, body.top - g, body.right + g, body.bottom + g, radius + g, radius + g, glowPaint)
                }
            }
            fillPaint.shader = offShader; fillPaint.alpha = 255
            c.drawRoundRect(body, radius, radius, fillPaint)
            if (level > 0.01f) {
                fillPaint.shader = onShader; fillPaint.alpha = (level * 255).toInt()
                c.drawRoundRect(body, radius, radius, fillPaint)
            }
            if (shape == Shape.ORB) {
                sheenPaint.shader = sheenShader
                c.drawOval(sheen, sheenPaint)
            }
            strokePaint.strokeWidth = context.dpf(1.4f)
            strokePaint.color = ev.evaluate(level, Ui.STROKE_OFF, Ui.STROKE_ON) as Int
            c.drawRoundRect(body, radius, radius, strokePaint)
        }

        val g = glyph
        if (g != null && shape != Shape.PILL) {
            val tint = ev.evaluate(level, Ui.GLYPH_OFF, Color.WHITE) as Int
            glyphPaint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            val s: Float; val cx: Float; val cy: Float
            when (shape) {
                Shape.ORB -> { s = body.width() * 0.52f; cx = body.centerX(); cy = body.centerY() }
                Shape.WIDE -> { s = context.dpf(26f); cx = body.left + context.dpf(26f); cy = body.centerY() }
                Shape.CARD -> { s = context.dpf(26f); cx = body.centerX(); cy = body.top + body.height() * 0.28f }
                else -> { s = min(width, height) * 0.62f; cx = width / 2f; cy = height / 2f }
            }
            val ar = g.width.toFloat() / g.height
            val dw = if (ar >= 1f) s else s * ar
            val dh = if (ar >= 1f) s / ar else s
            dst.set(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2)
            c.drawBitmap(g, null, dst, glyphPaint)
        }

        val l = layout
        if (l != null) {
            textPaint.color = ev.evaluate(level, Color.parseColor("#FFE6D6D6"), Color.WHITE) as Int
            val x = if (shape == Shape.WIDE) width - layoutW - context.dpf(8f) else (width - layoutW) / 2f
            val y = when (shape) {
                Shape.ORB -> body.bottom + context.dpf(4f)
                Shape.CARD -> body.top + body.height() * 0.50f
                else -> (height - l.height) / 2f
            }
            c.save(); c.translate(x, y); l.draw(c); c.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(e)
    }
}
