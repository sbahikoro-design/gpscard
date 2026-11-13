package ci.bsinaly.callmagic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Conversation : messages, fichiers et appel vocal avec le correspondant connecté. */
class ChatActivity : Activity(), Link.Listener {

    companion object {
        private const val REQ_FILE = 10
        private const val REQ_MIC = 11
    }

    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var btnCall: Button
    private lateinit var btnFile: Button
    private lateinit var callBox: LinearLayout
    private lateinit var callText: TextView
    private lateinit var btnSpeaker: Button
    private lateinit var btnMute: Button
    private lateinit var btnHang: Button
    private lateinit var progressText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var msgs: LinearLayout
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var endBox: LinearLayout
    private lateinit var endText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var rendered = 0
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var incomingDialog: AlertDialog? = null
    private var afterMic: (() -> Unit)? = null
    private var micDenied: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (Link.call == Link.Call.ACTIVE) {
                callText.text = "🟢 En appel avec ${Link.peerName} — ${Link.duration()}"
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        subtitle = Ui.text(this, "", 12f, Ui.MUTED)
        titles.addView(titleView); titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        btnFile = Ui.iconButton(this, "📎", Ui.GREY) { pickFile() }
        btnCall = Ui.iconButton(this, "📞", Ui.GREEN) { callOut() }
        header.addView(btnFile, LinearLayout.LayoutParams(d(48), d(48)).apply { rightMargin = d(8) })
        header.addView(btnCall, LinearLayout.LayoutParams(d(48), d(48)))
        root.addView(header, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // Bandeau d'appel
        callBox = Ui.card(this)
        callText = Ui.text(this, "", 16f, Ui.GOLD, true)
        callBox.addView(callText)
        val callRow = Ui.horizontal(this)
        btnSpeaker = Ui.button(this, "", Ui.GREY) { toggleSpeaker() }
        btnMute = Ui.button(this, "", Ui.GREY) { toggleMute() }
        btnHang = Ui.button(this, "Raccrocher", Ui.RED) { Link.hangUp() }
        for (b in listOf(btnSpeaker, btnMute, btnHang)) {
            b.textSize = 13f
            callRow.addView(b, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { setMargins(d(3), 0, d(3), 0) })
        }
        callBox.addView(callRow, Ui.lp(this, top = 10))
        root.addView(callBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(d(10), d(10), d(10), 0) })

        progressText = Ui.text(this, "", 13f, Ui.GOLD).apply { setPadding(d(16), d(8), d(16), 0) }
        root.addView(progressText)

        // Messages
        scroll = ScrollView(this).apply { isFillViewport = true }
        msgs = Ui.vertical(this).apply { setPadding(d(10), d(8), d(10), d(8)) }
        scroll.addView(msgs)
        root.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // Saisie
        inputRow = Ui.horizontal(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8), d(6), d(8), d(10))
        }
        input = EditText(this).apply {
            hint = "Votre message…"
            setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); textSize = 16f
            background = Ui.rounded(Ui.CARD, d(22).toFloat())
            setPadding(d(16), d(10), d(16), d(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        inputRow.addView(Ui.iconButton(this, "➤", Ui.GREEN) { sendText() },
            LinearLayout.LayoutParams(d(48), d(48)).apply { leftMargin = d(8) })
        root.addView(inputRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // Fin de connexion
        endBox = Ui.card(this)
        endText = Ui.text(this, "", 14f, Ui.TEXT)
        endBox.addView(endText)
        endBox.addView(Ui.button(this, "Retour", Ui.GREEN) { finish() }, Ui.lp(this, top = 10))
        root.addView(endBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(d(10), d(6), d(10), d(10)) })
        Ui.show(endBox, false)
    }

    override fun onResume() {
        super.onResume()
        Link.listener = this
        msgs.removeAllViews()
        rendered = 0
        onUpdate()
        onProgress(Link.progressText)
        if (!Link.connected) onDisconnected(Link.lastReason)
    }

    override fun onPause() {
        super.onPause()
        if (Link.listener === this) Link.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
        handler.removeCallbacksAndMessages(null)
        incomingDialog?.dismiss()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!Link.connected) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Quitter la conversation ?")
            .setMessage("La connexion avec ${Link.peerName} sera fermée.")
            .setPositiveButton("Quitter") { _, _ -> Link.leave(); finish() }
            .setNegativeButton("Rester", null)
            .show()
    }

    // ------------------------------------------------------------------ Link.Listener

    override fun onUpdate() {
        titleView.text = Link.peerName
        subtitle.text = if (Link.connected) "🟢 Connecté par ${Link.transport}" else "🔴 Déconnecté"
        val h = Link.history
        if (h.size < rendered) { msgs.removeAllViews(); rendered = 0 }
        if (h.size > rendered) {
            for (i in rendered until h.size) msgs.addView(bubble(h[i]))
            rendered = h.size
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        updateCall()
    }

    override fun onProgress(text: String?) {
        progressText.text = text ?: ""
        Ui.show(progressText, text != null)
    }

    override fun onDisconnected(reason: String) {
        updateCall()
        Ui.show(inputRow, false)
        Ui.show(endBox, true)
        endText.text = "La conversation est terminée.\n${reason.ifBlank { "Connexion fermée." }}"
        btnCall.isEnabled = false; btnCall.alpha = 0.4f
        btnFile.isEnabled = false; btnFile.alpha = 0.4f
    }

    // ------------------------------------------------------------------ messages

    private fun sendText() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        Link.sendText(t)
        input.setText("")
    }

    private fun bubble(m: Msg): View {
        val d = { v: Int -> Ui.dp(this, v) }
        if (m.system) {
            return Ui.center(Ui.text(this, "${m.text}  ·  ${m.hour}", 12f, Ui.MUTED)).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(0, d(8), 0, d(4)) }
            }
        }
        val box = Ui.vertical(this).apply {
            background = Ui.rounded(if (m.mine) Ui.BUBBLE_ME else Ui.BUBBLE_THEM, d(16).toFloat())
            setPadding(d(12), d(8), d(12), d(6))
        }
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        val body = if (m.fileName != null) {
            "📄 ${m.fileName}\n${FileStore.size(m.fileSize)} · " + if (m.mine) "envoyé" else "reçu — toucher pour ouvrir"
        } else m.text
        box.addView(Ui.text(this, body, 15f, Ui.TEXT).apply { maxWidth = maxW })
        box.addView(Ui.text(this, m.hour, 10f, Ui.MUTED).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        if (m.fileRef != null && m.fileName != null) {
            box.setOnClickListener { FileStore.open(this, m.fileRef, m.fileName) }
        }
        return LinearLayout(this).apply {
            gravity = if (m.mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = d(6) }
            addView(box, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        }
    }

    // ------------------------------------------------------------------ fichiers

    private fun pickFile() {
        if (!Link.connected) return
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
        for (u in uris) Link.sendFile(this, u)
    }

    // ------------------------------------------------------------------ appels

    private fun callOut() {
        if (!Link.connected || Link.call != Link.Call.IDLE) return
        withMic({ Link.startOutgoingCall() }, { Ui.toast(this, "Le micro est nécessaire pour appeler.") })
    }

    private fun withMic(action: () -> Unit, denied: () -> Unit) {
        if (Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action(); return
        }
        afterMic = action; micDenied = denied
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (ok) afterMic?.invoke() else micDenied?.invoke()
        afterMic = null; micDenied = null
    }

    private fun updateCall() {
        val c = Link.call
        btnCall.isEnabled = Link.connected && c == Link.Call.IDLE
        btnCall.alpha = if (btnCall.isEnabled) 1f else 0.4f
        btnFile.isEnabled = Link.connected
        btnFile.alpha = if (Link.connected) 1f else 0.4f
        Ui.show(callBox, c != Link.Call.IDLE)
        val active = c == Link.Call.ACTIVE
        Ui.show(btnSpeaker, active)
        Ui.show(btnMute, active)
        btnHang.text = if (active) "Raccrocher" else "Annuler"
        when (c) {
            Link.Call.IDLE -> {
                stopRinging(); dismissIncoming()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                handler.removeCallbacks(tick)
            }
            Link.Call.RINGING_OUT -> {
                callText.text = "📞 Appel de ${Link.peerName}… ça sonne"
                startRingback()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            Link.Call.RINGING_IN -> {
                callText.text = "📲 Appel entrant de ${Link.peerName}"
                Ui.show(callBox, false)
                showIncoming()
            }
            Link.Call.ACTIVE -> {
                stopRinging(); dismissIncoming()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                btnSpeaker.text = if (CallEngine.speaker) "🔊 Haut-parleur" else "🔈 Écouteur"
                btnMute.text = if (CallEngine.muted) "🔇 Micro coupé" else "🎤 Micro actif"
                handler.removeCallbacks(tick); handler.post(tick)
            }
        }
    }

    private fun showIncoming() {
        if (incomingDialog?.isShowing == true) return
        startRinging()
        incomingDialog = AlertDialog.Builder(this)
            .setTitle("📲 Appel entrant")
            .setMessage("${Link.peerName} vous appelle par ${Link.transport}.")
            .setCancelable(false)
            .setPositiveButton("Décrocher") { _, _ ->
                stopRinging()
                withMic({ Link.acceptCall() }, {
                    Ui.toast(this, "Micro refusé : vous entendrez le correspondant, mais il ne vous entendra pas.")
                    Link.acceptCall()
                })
            }
            .setNegativeButton("Refuser") { _, _ -> stopRinging(); Link.rejectCall() }
            .create()
        incomingDialog?.show()
    }

    private fun dismissIncoming() {
        incomingDialog?.let { if (it.isShowing) it.dismiss() }
        incomingDialog = null
    }

    private fun toggleSpeaker() {
        CallEngine.setSpeaker(this, !CallEngine.speaker)
        updateCall()
    }

    private fun toggleMute() {
        CallEngine.muted = !CallEngine.muted
        updateCall()
    }

    // ------------------------------------------------------------------ sonneries

    @Suppress("DEPRECATION")
    private fun startRinging() {
        if (ringtone?.isPlaying == true) return
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtone?.play()
        } catch (_: Exception) {}
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(longArrayOf(0, 800, 800), 0)
        } catch (_: Exception) {}
    }

    private fun startRingback() {
        if (tone != null) return
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (_: Exception) {}
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() } catch (_: Exception) {}
        try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}
        tone = null
    }
}
