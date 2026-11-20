package ci.bsinaly.callmagic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Mode sans lecture : uniquement des visages, de grands boutons et la voix.
 * Tout est dit à voix haute ; on répond en parlant. Pour ceux qui ne savent pas lire.
 */
class SimpleActivity : BaseActivity() {

    private lateinit var body: LinearLayout
    private var person: String? = null          // identifiant de la personne ouverte (null = la grille)
    private val handler = Handler(Looper.getMainLooper())
    private val observer: () -> Unit = { if (person == null) showGrid() else showPerson(person!!, silent = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this); HubService.start(this)
        Prefs.setInterphone(this, true)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        body = Ui.vertical(this).apply { setPadding(Ui.dp(this@SimpleActivity, 16), Ui.dp(this@SimpleActivity, 16), Ui.dp(this@SimpleActivity, 16), Ui.dp(this@SimpleActivity, 24)) }
        scroll.addView(body)
        setContentView(scroll)
        needPerms(listOf(Manifest.permission.RECORD_AUDIO)) {}
        Interphone.speak("Touchez une photo pour parler à quelqu'un.")
    }

    override fun onResume() { super.onResume(); Hub.observe(observer); observer() }
    override fun onPause() { super.onPause(); Hub.unobserve(observer) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (person != null) { person = null; Hub.visibleConv = null; showGrid() } else moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ grille de visages

    @SuppressLint("ClickableViewAccessibility")
    private fun showGrid() {
        body.removeAllViews()
        body.addView(sosButton())
        val dir = Contacts.directory()
        if (dir.isEmpty()) {
            body.addView(Ui.center(Ui.text(this, "👥 ⏳", 60f)), Ui.lp(this, top = 40))
            return
        }
        var row: LinearLayout? = null
        for ((i, c) in dir.withIndex()) {
            if (i % 2 == 0) { row = Ui.horizontal(this); body.addView(row, Ui.lp(this, top = 18)) }
            val cell = Ui.vertical(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val frame = FrameLayout(this)
            frame.addView(Ui.avatar(this, c.name, c.photo, 130))
            val st = Contacts.bestState(c)
            val dot = View(this).apply {
                background = Ui.rounded(if (st <= Access.AVAILABLE) 0xFF2EA043.toInt() else 0xFF666666.toInt(), Ui.dp(this@SimpleActivity, 14).toFloat(), 3, Ui.BG)
            }
            frame.addView(dot, FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28), Gravity.BOTTOM or Gravity.END))
            val unread = Hub.unreadBy[c.id] ?: 0
            if (unread > 0) frame.addView(Ui.text(this, " $unread ", 16f, Ui.TEXT, true).apply {
                background = Ui.rounded(Ui.RED, Ui.dp(this@SimpleActivity, 14).toFloat())
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            cell.addView(frame, LinearLayout.LayoutParams(Ui.dp(this, 136), Ui.dp(this, 136)))
            cell.addView(Ui.center(Ui.text(this, c.name, 18f, Ui.TEXT, true)), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            cell.setOnClickListener { Interphone.speak(c.name); showPerson(c.id.ifEmpty { c.key }) }
            row!!.addView(cell, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        if (dir.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        body.addView(exitButton(), Ui.lp(this, top = 40))
    }

    // ------------------------------------------------------------------ une personne

    @SuppressLint("ClickableViewAccessibility")
    private fun showPerson(id: String, silent: Boolean = false) {
        val c = Contacts.byId(id) ?: Contacts.byKey(id) ?: People.contactFor(id) ?: run { person = null; showGrid(); return }
        person = id
        Hub.visibleConv = c.id
        Hub.markRead(c.id)
        body.removeAllViews()
        val back = Ui.button(this, "⬅", Ui.GREY) { person = null; Hub.visibleConv = null; showGrid() }.apply { textSize = 30f }
        body.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 70)))
        val head = Ui.vertical(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        head.addView(Ui.avatar(this, c.name, c.photo, 170))
        head.addView(Ui.center(Ui.text(this, c.name, 26f, Ui.TEXT, true)))
        head.setOnClickListener { Interphone.speak(c.name) }
        body.addView(head, Ui.lp(this, top = 8))

        // Grand bouton : maintenir pour parler
        val talk = Button(this).apply {
            text = "🎙"; textSize = 64f; isAllCaps = false
            background = Ui.rounded(Ui.GREEN, Ui.dp(this@SimpleActivity, 30).toFloat(), 4, Ui.GOLD)
            stateListAnimator = null
        }
        talk.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (VoiceRecorder.start(this)) talk.background = Ui.rounded(Ui.RED, Ui.dp(this, 30).toFloat(), 4, Ui.GOLD)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    talk.background = Ui.rounded(Ui.GREEN, Ui.dp(this, 30).toFloat(), 4, Ui.GOLD)
                    VoiceRecorder.finish(this)?.let { r ->
                        val send = { Hub.sendVoice(r.file, r.durationMs, c.id); Interphone.speak("Envoyé.") }
                        if (Hub.reachableNow(c.id) || c.pubKey() != null) send()
                        else People.connect(this, c, null, onFail = { Interphone.speak("${c.name} n'est pas joignable.") }) { send() }
                    }
                    true
                }
                else -> true
            }
        }
        body.addView(talk, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 190)).apply { topMargin = Ui.dp(this@SimpleActivity, 18) })
        body.addView(Ui.center(Ui.text(this, "✋ 🎙 🗣", 26f)), Ui.lp(this, top = 4))

