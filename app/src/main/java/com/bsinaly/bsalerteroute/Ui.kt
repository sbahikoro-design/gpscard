package com.bsinaly.bsalerteroute

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Palette « Asphalte » : route de nuit, jaune signalisation, rouge danger. */
object C {
    val BG = 0xFF15171A.toInt()
    val CARD = 0xFF1F2227.toInt()
    val CARD2 = 0xFF2B2F35.toInt()
    val LINE = 0xFF3A3F47.toInt()
    val JAUNE = 0xFFFFD000.toInt()
    val ROUGE = 0xFFE10600.toInt()
    val TEXT = 0xFFF2F3F5.toInt()
    val GREY = 0xFF9AA1AB.toInt()
    val OK = 0xFF4CD08A.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.box(color: Int, radiusDp: Int = 16, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

fun Context.tv(text: String, sp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text; textSize = sp; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-condensed", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.12f)
    }

fun Context.button(text: String, bg: Int, fg: Int, sp: Float = 16f, onClick: () -> Unit): TextView =
    tv(text, sp, fg, true).apply {
        gravity = Gravity.CENTER
        background = box(bg, 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(48)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top }

/**
 * Radar de route : le véhicule est en bas au centre, la route défile vers le haut
 * (le haut = sens de marche). Les obstacles à moins de 600 m apparaissent en points colorés.
 */
class RadarView(ctx: Context) : View(ctx) {
    var loc: android.location.Location? = null
    var obstacles: List<Obstacle> = emptyList()
    var nearestId: Long = -1
    var range = 600f
    private var pulse = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.GREY; textSize = ctx.dpf(11f); typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val dash = DashPathEffect(floatArrayOf(ctx.dpf(10f), ctx.dpf(10f)), 0f)

    fun tick() { pulse = (pulse + 0.08f) % 1f; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h - context.dpf(34f)
        val R = min(w / 2f - context.dpf(8f), cy - context.dpf(10f))
        val scale = R / range

        // fond : chaussée en perspective
        p.style = Paint.Style.FILL; p.shader = null
        p.color = C.CARD; c.drawRoundRect(0f, 0f, w, h, context.dpf(18f), context.dpf(18f), p)
        val road = Path().apply {
            moveTo(cx - w * 0.07f, 0f); lineTo(cx + w * 0.07f, 0f)
            lineTo(cx + w * 0.30f, h); lineTo(cx - w * 0.30f, h); close()
        }
        p.color = C.CARD2; c.drawPath(road, p)
        // ligne médiane en pointillés qui défile
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(3f); p.color = 0x66FFFFFF
        p.pathEffect = DashPathEffect(floatArrayOf(context.dpf(14f), context.dpf(14f)), -pulse * context.dpf(28f))
        c.drawLine(cx, 0f, cx, h, p)
        p.pathEffect = null

        // anneaux de distance
        p.strokeWidth = context.dpf(1f); p.color = C.LINE; p.pathEffect = dash
        for (r in listOf(100f, 300f, 600f)) {
            c.drawCircle(cx, cy, r * scale, p)
            c.drawText("${r.toInt()} m", cx + r * scale * 0.71f + context.dpf(4f), cy - r * scale * 0.71f, txt)
        }
        p.pathEffect = null

        val me = loc
        val heading = if (me != null && me.hasBearing() && me.speed > 2.5f) me.bearing else 0f
        if (me != null) {
            for (o in obstacles) {
                val d = me.distanceTo(o.loc()); if (d > range * 1.05f) continue
                val b = Math.toRadians((me.bearingTo(o.loc()) - heading).toDouble())
                val x = cx + (sin(b) * d * scale).toFloat()
                val y = cy - (cos(b) * d * scale).toFloat()
                val t = Types.of(o.type)
                val isN = o.id == nearestId
                if (isN) {
                    p.style = Paint.Style.STROKE; p.color = t.color; p.strokeWidth = context.dpf(2f)
                    p.alpha = ((1f - pulse) * 255).toInt()
                    c.drawCircle(x, y, context.dpf(10f + 22f * pulse), p); p.alpha = 255
                }
                p.style = Paint.Style.FILL; p.color = if (o.muted) C.GREY else t.color
                c.drawCircle(x, y, context.dpf(if (isN) 9f else 7f), p)
                p.color = C.BG; c.drawCircle(x, y, context.dpf(2.5f), p)
            }
        }

        // véhicule (flèche)
        val s = context.dpf(14f)
        val car = Path().apply { moveTo(cx, cy - s); lineTo(cx + s * 0.75f, cy + s * 0.8f); lineTo(cx, cy + s * 0.35f); lineTo(cx - s * 0.75f, cy + s * 0.8f); close() }
        p.style = Paint.Style.FILL; p.color = if (me == null) C.GREY else C.JAUNE
        c.drawPath(car, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(2f); p.color = Color.BLACK; c.drawPath(car, p)
    }
}
