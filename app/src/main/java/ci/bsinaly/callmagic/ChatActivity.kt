package ci.bsinaly.callmagic

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Conversation : salon de groupe (cible vide) ou privée avec une personne. */
class ChatActivity : BaseActivity() {

    companion object {
        const val EXTRA_TARGET = "target"
        const val EXTRA_PICK_FILE = "pick_file"
        private const val REQ_FILE = 10
    }

    private var target = ""
    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var btnCall: Button
    private lateinit var btnFile: Button
    private lateinit var callBar: Button
    private lateinit var progressText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var msgs: LinearLayout
    private lateinit var input: EditText
    private lateinit var talk: Button
    private lateinit var infoBox: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var infoButtons: LinearLayout

    private var rendered = 0
    private var shown: List<Msg> = emptyList()
    private val observer: () -> Unit = { render() }

    private val isSalon get() = target.isEmpty()
    private fun contact(): Contact? = if (isSalon) null else People.contactFor(target)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        HubService.start(this)
        target = intent?.getStringExtra(EXTRA_TARGET) ?: ""
        val d = { v: Int -> Ui.dp(this, v) }
        val root = Ui.vertical(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)

        // En-tête
        val header = Ui.horizontal(this).apply {
            setBackgroundColor(Ui.CARD); gravity = Gravity.CENTER_VERTICAL
            setPadding(d(14), d(10), d(10), d(10))
        }
        val titles = Ui.vertical(this)
        titleView = Ui.text(this, "", 18f, Ui.TEXT, true).apply { setSingleLine() }
        subtitle = Ui.text(this, "", 12f, Ui.MUTED).apply { maxLines = 3 }
        titles.addView(titleView); titles.addView(subtitle)
        titles.setOnClickListener { headerInfo() }
        header.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        btnFile = Ui.iconButton(this, "📎", Ui.GREY) { pickFile() }
        btnCall = Ui.iconButton(this, "📞", Ui.GREEN) { callMenu() }
        header.addView(btnFile, LinearLayout.LayoutParams(d(46), d(46)).apply { rightMargin = d(8) })
        header.addView(btnCall, LinearLayout.LayoutParams(d(46), d(46)))
        root.addView(header, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        callBar = Ui.button(this, "", Ui.GREEN) { startActivity(Intent(this, CallActivity::class.java)) }
        root.addView(callBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(d(10), d(8), d(10), 0) })

        progressText = Ui.text(this, "", 13f, Ui.GOLD).apply { setPadding(d(16), d(8), d(16), 0) }
        root.addView(progressText)

