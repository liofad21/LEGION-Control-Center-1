package com.legion.controlcenter

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Ui {
    val RED = Color.parseColor("#FF1F2D")
    val RED_SOFT = Color.parseColor("#FF5A64")
    val GLYPH_OFF = Color.parseColor("#F3DADA")
    val TEXT_DIM = Color.parseColor("#C9B4B6")
    val GLASS_TOP = Color.parseColor("#FF2A0B0F")
    val GLASS_BOTTOM = Color.parseColor("#FF080102")
    val STROKE_OFF = Color.parseColor("#99B03A44")
    val STROKE_ON = Color.parseColor("#FFFF8C94")

    /** خلفية زجاجية بحدود حمرا مضيئة (بدل الصور المقصوصة). */
    fun glass(ctx: Context, radiusDp: Float, strong: Boolean = false): Drawable {
        val r = ctx.dpf(radiusDp)
        val glow = GradientDrawable().apply {
            cornerRadius = r + ctx.dpf(2f)
            setColor(Color.TRANSPARENT)
            setStroke(ctx.dpi(3), Color.parseColor(if (strong) "#40FF1A2A" else "#22FF1A2A"))
        }
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#EE1A0507"), Color.parseColor("#F2050102"))
        ).apply {
            cornerRadius = r
            setStroke(ctx.dpi(1).coerceAtLeast(1), Color.parseColor(if (strong) "#E0FF2A3A" else "#99FF2A3A"))
        }
        val inset = ctx.dpi(2)
        return LayerDrawable(arrayOf(glow, base)).apply { setLayerInset(1, inset, inset, inset, inset) }
    }
}

object Glyphs {
    private val cache = HashMap<Int, Bitmap>()

    fun get(ctx: Context, res: Int): Bitmap = cache.getOrPut(res) {
        val o = BitmapFactory.Options().apply { inScaled = false }
        BitmapFactory.decodeResource(ctx.resources, res, o)
    }

    val play: Bitmap by lazy {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL_AND_STROKE; strokeWidth = 10f; strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply { moveTo(42f, 28f); lineTo(42f, 100f); lineTo(100f, 64f); close() }
        Canvas(b).drawPath(path, p)
        b
    }

    val pause: Bitmap by lazy {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val c = Canvas(b)
        c.drawRoundRect(34f, 26f, 56f, 102f, 6f, 6f, p)
        c.drawRoundRect(72f, 26f, 94f, 102f, 6f, 6f, p)
        b
    }
}
