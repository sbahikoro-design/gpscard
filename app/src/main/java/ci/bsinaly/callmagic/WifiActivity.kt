package ci.bsinaly.callmagic

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Mode Wi-Fi : écoute TCP, annonce/détection par diffusion UDP, connexion directe par IP. */
class WifiActivity : Activity() {

    companion object {
        const val PORT = 47800
        const val DISC_PORT = 47801
        const val MAGIC = "BSCM1|"
    }

    @Volatile private var active = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val peers = LinkedHashMap<String, Pair<String, Long>>() // ip -> (nom, vu à)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var ipText: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.screen(this)
        root.addView(Ui.title(this, "📶 Échange Wi-Fi"))
        root.addView(Ui.text(this, "Les deux téléphones doivent être sur le même réseau Wi-Fi, ou l'un connecté au point d'accès de l'autre. Internet n'est pas nécessaire.", 13f, Ui.MUTED))

        val me = Ui.card(this)
        me.addView(Ui.text(this, "Ce téléphone : ${Prefs.name(this)}", 15f, Ui.TEXT, true))
        ipText = Ui.text(this, "", 14f, Ui.GOLD)
        me.addView(ipText, Ui.lp(this, top = 4))
        status = Ui.text(this, "", 13f, Ui.MUTED)
        me.addView(status, Ui.lp(this, top = 4))
        root.addView(me, Ui.lp(this, top = 16))

        root.addView(Ui.text(this, "Appareils détectés", 18f, Ui.TEXT, true), Ui.lp(this, top = 22))
        list = Ui.vertical(this)
        root.addView(list)

        root.addView(Ui.button(this, "⌨  Se connecter avec une adresse IP", Ui.GREY) { manual() }, Ui.lp(this, top = 22))
        root.addView(Ui.text(this, "Si l'autre téléphone n'apparaît pas, saisissez l'adresse affichée sur son écran.", 12f, Ui.MUTED), Ui.lp(this, top = 4))
    }

    override fun onStart() {
        super.onStart()
        ipText.text = localIps().let { if (it.isEmpty()) "⚠ Aucun réseau Wi-Fi détecté — activez le Wi-Fi ou le point d'accès." else "Adresse : " + it.joinToString("  ·  ") }
        if (!Link.connected) start()
        status.text = if (Link.connected) "Une conversation est déjà ouverte." else "🟢 En attente — visible par les autres appareils"
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        stop()
    }

    // ------------------------------------------------------------------ réseau

    private fun start() {
        if (active) return
        active = true
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("bscallmagic").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        Thread({ serverLoop() }, "wifi-server").start()
        Thread({ listenLoop() }, "wifi-listen").start()
        Thread({ beaconLoop() }, "wifi-beacon").start()
    }

    private fun stop() {
        active = false
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        server = null; udp = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    private fun serverLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            server = ss
            if (!active) { ss.close(); return }
            while (active) {
                val s = ss.accept()
                if (Link.connected) { try { s.close() } catch (_: Exception) {}; continue }
                onSocket(s)
            }
        } catch (e: Exception) {
            if (active) runOnUiThread { status.text = "⚠ Écoute impossible : ${e.message}" }
        }
    }

    private fun beaconLoop() {
        try {
            val ds = DatagramSocket()
            try {
                ds.broadcast = true
                while (active) {
                    val data = (MAGIC + Prefs.name(this)).toByteArray()
                    val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                    for (a in targets.distinct()) {
                        try { ds.send(DatagramPacket(data, data.size, a, DISC_PORT)) } catch (_: Exception) {}
                    }
                    Thread.sleep(2000)
                }
            } finally { ds.close() }
        } catch (_: Exception) {}
    }

    private fun listenLoop() {
        try {
            val ds = DatagramSocket(null)
            ds.reuseAddress = true
            ds.broadcast = true
            ds.bind(InetSocketAddress(DISC_PORT))
            ds.soTimeout = 1500
            udp = ds
            val buf = ByteArray(512)
            while (active) {
                val p = DatagramPacket(buf, buf.size)
                try { ds.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }
                val msg = String(p.data, 0, p.length)
                if (!msg.startsWith(MAGIC)) continue
                val ip = p.address.hostAddress ?: continue
                if (ip in localIps()) continue
                val name = msg.removePrefix(MAGIC).ifBlank { ip }
                runOnUiThread {
                    val isNew = !peers.containsKey(ip)
                    peers[ip] = name to System.currentTimeMillis()
                    if (isNew) render()
                }
            }
            ds.close()
        } catch (_: Exception) {}
    }

    private fun onSocket(s: Socket) {
        try { s.tcpNoDelay = true } catch (_: Exception) {}
        Link.attach(this, s.getInputStream(), s.getOutputStream(), Link.WIFI, Prefs.name(this)) {
            try { s.close() } catch (_: Exception) {}
        }
        runOnUiThread { startActivity(Intent(this, ChatActivity::class.java)) }
    }

    private fun connect(ip: String) {
        if (Link.connected) { startActivity(Intent(this, ChatActivity::class.java)); return }
        status.text = "⏳ Connexion à $ip…"
        Thread({
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, PORT), 6000)
                onSocket(s)
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "🟢 En attente — visible par les autres appareils"
                    Ui.toast(this, "Connexion impossible à $ip. Vérifiez que BS CALLMagic est ouvert en mode Wi-Fi sur l'autre téléphone.")
                }
            }
        }, "wifi-connect").start()
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

    private fun render() {
        val now = System.currentTimeMillis()
        peers.entries.removeAll { now - it.value.second > 8000 }
        list.removeAllViews()
        if (peers.isEmpty()) {
            list.addView(Ui.text(this, "Recherche en cours… Ouvrez BS CALLMagic en mode Wi-Fi sur l'autre téléphone.", 14f, Ui.MUTED), Ui.lp(this))
            return
        }
        for ((ip, v) in peers) {
            list.addView(Ui.button(this, "📱  ${v.first}   ·   $ip", Ui.CARD) { connect(ip) }, Ui.lp(this, top = 8))
        }
    }

    // ------------------------------------------------------------------ adresses

    private fun interfaces() = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
    } catch (_: Exception) { emptyList() }

    private fun localIps(): List<String> = interfaces().flatMap { ni ->
        ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }

    private fun broadcastAddresses(): List<InetAddress> = interfaces().flatMap { ni ->
        ni.interfaceAddresses.mapNotNull { it.broadcast }
    }
}
