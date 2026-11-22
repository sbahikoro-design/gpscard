package com.bsinaly.bsmagickeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Pavé tactile :
 *  - 1 doigt : déplacer le pointeur ; toucher = clic gauche
 *  - 2 doigts : toucher = clic droit ; glisser = défiler
 *  - appui long puis glisser = maintenir le clic (sélection, déplacement de fenêtres)
 */
class TouchpadView(ctx: Context) : View(ctx) {

    var sensitivity = 1.6f

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var lastX = 0f; private var lastY = 0f
    private var startX = 0f; private var startY = 0f
    private var downTime = 0L
    private var moved = false
    private var maxPointers = 1
    private var accX = 0f; private var accY = 0f; private var scrollAcc = 0f
    private var dragging = false

    private val longPress = Runnable {
        if (!moved && maxPointers == 1) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Link.press(1, true)
            invalidate()
        }
    }

    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pHint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    init { isHapticFeedbackEnabled = true }

    private fun dp(v: Int) = context.dp(v).toFloat()

    override fun onDraw(canvas: Canvas) {
        rect.set(dp(2), dp(2), width - dp(2), height - dp(2))
        pBg.color = if (dragging) 0xFF2E4D2F.toInt() else 0xFF203B22.toInt()
        canvas.drawRoundRect(rect, dp(18), dp(18), pBg)
        pBorder.strokeWidth = dp(2)
        pBorder.color = if (dragging) C.GOLD else 0x55D4AF37
        canvas.drawRoundRect(rect, dp(18), dp(18), pBorder)
        pTitle.color = 0x99D4AF37.toInt(); pTitle.textSize = dp(18)
        pHint.color = 0x88FFFFFF.toInt(); pHint.textSize = dp(12)
        val cx = width / 2f; val cy = height / 2f
        canvas.drawText(if (dragging) "CLIC MAINTENU" else "PAVÉ TACTILE", cx, cy - dp(22), pTitle)
        canvas.drawText("1 doigt : déplacer · toucher : clic", cx, cy + dp(4), pHint)
        canvas.drawText("2 doigts : clic droit · glisser : défiler", cx, cy + dp(22), pHint)
        canvas.drawText("appui long puis glisser : sélectionner", cx, cy + dp(40), pHint)
    }

    private fun avgX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun avgY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; startX = e.x; startY = e.y
                downTime = e.eventTime; moved = false; maxPointers = 1
                dragging = false; accX = 0f; accY = 0f; scrollAcc = 0f
                postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                removeCallbacks(longPress)
                lastX = avgX(e); lastY = avgY(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Recaler sur le(s) doigt(s) restant(s) pour éviter un saut du pointeur
                var sx = 0f; var sy = 0f; var n = 0
                for (i in 0 until e.pointerCount) if (i != e.actionIndex) { sx += e.getX(i); sy += e.getY(i); n++ }
                if (n > 0) { lastX = sx / n; lastY = sy / n }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val y = avgY(e)
                    scrollAcc += y - lastY
                    lastY = y; lastX = avgX(e)
                    val step = dp(16)
                    while (scrollAcc >= step) { Link.t()?.scroll(1); scrollAcc -= step; moved = true }
                    while (scrollAcc <= -step) { Link.t()?.scroll(-1); scrollAcc += step; moved = true }
                } else {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    if (!moved && hypot(e.x - startX, e.y - startY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    if (moved || dragging) {
                        // Accélération : mouvement rapide = plus grande distance
                        val speed = hypot(dx, dy) / dp(10)
                        val accel = 1f + min(speed, 2.5f) * 0.6f
                        accX += dx * sensitivity * accel
                        accY += dy * sensitivity * accel
                        val ix = accX.toInt(); val iy = accY.toInt()
                        accX -= ix; accY -= iy
                        if (ix != 0 || iy != 0) Link.t()?.move(ix, iy)
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    Link.press(1, false); dragging = false
                } else if (!moved && e.eventTime - downTime < 300) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    Link.click(if (maxPointers >= 2) 2 else 1)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) { Link.press(1, false); dragging = false }
                invalidate()
            }
        }
        return true
    }
}

/** Bande de défilement (molette) : glisser vers le bas = descendre. */
class ScrollStrip(ctx: Context) : View(ctx) {
    private var lastY = 0f
    private var acc = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val d = context.dp(1).toFloat()
        rect.set(4 * d, 2 * d, width - 4 * d, height - 2 * d)
        p.color = 0xFF2E7D32.toInt()
        canvas.drawRoundRect(rect, 14 * d, 14 * d, p)
        p.color = 0x66FFFFFF
        var y = rect.top + 20 * d
        while (y < rect.bottom - 10 * d) { canvas.drawRect(rect.left + 10 * d, y, rect.right - 10 * d, y + 2 * d, p); y += 14 * d }
        p.color = C.GOLD; p.textSize = 13 * d; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        canvas.drawText("▲", width / 2f, rect.top + 14 * d, p)
        canvas.drawText("▼", width / 2f, rect.bottom - 4 * d, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val step = context.dp(12).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastY = e.y; acc = 0f }
            MotionEvent.ACTION_MOVE -> {
                acc += e.y - lastY; lastY = e.y
                while (acc >= step) { Link.t()?.scroll(-1); acc -= step }
                while (acc <= -step) { Link.t()?.scroll(1); acc += step }
            }
        }
        return true
    }
}

/**
 * Champ qui capte la frappe du clavier du téléphone et la retransmet au PC,
 * y compris l'effacement (⌫) quand le champ est vide et la touche Entrée.
 */
class KeyCapture(ctx: Context) : EditText(ctx) {

    var onType: (Int, String) -> Unit = { _, _ -> }
    var onEnter: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    private var suppress = false

    init {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        setOnEditorActionListener { _, _, _ -> onEnter(); true }

        addTextChangedListener(object : TextWatcher {
            private var old = ""
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                old = s.toString()
            }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (suppress) return
                val removed = old.substring(start, min(start + before, old.length))
                val added = s.subSequence(start, start + count).toString()
                var p = 0
                while (p < removed.length && p < added.length && removed[p] == added[p]) p++
                onType(removed.length - p, added.substring(p))
            }
            override fun afterTextChanged(s: Editable) {
                if (!suppress && s.length > 200) { suppress = true; s.clear(); suppress = false }
            }
        })
    }

    /** Vide le champ sans rien envoyer au PC. */
    fun clearSilently() { suppress = true; text?.clear(); suppress = false }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (this@KeyCapture.text.isNullOrEmpty() && beforeLength > 0) {
                    repeat(abs(beforeLength).coerceAtMost(20)) { onBackspace() }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.keyCode == KeyEvent.KEYCODE_DEL && this@KeyCapture.text.isNullOrEmpty()) {
                        onBackspace(); return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_ENTER) { onEnter(); return true }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    init {
        setTextColor(Color.WHITE)
        setHintTextColor(0x99FFFFFF.toInt())
    }
}
