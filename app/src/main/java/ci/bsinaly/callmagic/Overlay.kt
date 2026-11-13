package ci.bsinaly.callmagic

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Bulle flottante disponible par-dessus toutes les applications. */
class Overlay(private val svc: Service) {
    private val ctx: Context = ContextThemeWrapper(svc, android.R.style.Theme_Material)
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var ball: TextView
    private lateinit var badge: TextView
    private lateinit var snippet: TextView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var lines: LinearLayout
    private lateinit var input: EditText
    private lateinit var talk: Button
    private lateinit var inter: Button
    private lateinit var callBtn: Button
    private var shown = false

    private fun dp(v: Int) = Ui.dp(svc, v)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (shown) { refresh(); return }
        val r = Ui.vertical(ctx)

        // Bulle + aperçu du dernier message
        val top = Ui.horizontal(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val frame = FrameLayout(ctx)
        ball = TextView(ctx).apply {
            text = "📞"; textSize = 26f; gravity = Gravity.CENTER
            background = Ui.rounded(Ui.GREEN, dp(30).toFloat(), dp(3), Ui.GOLD)
            elevation = dp(6).toFloat()
        }
        frame.addView(ball, FrameLayout.LayoutParams(dp(60), dp(60)))
        badge = TextView(ctx).apply {
            textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            background = Ui.rounded(Ui.RED, dp(10).toFloat())
            setPadding(dp(5), 0, dp(5), 0)
            elevation = dp(8).toFloat()
        }
        frame.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(20), Gravity.TOP or Gravity.END))
        top.addView(frame, LinearLayout.LayoutParams(dp(62), dp(62)))
        snippet = Ui.text(ctx, "", 13f, Ui.TEXT).apply {
            background = Ui.rounded(Ui.CARD, dp(12).toFloat(), dp(1), Ui.GOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6)); maxWidth = dp(230); maxLines = 3
        }
        top.addView(snippet, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = dp(6) })
        r.addView(top)

        // Panneau
        panel = Ui.card(ctx).apply { background = Ui.rounded(Ui.CARD, dp(14).toFloat(), dp(1), Ui.GOLD); elevation = dp(8).toFloat() }
        panel.addView(Ui.text(ctx, "BS CALLMagic", 16f, Ui.GOLD, true))
        status = Ui.text(ctx, "", 12f, Ui.MUTED)
        panel.addView(status)
        lines = Ui.vertical(ctx)
        panel.addView(lines, Ui.lp(ctx, top = 6))

        callBtn = Ui.button(ctx, "", Ui.GREEN) { openCall() }
        panel.addView(callBtn, Ui.lp(ctx, top = 6))

        val inRow = Ui.horizontal(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(ctx).apply {
            hint = "Message à tous…"; textSize = 14f
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        inRow.addView(input, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        inRow.addView(Ui.iconButton(ctx, "➤", Ui.GREEN) {
            Hub.sendText(input.text.toString()); input.setText("")
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(inRow, Ui.lp(ctx, top = 4))

        talk = Ui.button(ctx, TALK_IDLE, Ui.BLUE) {}
        talk.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { if (VoiceRecorder.start(svc)) { talk.text = "🔴 Parlez… relâchez pour envoyer"; talk.background = Ui.rounded(Ui.RED, dp(12).toFloat()) }; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    VoiceRecorder.finish(svc)?.let { r ->
                        if (Hub.readyPeers().isEmpty()) Ui.toast(svc, "Aucun correspondant connecté.")
                        else Hub.sendVoice(r.file, r.durationMs)
                    }
                    talk.text = TALK_IDLE; talk.background = Ui.rounded(Ui.BLUE, dp(12).toFloat()); true
                }
                else -> true
            }
        }
        panel.addView(talk, Ui.lp(ctx, top = 6))

        val row = Ui.horizontal(ctx)
        val w = { LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { setMargins(dp(2), 0, dp(2), 0) } }
        row.addView(Ui.button(ctx, "💬 Ouvrir", Ui.GREY) { openChat() }.apply { textSize = 12f }, w())
        inter = Ui.button(ctx, "", Ui.GREY) {
            Prefs.setInterphone(svc, !Prefs.interphone(svc)); if (!Prefs.interphone(svc)) Interphone.stopAll(); refresh()
        }.apply { textSize = 12f }
        row.addView(inter, w())
        row.addView(Ui.button(ctx, "✕", Ui.GREY) { setPanel(false) }.apply { textSize = 12f }, w())
        panel.addView(row, Ui.lp(ctx, top = 6))
        Ui.show(panel, false)
        r.addView(panel, LinearLayout.LayoutParams(dp(300), Ui.WRAP).apply { topMargin = dp(6) })

        // Déplacement / ouverture
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        ball.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { params.x = px + dx.toInt(); params.y = py + dy.toInt(); update() }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (Hub.call == Hub.Call.RINGING_IN) openCall() else setPanel(panel.visibility != View.VISIBLE)
                    }
                    true
                }
                else -> false
            }
        }

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(6); y = dp(180) }

        try { wm.addView(r, params); root = r; shown = true } catch (e: Exception) { return }
        refresh()
    }

    fun hide() {
        if (!shown) return
        VoiceRecorder.cancel()
        try { wm.removeView(root) } catch (_: Exception) {}
        root = null; shown = false
    }

    private fun update() { root?.let { try { wm.updateViewLayout(it, params) } catch (_: Exception) {} } }

    private fun setPanel(open: Boolean) {
        Ui.show(panel, open)
        Ui.show(snippet, false)
        if (open) Hub.unreadBy.clear()
        params.flags = if (open) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!open) {
            (svc.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        }
        update()
        refresh()
    }

    private fun openChat() {
        setPanel(false)
        svc.startActivity(Intent(svc, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openCall() {
        setPanel(false)
        svc.startActivity(Intent(svc, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun onMessage(m: Msg) {
        if (!shown) return
        refresh()
        if (panel.visibility == View.VISIBLE) return
        snippet.text = (if (m.to.isNotEmpty()) "🔒 " else "") + "${m.origin} : ${m.summary()}"
        Ui.show(snippet, true)
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ if (shown) Ui.show(snippet, false) }, 6000)
    }

    fun refresh() {
        if (!shown) return
        val ps = Hub.readyPeers()
        status.text = (if (ps.isEmpty()) "Aucun correspondant connecté" else "🟢 " + ps.joinToString(", ") { "${it.name} ${Proto.icon(it.transport)}" }) +
            "\n" + Relay.statusText()
        badge.text = Hub.unread.toString()
        Ui.show(badge, Hub.unread > 0)
        ball.text = when (Hub.call) {
            Hub.Call.IDLE -> "📞"
            Hub.Call.RINGING_IN -> "📲"
            else -> "🟢"
        }
        Ui.show(callBtn, Hub.call != Hub.Call.IDLE)
        callBtn.text = when (Hub.call) {
            Hub.Call.RINGING_IN -> "📲 Appel de ${Hub.callPeer?.name} — répondre"
            Hub.Call.RINGING_OUT -> "📞 Appel en cours… — afficher"
            Hub.Call.ACTIVE -> "🟢 En appel avec ${Hub.callPeer?.name} — afficher"
            else -> ""
        }
        if (Hub.call == Hub.Call.RINGING_IN && panel.visibility != View.VISIBLE) {
            snippet.text = "📲 ${Hub.callPeer?.name} vous appelle — touchez la bulle"
            Ui.show(snippet, true)
        }
        inter.text = if (Prefs.interphone(svc)) "🔊 Interphone" else "🔇 Interphone"
        lines.removeAllViews()
        val last = Hub.history.filter { it.kind != Msg.K_SYS }.takeLast(4)
        if (last.isEmpty()) lines.addView(Ui.text(ctx, "Aucun message pour l'instant.", 12f, Ui.MUTED))
        for (m in last) {
            val who = (if (m.to.isNotEmpty()) "🔒 " else "") + (if (m.mine) "Moi" else m.origin)
            val t = Ui.text(ctx, "$who : ${m.summary()}", 13f, Ui.TEXT).apply { maxLines = 2 }
            if (m.kind == Msg.K_VOICE && m.fileRef != null) t.setOnClickListener { Interphone.playNow(m.fileRef) }
            lines.addView(t)
        }
    }

    companion object {
        private const val TALK_IDLE = "🎙 Maintenir pour parler à tous"
    }
}
