package ci.bsinaly.callmagic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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

/** Salon de groupe : messages, vocaux, fichiers ; lancement des appels audio/vidéo. */
class ChatActivity : Activity() {

    companion object {
        private const val REQ_FILE = 10
        private const val REQ_PERMS = 11
    }

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
    private lateinit var emptyBox: LinearLayout

    private var rendered = 0
    private var afterPerms: (() -> Unit)? = null
    private val observer: () -> Unit = { render() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        HubService.start(this)
        val d = { v: Int -> Ui.dp(this, v) }
        val root = Ui.vertical(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)

        // En-tête
        val header = Ui.horizontal(this).apply {
            setBackgroundColor(Ui.CARD); gravity = Gravity.CENTER_VERTICAL
            setPadding(d(14), d(10), d(10), d(10))
        }
        val titles = Ui.vertical(this)
        titleView = Ui.text(this, "Salon", 18f, Ui.TEXT, true).apply { setSingleLine() }
        subtitle = Ui.text(this, "", 12f, Ui.MUTED).apply { maxLines = 2 }
        titles.addView(titleView); titles.addView(subtitle)
        titles.setOnClickListener { members() }
        header.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        btnFile = Ui.iconButton(this, "📎", Ui.GREY) { pickFile() }
        btnCall = Ui.iconButton(this, "📞", Ui.GREEN) { chooseCall() }
        header.addView(btnFile, LinearLayout.LayoutParams(d(46), d(46)).apply { rightMargin = d(8) })
        header.addView(btnCall, LinearLayout.LayoutParams(d(46), d(46)))
        root.addView(header, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        callBar = Ui.button(this, "", Ui.GREEN) { startActivity(Intent(this, CallActivity::class.java)) }
        root.addView(callBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(d(10), d(8), d(10), 0) })

        progressText = Ui.text(this, "", 13f, Ui.GOLD).apply { setPadding(d(16), d(8), d(16), 0) }
        root.addView(progressText)

        emptyBox = Ui.card(this)
        emptyBox.addView(Ui.text(this, "Aucun correspondant connecté. Les messages partiront dès qu'un téléphone sera connecté.", 13f, Ui.TEXT))
        val er = Ui.horizontal(this)
        er.addView(Ui.button(this, "📶 Wi-Fi", Ui.BLUE) { startActivity(Intent(this, WifiActivity::class.java)) },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = d(4) })
        er.addView(Ui.button(this, "🔵 Bluetooth", Ui.BLUE) { startActivity(Intent(this, BluetoothActivity::class.java)) },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = d(4) })
        emptyBox.addView(er, Ui.lp(this, top = 8))
        root.addView(emptyBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(d(10), d(8), d(10), 0) })

        scroll = ScrollView(this).apply { isFillViewport = true }
        msgs = Ui.vertical(this).apply { setPadding(d(10), d(8), d(10), d(8)) }
        scroll.addView(msgs)
        root.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // Saisie + appuyer pour parler
        val inputRow = Ui.horizontal(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8), d(6), d(8), d(10))
        }
        input = EditText(this).apply {
            hint = "Message au salon…"
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
                    if (!VoiceRecorder.canRecord(this)) { needPerms(listOf(Manifest.permission.RECORD_AUDIO)) {} }
                    else if (VoiceRecorder.start(this)) {
                        talk.background = Ui.rounded(Ui.RED, d(24).toFloat())
                        progressText.text = "🔴 Enregistrement… relâchez pour envoyer au salon"
                        Ui.show(progressText, true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (VoiceRecorder.recording) VoiceRecorder.stop(this)
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
    }

    override fun onResume() {
        super.onResume()
        Hub.chatVisible = true
        Hub.unread = 0
        HubService.instance?.cancelMessageNotification()
        HubService.instance?.refreshOverlay()
        Hub.observe(observer)
        msgs.removeAllViews()
        rendered = 0
        render()
    }

    override fun onPause() {
        super.onPause()
        Hub.chatVisible = false
        Hub.unobserve(observer)
        if (VoiceRecorder.recording) VoiceRecorder.stop(this, send = false)
    }

    // ------------------------------------------------------------------ affichage

    private fun render() {
        val ps = Hub.readyPeers()
        titleView.text = if (ps.isEmpty()) "Salon" else if (ps.size == 1) ps[0].name else "Salon · ${ps.size + 1} membres"
        subtitle.text = if (ps.isEmpty()) "🔴 Personne n'est connecté" else "🟢 " + ps.joinToString(", ") { "${it.name} (${it.transport})" }
        Ui.show(emptyBox, ps.isEmpty())
        btnCall.isEnabled = ps.isNotEmpty() && Hub.call == Hub.Call.IDLE
        btnCall.alpha = if (btnCall.isEnabled) 1f else 0.4f
        btnFile.isEnabled = ps.isNotEmpty()
        btnFile.alpha = if (btnFile.isEnabled) 1f else 0.4f

        Ui.show(callBar, Hub.call != Hub.Call.IDLE)
        callBar.text = when (Hub.call) {
            Hub.Call.RINGING_IN -> "📲 Appel entrant de ${Hub.callPeer?.name} — toucher pour répondre"
            Hub.Call.RINGING_OUT -> "📞 Appel de ${Hub.callPeer?.name}… — afficher"
            Hub.Call.ACTIVE -> "🟢 En appel avec ${Hub.callPeer?.name} (${Hub.duration()}) — revenir à l'appel"
            Hub.Call.IDLE -> ""
        }

        val h = Hub.history
        if (h.size < rendered) { msgs.removeAllViews(); rendered = 0 }
        if (h.size > rendered) {
            for (i in rendered until h.size) msgs.addView(bubble(h[i]))
            rendered = h.size
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        if (!VoiceRecorder.recording) onProgress(Hub.progressText)
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
        if (!m.mine) box.addView(Ui.text(this, m.origin, 12f, Ui.GOLD, true))
        val body = when (m.kind) {
            Msg.K_VOICE -> "🎤 Message vocal · ${m.durationMs / 1000} s\n▶ Toucher pour écouter"
            Msg.K_FILE -> "📄 ${m.fileName}\n${FileStore.size(m.size)} · " + (if (m.mine) "envoyé" else "reçu — toucher pour ouvrir")
            else -> m.text
        }
        box.addView(Ui.text(this, body, 15f, Ui.TEXT).apply { maxWidth = maxW })
        box.addView(Ui.text(this, m.hour, 10f, Ui.MUTED).apply { gravity = Gravity.END },
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

    private fun sendText() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (Hub.readyPeers().isEmpty()) { Ui.toast(this, "Aucun correspondant connecté."); return }
        Hub.sendText(t)
        input.setText("")
    }

    private fun members() {
        val ps = Hub.readyPeers()
        if (ps.isEmpty()) return
        val names = ps.map { "❌ Déconnecter ${it.name} (${it.transport})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Membres directement connectés")
            .setItems(names) { _, i -> Hub.disconnect(ps[i]) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun chooseCall() {
        val ps = Hub.readyPeers()
        if (ps.isEmpty()) { Ui.toast(this, "Aucun correspondant connecté."); return }
        val items = ArrayList<String>()
        for (p in ps) {
            items.add("📞 Appel vocal — ${p.name}")
            items.add("🎥 Appel vidéo — ${p.name}" + if (p.transport == Proto.BT) " (lent en Bluetooth)" else "")
        }
        AlertDialog.Builder(this)
            .setTitle("Appeler")
            .setItems(items.toTypedArray()) { _, i -> call(ps[i / 2], i % 2 == 1) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun call(p: Peer, video: Boolean) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) perms.add(Manifest.permission.CAMERA)
        needPerms(perms) {
            if (Hub.startCall(p, video)) startActivity(Intent(this, CallActivity::class.java))
            else Ui.toast(this, "Un appel est déjà en cours.")
        }
    }

    private fun needPerms(perms: List<String>, then: () -> Unit) {
        if (Build.VERSION.SDK_INT < 23) { then(); return }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { then(); return }
        afterPerms = then
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        HubService.start(this, HubService.ACTION_REFRESH)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) afterPerms?.invoke()
        else Ui.toast(this, "Autorisation refusée.")
        afterPerms = null
    }

    private fun pickFile() {
        if (Hub.readyPeers().isEmpty()) return
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
        for (u in uris) Hub.sendFile(u)
    }
}