        val call = Button(this).apply {
            text = "📞"; textSize = 54f
            background = Ui.rounded(Ui.BLUE, Ui.dp(this@SimpleActivity, 30).toFloat())
            stateListAnimator = null
            setOnClickListener { Interphone.speak("J'appelle ${c.name}."); People.call(this@SimpleActivity, c, false) }
        }
        body.addView(call, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 130)).apply { topMargin = Ui.dp(this@SimpleActivity, 14) })

        // Derniers messages : une grande ligne ▶ par message, lu à voix haute
        val msgs = Hub.history.filter { it.conv == c.id && it.kind != Msg.K_SYS }.takeLast(6).reversed()
        for (m in msgs) {
            val row = Ui.horizontal(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(if (m.mine) Ui.BUBBLE_ME else Ui.BUBBLE_THEM, Ui.dp(this@SimpleActivity, 20).toFloat())
                setPadding(Ui.dp(this@SimpleActivity, 16), Ui.dp(this@SimpleActivity, 12), Ui.dp(this@SimpleActivity, 16), Ui.dp(this@SimpleActivity, 12))
            }
            row.addView(Ui.text(this, "▶", 40f, Ui.GOLD, true))
            row.addView(Ui.text(this, (if (m.mine) "  ➡  " else "  ⬅  ") + m.hour + (if (m.status.startsWith("✅")) "  ✅" else ""), 20f, Ui.TEXT))
            row.setOnClickListener {
                when {
                    m.kind == Msg.K_VOICE && m.fileRef != null -> Interphone.playNow(m.fileRef)
                    m.kind == Msg.K_FILE -> Interphone.speak("Un fichier.")
                    else -> Interphone.speak(if (m.mine) "Vous avez dit : ${m.text}" else "${c.name} dit : ${m.text}")
                }
            }
            body.addView(row, Ui.lp(this, top = 12))
        }
        if (!silent) handler.postDelayed({
            val unreadVoice = msgs.firstOrNull { !it.mine }
            if (unreadVoice == null) Interphone.speak("Maintenez le bouton vert et parlez.")
        }, 900)
    }

    // ------------------------------------------------------------------ SOS et sortie

    @SuppressLint("ClickableViewAccessibility")
    private fun sosButton(): View {
        val b = Button(this).apply {
            text = if (Sos.myActive != 0L) "🆘 ✋" else "🆘"; textSize = 44f
            background = Ui.rounded(if (Sos.myActive != 0L) 0xFF8B0000.toInt() else Ui.RED, Ui.dp(this@SimpleActivity, 24).toFloat())
            stateListAnimator = null
        }
        var armed: Runnable? = null
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    Interphone.speak("Gardez le doigt pour appeler à l'aide.")
                    armed = Runnable {
                        if (Sos.myActive != 0L) { Sos.cancel(this); Interphone.speak("Alerte arrêtée."); showGrid() }
                        else Sos.send(this, "🆘 Autre urgence", "") {
                            Interphone.speak("Alerte envoyée. Pour l'arrêter, gardez encore le doigt sur le bouton rouge."); showGrid()
                        }
                    }.also { handler.postDelayed(it, 3000) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { armed?.let { handler.removeCallbacks(it) }; true }
                else -> true
            }
        }
        b.layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 100))
        return b
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun exitButton(): View {
        val t = TextView(this).apply {
            text = "⚙"; textSize = 22f; setTextColor(Ui.MUTED); gravity = Gravity.CENTER
        }
        var armed: Runnable? = null
        t.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    armed = Runnable {
                        Prefs.setSimpleMode(this, false)
                        startActivity(Intent(this, MainActivity::class.java)); finish()
                    }.also { handler.postDelayed(it, 3000) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { armed?.let { handler.removeCallbacks(it) }; true }
                else -> true
            }
        }
        t.layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 60))
        return t
    }
}
