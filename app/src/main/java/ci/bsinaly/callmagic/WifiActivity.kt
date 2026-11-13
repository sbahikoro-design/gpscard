package ci.bsinaly.callmagic

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Mode Wi-Fi : appareils détectés sur le réseau, connexion directe ou par adresse IP. */
class WifiActivity : Activity() {

    private lateinit var ipText: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val observer: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() { Hub.pruneFound(); render(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        HubService.start(this)
        val root = Ui.screen(this)
        root.addView(Ui.title(this, "📶 Wi-Fi"))
        root.addView(Ui.text(this, "Même réseau Wi-Fi, ou téléphones connectés au point d'accès de l'un d'eux. Internet n'est pas nécessaire. Plusieurs téléphones peuvent rejoindre le même salon.", 13f, Ui.MUTED))

        val me = Ui.card(this)
        me.addView(Ui.text(this, "Ce téléphone : ${Prefs.name(this)}", 15f, Ui.TEXT, true))
        ipText = Ui.text(this, "", 14f, Ui.GOLD)
        me.addView(ipText, Ui.lp(this, top = 4))
        status = Ui.text(this, "🟢 Visible par les autres appareils", 13f, Ui.MUTED)
        me.addView(status, Ui.lp(this, top = 4))
        root.addView(me, Ui.lp(this, top = 16))

        root.addView(Ui.text(this, "Appareils détectés", 18f, Ui.TEXT, true), Ui.lp(this, top = 22))
        list = Ui.vertical(this)
        root.addView(list)

        root.addView(Ui.button(this, "⌨  Se connecter avec une adresse IP", Ui.GREY) { manual() }, Ui.lp(this, top = 22))
        root.addView(Ui.button(this, "💬  Ouvrir le salon", Ui.GREEN) {
            startActivity(Intent(this, ChatActivity::class.java))
        }, Ui.lp(this, top = 10))
    }

    override fun onResume() {
        super.onResume()
        val ips = Hub.localIps()
        ipText.text = if (ips.isEmpty()) "⚠ Aucun réseau — activez le Wi-Fi ou le point d'accès." else "Adresse : " + ips.joinToString("  ·  ")
        Hub.observe(observer)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Hub.unobserve(observer)
        handler.removeCallbacks(ticker)
    }

    private fun render() {
        list.removeAllViews()
        val found = Hub.wifiFound.entries.toList()
        if (found.isEmpty()) {
            list.addView(Ui.text(this, "Recherche en cours… Ouvrez BS CALLMagic sur l'autre téléphone.", 14f, Ui.MUTED), Ui.lp(this))
            return
        }
        for (entry in found) {
            val ip = entry.key
            val f = entry.value
            val connected = Hub.isConnectedTo(f.id)
            val label = (if (connected) "✅  " else "📱  ") + "${f.name}   ·   $ip" + (if (connected) "  (connecté)" else "")
            val b = Ui.button(this, label, if (connected) Ui.GREEN else Ui.CARD) {
                if (connected) startActivity(Intent(this, ChatActivity::class.java)) else connect(ip)
            }
            list.addView(b, Ui.lp(this, top = 8))
        }
    }

    private fun connect(ip: String) {
        status.text = "⏳ Connexion à $ip…"
        Hub.connectWifi(ip) { ok ->
            status.text = "🟢 Visible par les autres appareils"
            if (ok) startActivity(Intent(this, ChatActivity::class.java))
            else Ui.toast(this, "Connexion impossible à $ip. Vérifiez que BS CALLMagic est ouvert sur l'autre téléphone et que vous êtes sur le même réseau.")
        }
    }

    private fun manual() {
        val e = EditText(this).apply {
            hint = "ex. 192.168.43.1"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Adresse IP de l'autre téléphone")
            .setView(e)
            .setPositiveButton("Connecter") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let { connect(it) } }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
