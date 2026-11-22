package com.bsinaly.bsmagickeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/** Mode Wi-Fi : le téléphone parle au petit serveur « BS MAGIC KEYBOARD PC ». */
object WifiLink : Transport {

    const val PORT = 5577
    const val DISCOVERY_PORT = 5578

    @Volatile private var sock: Socket? = null
    @Volatile private var out: Writer? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile var state = "Non connecté"
    private var mask = 0

    data class Pc(val ip: String, val name: String)

    override fun isReady() = sock != null && out != null

    fun connect(ctx: Context, ip: String, pin: String, label: String, done: (Boolean, String) -> Unit) {
        val prefs = ctx.applicationContext.getSharedPreferences("bsmk", Context.MODE_PRIVATE)
        exec.execute {
            close()
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip.trim(), PORT), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 5000
                val w = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                w.write(JSONObject().put("t", "hello").put("pin", pin.trim()).toString() + "\n")
                w.flush()
                val resp = r.readLine()
                if (resp == "OK") {
                    s.soTimeout = 0
                    sock = s; out = w; mask = 0
                    Link.current = WifiLink
                    state = "Connecté en Wi-Fi à $label"
                    prefs.edit().putString("ip", ip.trim()).putString("pin", pin.trim()).apply()
                    Link.changed()
                    done(true, state)
                    Thread {
                        try { while (r.readLine() != null) { /* rien */ } } catch (_: Exception) {}
                        if (sock === s) { close(); state = "Connexion Wi-Fi perdue"; Link.changed() }
                    }.start()
                } else {
                    s.close()
                    done(false, if (resp == "BADPIN") "Code incorrect : recopiez le code affiché sur le PC" else "Réponse inattendue du PC")
                }
            } catch (e: Exception) {
                done(false, "Impossible de joindre $ip — le programme PC est-il lancé ? (${e.javaClass.simpleName})")
            }
        }
    }

    private fun close() {
        try { sock?.close() } catch (_: Exception) {}
        sock = null; out = null
    }

    fun disconnect() {
        exec.execute { close(); state = "Déconnecté"; Link.changed() }
    }

    /** Recherche les PC qui font tourner le serveur sur le réseau local. */
    fun discover(done: (List<Pc>) -> Unit) {
        Thread {
            val found = LinkedHashMap<String, Pc>()
            try {
                DatagramSocket().use { ds ->
                    ds.broadcast = true
                    ds.soTimeout = 700
                    val msg = "BSMK?".toByteArray()
                    val targets = LinkedHashSet<InetAddress>()
                    targets.add(InetAddress.getByName("255.255.255.255"))
                    try {
                        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                            if (ni.isUp && !ni.isLoopback) ni.interfaceAddresses.forEach { ia ->
                                ia.broadcast?.let { targets.add(it) }
                            }
                        }
                    } catch (_: Exception) {}
                    repeat(2) {
                        for (t in targets) {
                            try { ds.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT)) } catch (_: Exception) {}
                        }
                    }
                    val end = System.currentTimeMillis() + 2500
                    val buf = ByteArray(512)
                    while (System.currentTimeMillis() < end) {
                        val p = DatagramPacket(buf, buf.size)
                        try { ds.receive(p) } catch (_: SocketTimeoutException) { continue }
                        val txt = String(p.data, 0, p.length, Charsets.UTF_8)
                        if (txt.startsWith("BSMK!")) {
                            val ip = p.address.hostAddress ?: continue
                            found[ip] = Pc(ip, txt.split("|").getOrElse(1) { "PC" })
                        }
                    }
                }
            } catch (_: Exception) {}
            done(found.values.toList())
        }.start()
    }

    // ------------------------------------------------------------------ envoi
    private fun send(o: JSONObject) {
        exec.execute {
            val w = out ?: return@execute
            try {
                w.write(o.toString() + "\n")
                w.flush()
            } catch (_: Exception) {
                close(); state = "Connexion Wi-Fi perdue"; Link.changed()
            }
        }
    }

    private fun cmd(t: String) = JSONObject().put("t", t)

    override fun move(dx: Int, dy: Int) = send(cmd("move").put("x", dx).put("y", dy))

    override fun buttons(mask: Int) {
        val changed = mask xor this.mask
        this.mask = mask
        for ((bit, name) in listOf(1 to "left", 2 to "right", 4 to "middle")) {
            if (changed and bit != 0) send(cmd("btn").put("b", name).put("d", if (mask and bit != 0) 1 else 0))
        }
    }

    override fun scroll(v: Int) = send(cmd("scroll").put("v", v))

    override fun text(s: String) = send(cmd("text").put("s", s))

    override fun key(name: String, mods: Int) =
        send(cmd("key").put("k", name).put("m", JSONArray(Mod.names(mods))))

    override fun media(name: String) = send(cmd("media").put("k", name))
}
