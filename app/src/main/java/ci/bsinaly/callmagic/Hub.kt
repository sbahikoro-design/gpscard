package ci.bsinaly.callmagic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Un élément de conversation (salon ou privé). */
data class Msg(
    val key: Long,
    val mine: Boolean,
    val originId: String,
    val origin: String,
    val to: String,              // "" = salon, sinon identifiant du destinataire
    val text: String,
    val kind: Int,
    val fileRef: String? = null,
    val fileName: String? = null,
    val size: Long = -1,
    val durationMs: Int = 0,
    val time: Long = System.currentTimeMillis(),
    val via: String = ""
) {
    companion object {
        const val K_TEXT = 0
        const val K_VOICE = 1
        const val K_FILE = 2
        const val K_SYS = 3
    }

    /** Conversation : "" = salon, sinon l'autre personne. */
    val conv: String get() = when {
        to.isEmpty() -> ""
        mine || kind == K_SYS -> to
        else -> originId
    }

    val hour: String get() = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))

    fun summary(): String = when (kind) {
        K_VOICE -> "🎤 Message vocal (${durationMs / 1000} s)"
        K_FILE -> "📄 $fileName"
        else -> text
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", key); put("m", mine); put("oi", originId); put("o", origin); put("to", to); put("t", text)
        put("kd", kind); put("fr", fileRef ?: ""); put("fn", fileName ?: ""); put("s", size); put("d", durationMs)
        put("tm", time); put("v", via)
    }
}

private fun msgFromJson(o: JSONObject) = Msg(
    o.optLong("k"), o.optBoolean("m"), o.optString("oi"), o.optString("o"), o.optString("to"), o.optString("t"),
    o.optInt("kd"), o.optString("fr").ifEmpty { null }, o.optString("fn").ifEmpty { null }, o.optLong("s", -1),
    o.optInt("d"), o.optLong("tm"), o.optString("v")
)

/**
 * Cœur de l'application : connexions (Wi-Fi, Bluetooth, Internet IP, Internet code), salon de groupe
 * avec relais, conversations privées, fichiers, vocaux, appels ; connexion intelligente à la demande.
 */
@SuppressLint("MissingPermission")
object Hub {
    const val PORT = 47800
    const val DISC_PORT = 47801
    private const val MAGIC = "BSCM3|"
    val BT_UUID: UUID = UUID.fromString("7b1d3e2a-5c4f-4b8e-9a61-bc5ca11a6c03")

    enum class Call { IDLE, RINGING_IN, RINGING_OUT, ACTIVE }

    interface Events {
        fun onIncoming(m: Msg)
        fun onCallChanged()
        fun onPeersChanged()
    }

    class Found(val id: String, val name: String, @Volatile var seen: Long)

    private class Incoming(
        val src: Peer, val originId: String, val origin: String, val to: String,
        val name: String, val size: Long, val target: FileStore.Target?
    ) {
        var received = 0L
        var lastPct = -1
    }

    lateinit var app: Context
        private set
    private var inited = false
    val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val rnd = SecureRandom()

    val peers = CopyOnWriteArrayList<Peer>()
    val history = ArrayList<Msg>()           // fil principal uniquement
    private val observers = ArrayList<() -> Unit>()
    var progressText: String? = null; private set
    val unreadBy = HashMap<String, Int>()     // conversation -> non lus
    var visibleConv: String? = null          // conversation affichée à l'écran
    var events: Events? = null
    var myId = ""; private set

    private val seen = Collections.synchronizedSet(HashSet<Long>())
    private val inFiles = ConcurrentHashMap<Long, Incoming>()

    @Volatile var running = false; private set
    @Volatile private var tcpServer: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var btServer: BluetoothServerSocket? = null
    @Volatile private var btActive = false
    val wifiFound = ConcurrentHashMap<String, Found>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()

    @Volatile var call = Call.IDLE; private set
    @Volatile var callPeer: Peer? = null; private set
    @Volatile var callVideo = false; private set
    @Volatile var callStartedAt = 0L; private set