        infoBox = Ui.card(this)
        infoText = Ui.text(this, "", 13f, Ui.TEXT)
        infoBox.addView(infoText)
        infoButtons = Ui.horizontal(this)
        infoBox.addView(infoButtons, Ui.lp(this, top = 8))
        root.addView(infoBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(d(10), d(8), d(10), 0) })

        scroll = ScrollView(this).apply { isFillViewport = true }
        msgs = Ui.vertical(this).apply { setPadding(d(10), d(8), d(10), d(8)) }
        scroll.addView(msgs)
        root.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // Saisie + appuyer pour parler
        val inputRow = Ui.horizontal(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8), d(6), d(8), d(4))
        }
        input = EditText(this).apply {
            setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); textSize = 16f
            background = Ui.rounded(Ui.CARD, d(22).toFloat())
            setPadding(d(16), d(10), d(16), d(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        talk = Ui.iconButton(this, "🎙", Ui.BLUE) {}
        talk.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!VoiceRecorder.canRecord(this)) needPerms(listOf(Manifest.permission.RECORD_AUDIO)) {}
                    else if (VoiceRecorder.start(this)) {
                        talk.background = Ui.rounded(Ui.RED, d(24).toFloat())
                        progressText.text = "🔴 Enregistrement… relâchez pour envoyer"
                        Ui.show(progressText, true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    VoiceRecorder.finish(this)?.let { r -> withTarget { Hub.sendVoice(r.file, r.durationMs, target) } }
                    talk.background = Ui.rounded(Ui.BLUE, d(24).toFloat())
                    onProgress(Hub.progressText)
                    true
                }
                else -> true
            }
        }
        inputRow.addView(talk, LinearLayout.LayoutParams(d(46), d(46)).apply { leftMargin = d(6) })
        inputRow.addView(Ui.iconButton(this, "➤", Ui.GREEN) { sendText() },
            LinearLayout.LayoutParams(d(46), d(46)).apply { leftMargin = d(6) })
        root.addView(inputRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.addView(Ui.center(Ui.text(this, "Maintenez 🎙 pour envoyer un message vocal", 11f, Ui.MUTED)),
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = d(6) })

        VoiceRecorder.onMax = {
            VoiceRecorder.finish(this)?.let { r -> withTarget { Hub.sendVoice(r.file, r.durationMs, target) } }
            talk.background = Ui.rounded(Ui.BLUE, d(24).toFloat())
        }
        if (intent?.getBooleanExtra(EXTRA_PICK_FILE, false) == true) root.post { pickFile() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = intent?.getStringExtra(EXTRA_TARGET) ?: ""
        msgs.removeAllViews(); rendered = 0
        Hub.visibleConv = target
        Hub.markRead(target)
        render()
        if (intent?.getBooleanExtra(EXTRA_PICK_FILE, false) == true) pickFile()
    }

    override fun onResume() {
        super.onResume()
        Hub.visibleConv = target
        Hub.markRead(target)
        HubService.instance?.cancelMessageNotification(target)
        HubService.instance?.refreshOverlay()
        Hub.observe(observer)
        msgs.removeAllViews()
        rendered = 0
        render()
    }

    override fun onPause() {
        super.onPause()
        if (Hub.visibleConv == target) Hub.visibleConv = null
        Hub.unobserve(observer)
        VoiceRecorder.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        VoiceRecorder.onMax = null
    }

    // ------------------------------------------------------------------ affichage

    private fun render() {
        val ps = Hub.readyPeers()
        if (isSalon) {
            titleView.text = "💬 Salon" + if (ps.isNotEmpty()) " · ${ps.distinctBy { it.deviceId }.size + 1} membres" else ""
            subtitle.text = if (ps.isEmpty()) "🔴 Personne n'est connecté"
            else ps.distinctBy { it.deviceId }.joinToString(", ") { "${it.name} ${Proto.icon(it.transport)}" }
            input.hint = "Message à tout le salon…"
            Ui.show(infoBox, ps.isEmpty())
            if (ps.isEmpty()) {
                infoText.text = "Aucun correspondant connecté. Choisissez une personne dans l'annuaire, ou connectez-vous :"
                infoButtons.removeAllViews()
                infoButtons.addView(Ui.button(this, "👥 Annuaire", Ui.GREEN) { finishToHome() }, weight(4))
                infoButtons.addView(Ui.button(this, "🌐 Internet", Ui.BLUE) { startActivity(Intent(this, InternetActivity::class.java)) }, weight(4))
            }
        } else {
            val c = contact()
            val name = c?.name ?: Contacts.nameFor(target)
            titleView.text = "🔒 $name"
            val acc = c?.let { Contacts.access(it) } ?: emptyList()
            subtitle.text = acc.firstOrNull()?.label ?: "⚫ Hors de portée — les messages passeront par le salon si possible"
            input.hint = "Message privé à $name…"
            val connected = Hub.peerFor(target) != null
            Ui.show(infoBox, !connected && c != null)
            if (!connected && c != null) {
                infoText.text = if (acc.isEmpty()) "$name n'est joignable par aucun accès pour l'instant."
                else "Pas encore connecté. Accès possibles :\n" + acc.joinToString("\n") { it.label }
                infoButtons.removeAllViews()
                if (acc.isNotEmpty()) infoButtons.addView(Ui.button(this, "🔌 Se connecter", Ui.GREEN) {
                    People.chooseVia(this, c) { via -> People.connect(this, c, via) { render() } }
                }, weight(0))
            }
        }
        btnCall.alpha = if (Hub.call == Hub.Call.IDLE) 1f else 0.5f

        Ui.show(callBar, Hub.call != Hub.Call.IDLE)
        callBar.text = when (Hub.call) {
            Hub.Call.RINGING_IN -> "📲 Appel entrant de ${Hub.callPeer?.name} — toucher pour répondre"
            Hub.Call.RINGING_OUT -> "📞 Appel de ${Hub.callPeer?.name}… — afficher"
            Hub.Call.ACTIVE -> "🟢 En appel avec ${Hub.callPeer?.name} (${Hub.duration()}) — revenir à l'appel"
            Hub.Call.IDLE -> ""
        }

        val list = Hub.history.filter { it.conv == target }
        if (list.size < rendered || (rendered > 0 && shown.getOrNull(rendered - 1) !== list.getOrNull(rendered - 1))) {
            msgs.removeAllViews(); rendered = 0
        }
        if (list.size > rendered) {
            for (i in rendered until list.size) msgs.addView(bubble(list[i]))
            rendered = list.size
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        shown = list
        if (Hub.visibleConv == target && (Hub.unreadBy[target] ?: 0) > 0) Hub.unreadBy.remove(target)
        if (!VoiceRecorder.recording) onProgress(Hub.progressText)
    }

    private fun weight(m: Int) = LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply {
        setMargins(Ui.dp(this@ChatActivity, m), 0, Ui.dp(this@ChatActivity, m), 0)
    }

    private fun finishToHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun onProgress(text: String?) {
        progressText.text = text ?: ""
        Ui.show(progressText, text != null)
    }

    private fun bubble(m: Msg): View {
        val d = { v: Int -> Ui.dp(this, v) }
        if (m.kind == Msg.K_SYS) {
            return Ui.center(Ui.text(this, "${m.text}  ·  ${m.hour}", 12f, Ui.MUTED)).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(0, d(8), 0, d(4)) }
            }
        }
        val box = Ui.vertical(this).apply {
            background = Ui.rounded(if (m.mine) Ui.BUBBLE_ME else Ui.BUBBLE_THEM, d(16).toFloat())
            setPadding(d(12), d(8), d(12), d(6))
        }
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        if (!m.mine && isSalon) box.addView(Ui.text(this, m.origin, 12f, Ui.GOLD, true))
        val body = when (m.kind) {
            Msg.K_VOICE -> "🎤 Message vocal · ${m.durationMs / 1000} s\n▶ Toucher pour écouter"
            Msg.K_FILE -> "📄 ${m.fileName}\n${FileStore.size(m.size)} · " + (if (m.mine) "envoyé" else "reçu — toucher pour ouvrir")
            else -> m.text
        }
        box.addView(Ui.text(this, body, 15f, Ui.TEXT).apply { maxWidth = maxW })
        val via = if (m.via.isNotEmpty()) "  ·  ${Proto.icon(m.via)} ${m.via}" else ""
        box.addView(Ui.text(this, m.hour + via, 10f, Ui.MUTED).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val ref = m.fileRef
        if (ref != null) {
            if (m.kind == Msg.K_VOICE) box.setOnClickListener { Interphone.playNow(ref) }
            else if (m.kind == Msg.K_FILE) box.setOnClickListener { FileStore.open(this, ref, m.fileName ?: "fichier") }
        }
        return LinearLayout(this).apply {
            gravity = if (m.mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = d(6) }
            addView(box, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        }
    }

    // ------------------------------------------------------------------ actions

    /**
     * Exécute un envoi : en privé, se connecte d'abord à la personne si besoin (meilleur accès) ;
     * si elle est injoignable directement, le message passe par le salon s'il y a du monde.
     */
    private fun withTarget(action: () -> Unit) {
        if (isSalon) {
            if (Hub.readyPeers().isEmpty()) { Ui.toast(this, "Aucun correspondant connecté."); return }
            action(); return
        }
        if (Hub.peerFor(target) != null) { action(); return }
        val c = contact()
        if (c == null) { if (Hub.readyPeers().isNotEmpty()) action() else Ui.toast(this, "Personne injoignable."); return }
        People.connect(this, c, null, onFail = {
            if (Hub.readyPeers().isNotEmpty()) {
                Ui.toast(this, "${c.name} injoignable directement : envoi par le salon (relais).")
                action()
            } else People.failMessage(this, c, null)
        }) { action() }
    }

    private fun sendText() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        withTarget { Hub.sendText(t, target) }
    }

    private fun headerInfo() {
        if (!isSalon) { contact()?.let { People.showActions(this, it) { render() } }; return }
        val ps = Hub.readyPeers()
        if (ps.isEmpty()) return
        val labels = ps.map { "${Proto.icon(it.transport)} ${it.name} — ${it.transport}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Membres connectés (toucher pour déconnecter)")
            .setItems(labels) { _, i ->
                AlertDialog.Builder(this).setTitle("Déconnecter ${ps[i].name} (${ps[i].transport}) ?")
                    .setPositiveButton("Déconnecter") { _, _ -> Hub.disconnect(ps[i]) }
                    .setNegativeButton("Annuler", null).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun callMenu() {
        if (Hub.call != Hub.Call.IDLE) { startActivity(Intent(this, CallActivity::class.java)); return }
        val pick: (Contact) -> Unit = { c ->
            AlertDialog.Builder(this).setTitle("Appeler ${c.name}")
                .setItems(arrayOf("📞 Appel vocal", "🎥 Appel vidéo")) { _, i -> People.call(this, c, i == 1) }
                .setNegativeButton("Annuler", null).show()
        }
        if (isSalon) People.pick(this, "Qui appeler ?", pick)
        else contact()?.let(pick)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(Intent.createChooser(i, "Choisir le(s) fichier(s) à envoyer"), REQ_FILE) }
        catch (e: Exception) { Ui.toast(this, "Aucun gestionnaire de fichiers disponible.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
        else data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        withTarget { for (u in uris) Hub.sendFile(u, target) }
    }
}
