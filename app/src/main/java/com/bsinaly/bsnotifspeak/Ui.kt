package com.bsinaly.bsnotifspeak

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Palette « Nuit d'Afrique » : nuit profonde, terre cuite, ambre, motifs bogolan. */
object C {
    val NUIT = 0xFF120E0B.toInt()
    val CARD = 0xFF1E1813.toInt()
    val CARD2 = 0xFF2A211A.toInt()
    val LINE = 0xFF3A2E24.toInt()
    val ORANGE = 0xFFFF7A1A.toInt()
    val TERRE = 0xFFC8461E.toInt()
    val AMBRE = 0xFFFFC145.toInt()
    val TEXT = 0xFFF6EDE3.toInt()
    val GREY = 0xFFA8998A.toInt()
    val OK = 0xFF4CD08A.toInt()
    val BAD = 0xFFFF5A4E.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int = 18, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

fun Context.tv(text: String, sp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = top }

fun weight1(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

/** Bouton pilule : plein (orange) ou contour. */
fun Context.pill(text: String, filled: Boolean = true, onClick: () -> Unit): TextView =
    tv(text, 14.5f, if (filled) C.NUIT else C.ORANGE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = if (filled) GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.AMBRE, C.ORANGE)
        ).apply { cornerRadius = dpf(40f) }
        else rounded(Color.TRANSPARENT, 40, 1, C.ORANGE)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = matchWrap(dp(10))
    }

/** Petite étiquette de choix (sélectionnée ou non). */
fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
    tv(text, 13.5f, if (selected) C.NUIT else C.TEXT, selected).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = if (selected) rounded(C.ORANGE, 30) else rounded(C.CARD2, 30, 1, C.LINE)
        setOnClickListener { onClick() }
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(C.CARD, 22, 1, C.LINE)
    setPadding(dp(18), dp(16), dp(18), dp(18))
    layoutParams = matchWrap().apply { setMargins(dp(14), dp(8), dp(14), dp(8)) }
}

/** Titre de section avec frise de triangles bogolan. */
fun Context.sectionTitle(text: String, icon: String): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(tv(icon, 18f).apply { setPadding(0, 0, dp(10), 0) })
    addView(tv(text.uppercase(), 13f, C.AMBRE, true).apply { letterSpacing = 0.12f }, weight1())
    addView(BogolanStrip(context), LinearLayout.LayoutParams(dp(54), dp(10)))
    layoutParams = matchWrap().apply { bottomMargin = dp(10) }
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
    row().apply {
        setPadding(0, dp(8), 0, dp(8))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 15f, C.TEXT, true))
            addView(tv(sub, 12.5f, C.GREY))
        }
        addView(texts, weight1())
        val sw = Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C.AMBRE, C.GREY)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C.ORANGE, C.LINE)
            )
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        addView(sw)
        setOnClickListener { sw.isChecked = !sw.isChecked }
    }

fun Context.slider(label: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = matchWrap(dp(8))
        val head = row()
        head.addView(tv(label, 14f, C.TEXT), weight1())
        val v = tv(fmt(value), 14f, C.AMBRE, true)
        head.addView(v)
        addView(head)
        addView(SeekBar(context).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(C.ORANGE)
            thumbTintList = ColorStateList.valueOf(C.AMBRE)
            progressBackgroundTintList = ColorStateList.valueOf(C.LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { v.text = fmt(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { onChange((s?.progress ?: 0) + min) }
            })
        }, matchWrap(dp(2)))
    }

/** Petite frise de triangles (motif bogolan). */
class BogolanStrip(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TERRE }
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = h
        var x = 0f
        var i = 0
        while (x + w <= width) {
            p.color = if (i % 2 == 0) C.TERRE else C.AMBRE
            val path = Path().apply { moveTo(x, h); lineTo(x + w / 2, 0f); lineTo(x + w, h); close() }
            c.drawPath(path, p)
            x += w + dpf(2f); i++
        }
    }
    private fun dpf(v: Float) = v * resources.displayMetrics.density
}

/** Bandeau d'en-tête : dégradé terre cuite + motifs bogolan (losanges, zigzags, points). */
class BogolanHeader(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF3B1A0C.toInt(), 0xFF7A2C12.toInt(), 0xFF2A140A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = 0x55FFC145
        // zigzag en haut
        val z = Path()
        val step = 14f * d
        var x = 0f
        z.moveTo(0f, 12f * d)
        while (x < w) { z.lineTo(x + step / 2, 4f * d); z.lineTo(x + step, 12f * d); x += step }
        c.drawPath(z, p)
        // zigzag en bas
        val z2 = Path()
        x = 0f
        z2.moveTo(0f, h - 4f * d)
        while (x < w) { z2.lineTo(x + step / 2, h - 12f * d); z2.lineTo(x + step, h - 4f * d); x += step }
        c.drawPath(z2, p)

        // losanges à droite
        p.style = Paint.Style.FILL
        p.color = 0x33FFC145
        val s = 10f * d
        var row = 0
        var y = 26f * d
        while (y < h - 20f * d) {
            var xx = w - 6f * s + if (row % 2 == 0) 0f else s
            while (xx < w + s) {
                val dia = Path().apply { moveTo(xx, y - s / 2); lineTo(xx + s / 2, y); lineTo(xx, y + s / 2); lineTo(xx - s / 2, y); close() }
                c.drawPath(dia, p)
                xx += 2 * s
            }
            y += s; row++
        }
        // points
        p.color = 0x44F6EDE3
        var px = 20f * d
        while (px < w * 0.55f) { c.drawCircle(px, h - 22f * d, 1.6f * d, p); px += 9f * d }
    }
}

/** Grand bouton marche/arrêt, avec ondes sonores animées quand il est actif. */
class PowerButton(ctx: Context) : View(ctx) {
    var on = true
        set(v) { field = v; if (v) anim.start() else anim.cancel(); invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { isClickable = true }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (on) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.62f

        if (on) {
            // ondes
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            for (i in 0..2) {
                val t = (phase + i / 3f) % 1f
                p.color = C.ORANGE
                p.alpha = ((1f - t) * 170).toInt()
                c.drawCircle(cx, cy, r + t * r * 0.58f, p)
            }
            p.alpha = 255
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.5f, intArrayOf(C.AMBRE, C.ORANGE, C.TERRE), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            p.shader = null
        } else {
            p.style = Paint.Style.FILL
            p.color = C.CARD2
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            p.color = C.LINE
            c.drawCircle(cx, cy, r, p)
        }

        // symbole marche/arrêt
        val fg = if (on) C.NUIT else C.GREY
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 6f * d
        p.color = fg
        val ir = r * 0.38f
        c.drawArc(cx - ir, cy - ir + 4 * d, cx + ir, cy + ir + 4 * d, -60f, 300f, false, p)
        c.drawLine(cx, cy - ir - 2 * d, cx, cy + 2 * d, p)

        // libellé
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.textSize = 13f * d
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.color = fg
        c.drawText(if (on) "EN MARCHE" else "EN PAUSE", cx, cy + r * 0.78f, p)
    }
}

/** Point d'état coloré. */
fun Context.dot(ok: Boolean): View = View(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ok) C.OK else C.BAD) }
    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
}