    val unread: Int get() = unreadBy.values.sum()

    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        myId = Prefs.deviceId(app)
        Contacts.init(app)
        loadHistory()
        inited = true
    }

    fun myName() = Prefs.name(app)
    fun myCode() = Contacts.codeOf(myId)
    fun readyPeers() = peers.filter { it.alive && it.deviceId.isNotEmpty() }
    fun peerFor(id: String, via: String? = null): Peer? =
        if (id.isEmpty()) null else peers.firstOrNull { it.alive && it.deviceId == id && (via == null || it.transport == via) }

    // ------------------------------------------------------------------ observateurs (fil principal)

    fun observe(f: () -> Unit) { observers.add(f) }
    fun unobserve(f: () -> Unit) { observers.remove(f) }
    fun changed() = main.post { for (o in observers.toList()) o() }

    private fun addLocal(m: Msg) = main.post { history.add(m); trim(); saveSoon(); for (o in observers.toList()) o() }
    fun addSystem(t: String, conv: String = "") = addLocal(Msg(0, false, "", "", conv, t, Msg.K_SYS))

    private fun incoming(m: Msg) = main.post {
        history.add(m); trim(); saveSoon()
        if (visibleConv != m.conv) unreadBy[m.conv] = (unreadBy[m.conv] ?: 0) + 1
        for (o in observers.toList()) o()
        events?.onIncoming(m)
    }

    fun markRead(conv: String) { unreadBy.remove(conv); changed() }

    private fun progress(t: String?) = main.post { progressText = t; for (o in observers.toList()) o() }

    private fun newKey(): Long { val k = rnd.nextLong(); seen.add(k); return k }

    // ------------------------------------------------------------------ historique persistant

    private val saver = Runnable { saveHistory() }
    private fun saveSoon() { main.removeCallbacks(saver); main.postDelayed(saver, 1500) }
    private fun trim() { while (history.size > 1500) history.removeAt(0) }

    private fun historyFile() = File(app.filesDir, "history.json")

    private fun loadHistory() {
        try {
            val f = historyFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) history.add(msgFromJson(arr.getJSONObject(i)))
            for (m in history) if (m.key != 0L) seen.add(m.key)
        } catch (_: Exception) {}
    }

    private fun saveHistory() {
        val snapshot = history.toList()
        Thread({
            try {
                val arr = JSONArray()
                for (m in snapshot) arr.put(m.toJson())
                val tmp = File(app.filesDir, "history.tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(historyFile())
            } catch (_: Exception) {}
        }, "hub-save").start()
    }

    fun clearConversation(conv: String) {
        history.removeAll { it.conv == conv }
        unreadBy.remove(conv)
        saveSoon(); changed()
    }

    // ------------------------------------------------------------------ démarrage / arrêt

    fun startNetworking() {
        if (running) return
        running = true
        Thread({ tcpLoop() }, "hub-tcp").start()
        Thread({ beaconLoop() }, "hub-beacon").start()
        Thread({ listenLoop() }, "hub-listen").start()
        Thread({ keepAliveLoop() }, "hub-ping").start()
        startBtServer()
        Relay.start()
    }

    fun stopAll() {
        running = false
        if (call != Call.IDLE) hangUp()
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { btServer?.close() } catch (_: Exception) {}
        tcpServer = null; udp = null; btServer = null
        val list = peers.toList()
        io.execute { for (p in list) p.close("arrêt", true) }
        Thread({ Relay.stop() }, "relay-stop").start()
        wifiFound.clear()
        saveHistory()
    }

    fun disconnect(p: Peer) = io.execute { p.close("déconnecté", true) }

    /** Maintient les liaisons ouvertes (box, opérateurs) et détecte les liaisons mortes. */
    private fun keepAliveLoop() {
        while (running) {
            try { Thread.sleep(25_000) } catch (_: Exception) {}
            for (p in peers) if (p.alive) io.execute { p.send(Proto.PING, Proto.EMPTY) }
        }
    }

    private fun addPeer(p: Peer): Peer {
        peers.add(p)
        p.start()
        val hello = pack { putStr(myId); putStr(myName()); writeInt(Proto.VERSION) }
        io.execute { p.send(Proto.HELLO, hello) }
        changed()
        return p
    }

    fun addRelayPeer(link: RelayLink, code: String, outgoing: Boolean): Peer =
        addPeer(Peer(Proto.NET_CODE, code, outgoing, link.input, link.output) { link.close() })

    fun onPeerClosed(p: Peer, reason: String) {
        peers.remove(p)
        for ((k, f) in inFiles) if (f.src === p) { inFiles.remove(k); f.target?.let { FileStore.abort(app, it) }; progress(null) }
        if (callPeer === p && call != Call.IDLE) finishCall("📵 Appel coupé : ${p.name} n'est plus joignable", p.deviceId)
        if (p.deviceId.isNotEmpty() && peers.none { it.alive && it.deviceId == p.deviceId })
            addSystem("⚠ ${p.name} déconnecté (${p.transport} : $reason)")
        changed()
        main.post { events?.onPeersChanged() }
    }

    // ------------------------------------------------------------------ Wi-Fi / Internet par IP

    private fun tcpLoop() {
        while (running) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                tcpServer = ss
                while (running) {
                    val s = ss.accept()
                    tune(s)
                    val via = if (Proto.isLocal(s.inetAddress)) Proto.WIFI else Proto.NET_IP
                    addPeer(Peer(via, clean(s.inetAddress), false, s.getInputStream(), s.getOutputStream()) { s.close() })
                }
            } catch (e: Exception) {
                try { tcpServer?.close() } catch (_: Exception) {}
                if (running) try { Thread.sleep(3000) } catch (_: Exception) {}
            }
        }
    }

    private fun tune(s: Socket) {
        try { s.tcpNoDelay = true } catch (_: Exception) {}
        try { s.keepAlive = true } catch (_: Exception) {}
    }

    private fun clean(a: InetAddress?): String =
        (a?.hostAddress ?: "?").substringBefore('%').removePrefix("::ffff:")

    private fun beaconLoop() {
        while (running) {
            try {
                val ds = DatagramSocket()
                try {
                    ds.broadcast = true
                    while (running) {
                        val data = (MAGIC + myId + "|" + myName()).toByteArray()
                        val targets = (broadcastAddresses() + InetAddress.getByName("255.255.255.255")).distinct()
                        for (a in targets) try { ds.send(DatagramPacket(data, data.size, a, DISC_PORT)) } catch (_: Exception) {}
                        Thread.sleep(2000)
                    }
                } finally { ds.close() }
            } catch (e: Exception) {
                if (running) try { Thread.sleep(3000) } catch (_: Exception) {}
            }
        }
    }

    private fun listenLoop() {
        while (running) {
            try {
                val ds = DatagramSocket(null)
                ds.reuseAddress = true
                ds.broadcast = true
                ds.bind(InetSocketAddress(DISC_PORT))
                ds.soTimeout = 1500
                udp = ds
                val buf = ByteArray(512)
                try {
                    while (running) {
                        val pk = DatagramPacket(buf, buf.size)
                        try { ds.receive(pk) } catch (e: java.net.SocketTimeoutException) { continue }
                        val msg = String(pk.data, 0, pk.length)
                        if (!msg.startsWith(MAGIC)) continue
                        val parts = msg.removePrefix(MAGIC).split('|', limit = 2)
                        if (parts.size < 2 || parts[0] == myId) continue
                        val ip = clean(pk.address)
                        val f = wifiFound[ip]
                        if (f != null && f.id == parts[0]) f.seen = System.currentTimeMillis()
                        else { wifiFound[ip] = Found(parts[0], parts[1], System.currentTimeMillis()); changed() }
                        autoJoin(ip, parts[0])
                    }
                } finally { ds.close() }
            } catch (e: Exception) {
                if (running) try { Thread.sleep(3000) } catch (_: Exception) {}
            }
        }
    }

    /** Salon automatique : le plus petit identifiant appelle l'autre (évite les doublons). */
    private fun autoJoin(ip: String, id: String) {
        if (!Prefs.autoJoin(app) || myId >= id) return
        if (peerFor(id, Proto.WIFI) != null) return
        val now = System.currentTimeMillis()
        if (now - (lastAttempt[ip] ?: 0L) < 15000) return
        lastAttempt[ip] = now
        connectIp(ip, PORT) {}
    }

    fun isConnectedTo(id: String) = peerFor(id) != null

    /** Connexion TCP directe (réseau local ou Internet, IPv4 ou IPv6). */
    fun connectIp(ip: String, port: Int, timeoutMs: Int = 6000, done: (Peer?) -> Unit) {
        Thread({
            val p = try {
                val addr = InetAddress.getByName(ip.trim().removePrefix("[").removeSuffix("]"))
                val s = Socket()
                s.connect(InetSocketAddress(addr, port), timeoutMs)
                tune(s)
                val via = if (Proto.isLocal(addr)) Proto.WIFI else Proto.NET_IP
                addPeer(Peer(via, clean(addr), true, s.getInputStream(), s.getOutputStream()) { s.close() })
            } catch (e: Exception) { null }
            main.post { done(p) }
        }, "hub-connect").start()
    }

    fun pruneFound() {
        val now = System.currentTimeMillis()
        wifiFound.entries.removeAll { now - it.value.seen > 8000 }
    }

    private fun interfaces() = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
    } catch (_: Exception) { emptyList() }

    /** Adresses IPv4 du réseau local. */
    fun localIps(): List<String> = interfaces().flatMap { ni ->
        ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }

    /** Adresses IPv6 publiques (joignables par Internet si l'opérateur l'autorise). */
    fun globalIpv6(): List<String> = interfaces().flatMap { ni ->
        ni.inetAddresses.toList().filterIsInstance<Inet6Address>().filter { !Proto.isLocal(it) }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
    }.distinct()

    private fun broadcastAddresses(): List<InetAddress> = interfaces().flatMap { ni ->
        ni.interfaceAddresses.mapNotNull { it.broadcast }
    }

    // ------------------------------------------------------------------ Bluetooth

    fun btAdapter() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasBtPerms(c: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 31 -> listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN
        ).all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        Build.VERSION.SDK_INT >= 23 ->
            c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun canBtConnect(): Boolean = Build.VERSION.SDK_INT < 31 ||
        app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun btReady(): Boolean = try { canBtConnect() && btAdapter()?.isEnabled == true } catch (_: Exception) { false }

    fun startBtServer() {
        if (!running || btActive || !canBtConnect()) return
        val a = btAdapter() ?: return
        if (!a.isEnabled) return
        btActive = true
        Thread({
            try {
                val ss = a.listenUsingInsecureRfcommWithServiceRecord("BS CALLMagic", BT_UUID)
                btServer = ss
                while (running) {
                    val s = ss.accept()
                    addPeer(btPeer(s, false))
                }
            } catch (_: Exception) {
            } finally {
                btActive = false
                try { btServer?.close() } catch (_: Exception) {}
                btServer = null
            }
        }, "hub-bt").start()
    }

    private fun btPeer(s: BluetoothSocket, outgoing: Boolean): Peer {
        val d = s.remoteDevice
        val label = try { d?.name } catch (_: Exception) { null }
        val p = Peer(Proto.BT, d?.address ?: "?", outgoing, s.inputStream, s.outputStream) { s.close() }
        if (!label.isNullOrBlank()) p.name = label
        return p
    }

    fun connectBt(d: BluetoothDevice, done: (Peer?) -> Unit) {
        try { btAdapter()?.cancelDiscovery() } catch (_: Exception) {}
        Thread({
            var s: BluetoothSocket? = null
            try {
                s = d.createInsecureRfcommSocketToServiceRecord(BT_UUID)
                s.connect()
            } catch (e: Exception) {
                try { s?.close() } catch (_: Exception) {}
                s = null
                try {
                    s = d.createRfcommSocketToServiceRecord(BT_UUID)
                    s.connect()
                } catch (e2: Exception) {
                    try { s?.close() } catch (_: Exception) {}
                    s = null
                }
            }
            val sock = s
            val p = if (sock != null) addPeer(btPeer(sock, true)) else null
            main.post { done(p) }
        }, "hub-bt-connect").start()
    }

    fun connectBtAddress(mac: String, done: (Peer?) -> Unit) {
        val d = try { btAdapter()?.getRemoteDevice(mac) } catch (_: Exception) { null }
        if (d == null) { main.post { done(null) }; return }
        connectBt(d, done)
    }

    // ------------------------------------------------------------------ connexion intelligente

    /** Ordre d'essai des accès pour joindre une personne. */
    fun plan(c: Contact, via: String?): List<String> {
        val acc = Contacts.access(c)
        val order = listOf(Proto.WIFI, Proto.NET_IP, Proto.NET_CODE, Proto.BT)
        val wanted = if (via != null) listOf(via) else order
        return wanted.filter { v -> acc.any { it.via == v } || (v == Proto.NET_CODE && c.code.isNotEmpty() && Relay.online) }
    }

    /**
     * Joindre une personne : réutilise une liaison existante, sinon essaie les accès un par un
     * (Wi-Fi local, Internet direct, Internet par code, Bluetooth). [step] informe de l'essai en cours.
     */
    fun reach(c: Contact, via: String?, step: (String) -> Unit, done: (Peer?) -> Unit) {
        val existing = if (c.id.isNotEmpty()) peerFor(c.id, via) else null
        if (existing != null) { done(existing); return }
        val attempts = plan(c, via).toMutableList()
        lateinit var next: () -> Unit
        next = {
            if (attempts.isEmpty()) done(null)
            else {
                val v = attempts.removeAt(0)
                step("${Proto.icon(v)} Essai par $v…")
                val after: (Peer?) -> Unit = { p ->
                    if (p == null) next() else waitHello(p, c) { ok -> if (ok != null) done(ok) else next() }
                }
                when (v) {
                    Proto.WIFI -> {
                        val ip = wifiFound.entries.firstOrNull { it.value.id == c.id }?.key ?: c.lanIp
                        if (ip.isEmpty()) next() else connectIp(ip, PORT, 5000, after)
                    }
                    Proto.NET_IP -> if (c.ip.isEmpty()) next() else connectIp(c.ip, c.port, 8000, after)
                    Proto.NET_CODE -> if (c.code.isEmpty()) next() else Relay.connect(c.code, after)
                    Proto.BT -> if (c.bt.isEmpty() || !btReady()) next() else connectBtAddress(c.bt, after)
                    else -> next()
                }
            }
        }
        next()
    }

    /** Attend la présentation du correspondant (au plus 8 s). */
    private fun waitHello(p: Peer, c: Contact, done: (Peer?) -> Unit) {
        Thread({
            var n = 0
            while (n++ < 80 && p.alive && p.deviceId.isEmpty()) Thread.sleep(100)
            val ok = when {
                p.alive && p.deviceId.isNotEmpty() -> p
                c.id.isNotEmpty() -> peerFor(c.id)       // doublon fermé : l'autre liaison est gardée
                else -> null
            }
            main.post { done(ok) }
        }, "hub-wait").start()
    }

    // ------------------------------------------------------------------ réception

    fun onFrame(p: Peer, type: Int, b: ByteArray) {
        when (type) {
            Proto.PING -> {}
            Proto.HELLO -> {
                val d = DataInputStream(ByteArrayInputStream(b))
                val id = d.getStr(); val name = d.getStr().ifBlank { "Correspondant" }
                if (id == myId) { io.execute { p.close("soi-même", true) }; return }
                val dup = peers.filter { it !== p && it.alive && it.deviceId == id && it.transport == p.transport }
                if (dup.isNotEmpty()) {
                    // Double liaison par le même accès : les deux téléphones gardent la même
                    val all = dup + p
                    val keep = all.firstOrNull { it.outgoing == (myId < id) } ?: dup.first()
                    if (keep === p) { p.name = name; p.deviceId = id }
                    for (q in all) if (q !== keep) io.execute { q.close("doublon", true) }
                    changed()
                    return
                }
                val already = peers.any { it !== p && it.alive && it.deviceId == id }
                p.name = name
                p.deviceId = id
                Contacts.upsertFromPeer(p)
                if (!already) addSystem("🔗 $name connecté (${Proto.icon(p.transport)} ${p.transport})")
                changed()
                main.post { events?.onPeersChanged() }
            }
            Proto.TEXT -> {
                val d = DataInputStream(ByteArrayInputStream(b))
                val key = d.readLong()
                if (!seen.add(key)) return
                val oid = d.getStr(); val origin = d.getStr(); val to = d.getStr(); val text = d.getStr()
                if (to.isNotEmpty() && to != myId) { route(p, to, type, b); return }
                if (to.isEmpty()) route(p, "", type, b)
                incoming(Msg(key, false, oid, origin, to, text, Msg.K_TEXT, via = p.transport))
            }
            Proto.VOICE -> {
                val d = DataInputStream(ByteArrayInputStream(b))
                val key = d.readLong()
                if (!seen.add(key)) return
                val oid = d.getStr(); val origin = d.getStr(); val to = d.getStr()
                if (to.isNotEmpty() && to != myId) { route(p, to, type, b); return }
                if (to.isEmpty()) route(p, "", type, b)
                val dur = d.readInt()
                val audio = d.getBytes(6_000_000)
                val f = File(voiceDir(), "$key.m4a")
                try { f.writeBytes(audio) } catch (e: Exception) { return }
                incoming(Msg(key, false, oid, origin, to, "", Msg.K_VOICE, fileRef = f.absolutePath, durationMs = dur, via = p.transport))
            }
            Proto.FILE_START -> {
                val d = DataInputStream(ByteArrayInputStream(b))
                val key = d.readLong()
                if (!seen.add(key)) return
                val oid = d.getStr(); val origin = d.getStr(); val to = d.getStr()
                val size = d.readLong(); val name = d.getStr()
                val forMe = to.isEmpty() || to == myId
                val target = if (forMe) try { FileStore.create(app, name) } catch (e: Exception) {
                    addSystem("Réception impossible de « $name » : ${e.message}"); null
                } else null
                inFiles[key] = Incoming(p, oid, origin, to, name, size, target)
                if (target != null) progress("⬇ Réception de « $name » ($origin)…")
                if (to != myId) route(p, to, type, b)
            }
            Proto.FILE_CHUNK -> {
                if (b.size < 8) return
                val key = ByteBuffer.wrap(b, 0, 8).long
                val f = inFiles[key] ?: return
                if (f.src !== p) return
                if (f.to != myId) route(p, f.to, type, b)
                val t = f.target ?: return
                try { t.out.write(b, 8, b.size - 8) } catch (e: Exception) {
                    inFiles.remove(key); FileStore.abort(app, t); progress(null)
                    addSystem("Erreur d'écriture de « ${f.name} »"); return
                }
                f.received += b.size - 8
                if (f.size > 0) {
                    val pct = (f.received * 100 / f.size).toInt()
                    if (pct != f.lastPct) { f.lastPct = pct; progress("⬇ « ${f.name} » : $pct %") }
                } else progress("⬇ « ${f.name} » : ${FileStore.size(f.received)}")
            }
            Proto.FILE_END -> {
                if (b.size < 8) return
                val key = ByteBuffer.wrap(b).long
                val f = inFiles[key] ?: return
                if (f.src !== p) return
                inFiles.remove(key)
                if (f.to != myId) route(p, f.to, type, b)
                val t = f.target ?: return
                FileStore.finish(app, t)
                progress(null)
                incoming(Msg(key, false, f.originId, f.origin, f.to, f.name, Msg.K_FILE, fileRef = t.ref,
                    fileName = f.name, size = f.received, via = p.transport))
            }
            Proto.CALL_REQ -> onCallRequest(p, b.isNotEmpty() && b[0].toInt() == 1)
            Proto.CALL_ACCEPT -> if (p === callPeer && call == Call.RINGING_OUT) beginCall()
            Proto.CALL_REJECT -> if (p === callPeer && call == Call.RINGING_OUT) finishCall("📵 ${p.name} n'a pas décroché (refusé ou occupé)", p.deviceId)
            Proto.CALL_END -> if (p === callPeer) endCall()
            Proto.AUDIO -> if (p === callPeer && call == Call.ACTIVE) CallEngine.play(b)
            Proto.VIDEO -> if (p === callPeer && call == Call.ACTIVE) VideoEngine.onRemote(b)
        }
    }

    /**
     * Acheminement : un message privé va directement à son destinataire s'il est connecté,
     * sinon il est relayé par les membres du salon ; un message de salon va à tout le monde.
     */
    private fun route(from: Peer?, to: String, type: Int, b: ByteArray, off: Int = 0, len: Int = b.size): Int {
        if (to.isNotEmpty()) {
            val direct = peerFor(to)
            if (direct != null) return if (direct !== from && direct.send(type, b, off, len)) 1 else 0
        }
        var n = 0
        val sentTo = HashSet<String>()
        for (q in peers) {
            if (q === from || !q.alive || q.deviceId.isEmpty()) continue
            if (from != null && q.deviceId == from.deviceId) continue
            if (!sentTo.add(q.deviceId)) continue      // une seule liaison par personne
            if (q.send(type, b, off, len)) n++
        }
        return n
    }

    fun voiceDir(): File = File(app.filesDir, "voice").apply { mkdirs() }

    // ------------------------------------------------------------------ envoi (to = "" : salon)

    fun sendText(t: String, to: String = "") {
        val text = t.trim()
        if (text.isEmpty()) return
        val key = newKey()
        val payload = pack { writeLong(key); putStr(myId); putStr(myName()); putStr(to); putStr(text) }
        val via = viaFor(to)
        io.execute {
            if (route(null, to, Proto.TEXT, payload) == 0) addSystem("⚠ Message non remis : personne n'est joignable.", to)
        }
        addLocal(Msg(key, true, myId, myName(), to, text, Msg.K_TEXT, via = via))
    }

    fun sendVoice(file: File, durationMs: Int, to: String = "") {
        val key = newKey()
        val dest = File(voiceDir(), "$key.m4a")
        val audio = try { file.readBytes() } catch (e: Exception) { return }
        try { dest.writeBytes(audio); file.delete() } catch (_: Exception) {}
        val payload = pack { writeLong(key); putStr(myId); putStr(myName()); putStr(to); writeInt(durationMs); putBytes(audio) }
        val via = viaFor(to)
        io.execute {
            if (route(null, to, Proto.VOICE, payload) == 0) addSystem("⚠ Vocal non remis : personne n'est joignable.", to)
        }
        addLocal(Msg(key, true, myId, myName(), to, "", Msg.K_VOICE, fileRef = dest.absolutePath, durationMs = durationMs, via = via))
    }

    private fun viaFor(to: String): String = if (to.isEmpty()) "" else peerFor(to)?.transport ?: "relais du salon"

    fun sendFile(uri: Uri, to: String = "") {
        Thread({ doSendFile(uri, to) }, "hub-file").start()
    }

    private fun doSendFile(uri: Uri, to: String) {
        val cr = app.contentResolver
        var name = "fichier"
        var size = -1L
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        val key = newKey()
        try {
            val ins = cr.openInputStream(uri) ?: throw IOException("lecture impossible")
            var sent = 0L
            ins.use { s ->
                val start = pack { writeLong(key); putStr(myId); putStr(myName()); putStr(to); writeLong(size); putStr(name) }
                if (route(null, to, Proto.FILE_START, start) == 0) throw IOException("personne n'est joignable")
                val slow = peers.any { it.alive && (it.transport == Proto.BT || it.transport == Proto.NET_CODE) }
                val chunk = if (slow) 16384 else 32768
                val buf = ByteArray(8 + chunk)
                ByteBuffer.wrap(buf).putLong(key)
                var last = -1
                while (true) {
                    val n = s.read(buf, 8, chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    if (route(null, to, Proto.FILE_CHUNK, buf, 0, 8 + n) == 0) throw IOException("connexion perdue")
                    sent += n
                    if (size > 0) {
                        val pct = (sent * 100 / size).toInt()
                        if (pct != last) { last = pct; progress("⬆ Envoi de « $name » : $pct %") }
                    } else progress("⬆ Envoi de « $name » : ${FileStore.size(sent)}")
                }
                route(null, to, Proto.FILE_END, ByteBuffer.allocate(8).putLong(key).array())
            }
            progress(null)
            addLocal(Msg(key, true, myId, myName(), to, name, Msg.K_FILE, fileRef = uri.toString(), fileName = name, size = sent, via = viaFor(to)))
        } catch (e: Exception) {
            progress(null)
            addSystem("Échec de l'envoi de « $name » : ${e.message}", to)
        }
    }

    // ------------------------------------------------------------------ appels (1 à 1)

    private fun convOfCall(): String = callPeer?.deviceId ?: ""

    /** Qualité audio adaptée à l'accès : (fréquence, durée d'une trame en ms). */
    fun audioProfile(transport: String?): Pair<Int, Int> = when (transport) {
        Proto.WIFI -> 16000 to 20
        Proto.NET_IP -> 16000 to 40
        Proto.NET_CODE -> 8000 to 60
        else -> 8000 to 20
    }

    @Synchronized
    fun startCall(p: Peer, video: Boolean): Boolean {
        if (call != Call.IDLE || !p.alive) return false
        callPeer = p; callVideo = video; call = Call.RINGING_OUT
        io.execute { p.send(Proto.CALL_REQ, byteArrayOf(if (video) 1 else 0)) }
        notifyCall()
        return true
    }

    @Synchronized
    private fun onCallRequest(p: Peer, video: Boolean) {
        if (call != Call.IDLE) { io.execute { p.send(Proto.CALL_REJECT, Proto.EMPTY) }; return }
        callPeer = p; callVideo = video; call = Call.RINGING_IN
        notifyCall()
    }

    @Synchronized
    fun accept() {
        val p = callPeer ?: return
        if (call != Call.RINGING_IN) return
        io.execute { p.send(Proto.CALL_ACCEPT, Proto.EMPTY) }
        beginCall()
    }

    @Synchronized
    fun reject() {
        val p = callPeer ?: return
        if (call != Call.RINGING_IN) return
        io.execute { p.send(Proto.CALL_REJECT, Proto.EMPTY) }
        finishCall("📵 Appel de ${p.name} refusé", p.deviceId)
    }

    @Synchronized
    fun hangUp() {
        if (call == Call.IDLE) return
        callPeer?.let { p -> io.execute { p.send(Proto.CALL_END, Proto.EMPTY) } }
        endCall()
    }

    @Synchronized
    private fun beginCall() {
        call = Call.ACTIVE
        callStartedAt = System.currentTimeMillis()
        Interphone.stopAll()
        val (rate, frameMs) = audioProfile(callPeer?.transport)
        CallEngine.start(app, rate, frameMs)
        addSystem((if (callVideo) "🎥 Appel vidéo" else "📞 Appel vocal") + " avec ${callPeer?.name} (${callPeer?.transport})", convOfCall())
        notifyCall()
    }

    @Synchronized
    private fun endCall() {
        val name = callPeer?.name ?: ""
        val conv = convOfCall()
        when (call) {
            Call.ACTIVE -> finishCall("📞 Appel terminé avec $name (${duration()})", conv)
            Call.RINGING_IN -> finishCall("📵 Appel manqué de $name", conv)
            Call.RINGING_OUT -> finishCall("📵 Appel annulé", conv)
            Call.IDLE -> {}
        }
    }

    @Synchronized
    private fun finishCall(msg: String, conv: String = "") {
        call = Call.IDLE
        CallEngine.stop(app)
        VideoEngine.stopCamera()
        callPeer = null
        addSystem(msg, conv)
        notifyCall()
    }

    private fun notifyCall() {
        changed()
        main.post { events?.onCallChanged() }
    }

    fun duration(): String {
        val s = ((System.currentTimeMillis() - callStartedAt) / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", s / 60, s % 60)
    }
}
