package com.bsinaly.bsclean

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object C {
    val GREEN = 0xFF1B5E20.toInt()
    val GREEN2 = 0xFF2E7D32.toInt()
    val GOLD = 0xFFD4AF37.toInt()
    val BG = 0xFFF2F5EF.toInt()
    val TEXT = 0xFF1F2A1F.toInt()
    val GREY = 0xFF66756A.toInt()
    val TRACK = 0xFFE1E9DE.toInt()
    val RED = 0xFFC62828.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.size(bytes: Long): String = Formatter.formatFileSize(this, bytes)

fun Context.rounded(color: Int, radiusDp: Int = 14, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
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
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.1f)
    }

fun Context.btn(text: String, bg: Int = C.GREEN, fg: Int = Color.WHITE, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        background = rounded(bg, 12)
        stateListAnimator = null
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Color.WHITE, 16)
    elevation = dp(2).toFloat()
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) }
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun weight1(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

/** Jauge circulaire d'occupation. */
class GaugeView(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var center = ""
    var sub = ""

    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = C.TRACK; strokeCap = Paint.Cap.ROUND
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.GREEN; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.GREY; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(180)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val sw = context.dp(16).toFloat()
        pTrack.strokeWidth = sw
        pFill.strokeWidth = sw
        pFill.color = when {
            fraction > 0.9f -> C.RED
            fraction > 0.75f -> C.GOLD
            else -> C.GREEN2
        }
        rect.set(sw, sw, width - sw, height - sw)
        canvas.drawArc(rect, 135f, 270f, false, pTrack)
        canvas.drawArc(rect, 135f, 270f * fraction, false, pFill)
        pText.textSize = context.dp(30).toFloat()
        pSub.textSize = context.dp(13).toFloat()
        canvas.drawText(center, width / 2f, height / 2f + context.dp(8), pText)
        canvas.drawText(sub, width / 2f, height / 2f + context.dp(30), pSub)
    }
}
