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
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
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

/** Un élément de la conversation de groupe. */
data class Msg(
    val key: Long,
    val mine: Boolean,
    val origin: String,
    val text: String,
    val kind: Int,
    val fileRef: String? = null,
    val fileName: String? = null,
    val size: Long = -1,
    val durationMs: Int = 0,
    val time: Long = System.currentTimeMillis()
) {
    companion object {
        const val K_TEXT = 0
        const val K_VOICE = 1
        const val K_FILE = 2
        const val K_SYS = 3
    }
    val hour: String get() = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))

    /** Résumé d'une ligne (notifications, bulle). */
    fun summary(): String = when (kind) {
        K_VOICE -> "🎤 Message vocal (${durationMs / 1000} s)"
        K_FILE -> "📄 $fileName"
        else -> text
    }
}

/**
 * Cœur de l'application : connexions avec tous les correspondants (salon de groupe avec relais),
 * écoute permanente Wi-Fi + Bluetooth, messages, fichiers, vocaux et appels.
 */
@SuppressLint("MissingPermission")
object Hub {
    const val PORT = 47800
    const val DISC_PORT = 47801
    private const val MAGIC = "BSCM2|"
    val BT_UUID: UUID = UUID.fromString("7b1d3e2a-5c4f-4b8e-9a61-bc5ca11a6c02")

    enum class Call { IDLE, RINGING_IN, RINGING_OUT, ACTIVE }

    interface Events {
        fun onIncoming(m: Msg)
        fun onCallChanged()
        fun onPeersChanged()
    }

    class Found(val id: String, val name: String, @Volatile var seen: Long)

    private class Incoming(val src: Peer, val origin: String, val name: String, val size: Long, val target: FileStore.Target) {
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
    var unread = 0
    var chatVisible = false
    var events: Events? = null
    var myId = ""; private set

    private val seen = Collections.synchronizedSet(HashSet<Long>())
    private val inFiles = ConcurrentHashMap<Long, Incoming>()

    // Réseau
    @Volatile var running = false; private set
    @Volatile private var tcpServer: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var btServer: BluetoothServerSocket? = null
    @Volatile private var btActive = false
    val wifiFound = ConcurrentHashMap<String, Found>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()

    // Appel
    @Volatile var call = Call.IDLE; private set
    @Volatile var callPeer: Peer? = null; private set
    @Volatile var callVideo = false; private set
    @Volatile var callStartedAt = 0L; private set

    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        myId = Prefs.deviceId(app)
        inited = true
    }

    fun myName() = Prefs.name(app)
    fun readyPeers() = peers.filter { it.alive && it.deviceId.isNotEmpty() }

    // ------------------------------------------------------------------ observateurs (fil principal)

    fun observe(f: () -> Unit) { observers.add(f) }
    fun unobserve(f: () -> Unit) { observers.remove(f) }
    fun changed() = main.post { for (o in observers.toList()) o() }

    private fun addLocal(m: Msg) = main.post { history.add(m); for (o in observers.toList()) o() }
    fun addSystem(t: String) = addLocal(Msg(0, false, "", t, Msg.K_SYS))

    private fun incoming(m: Msg) = main.post {
        history.add(m)
        if (!chatVisible) unread++
        for (o in observers.toList()) o()
        events?.onIncoming(m)
    }

    private fun progress(t: String?) = main.post { progressText = t; for (o in observers.toList()) o() }

    private fun newKey(): Long { val k = rnd.nextLong(); seen.add(k); return k }

    // ------------------------------------------------------------------ démarrage / arrêt

    fun startNetworking() {
        if (running) return
        running = true
        Thread({ tcpLoop() }, "hub-tcp").start()
        Thread({ beaconLoop() }, "hub-beacon").start()
        Thread({ listenLoop() }, "hub-listen").start()
        startBtServer()
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
        wifiFound.clear()
    }

    fun disconnect(p: Peer) = io.execute { p.close("déconnecté", true) }

    private fun addPeer(p: Peer) {
        peers.add(p)
        p.start()
        val hello = pack { putStr(myId); putStr(myName()) }
        io.execute { p.send(Proto.HELLO, hello) }
        changed()
    }

    fun onPeerClosed(p: Peer, reason: String) {
        peers.remove(p)
        for ((k, f) in inFiles) if (f.src === p) { inFiles.remove(k); FileStore.abort(app, f.target); progress(null) }
        if (callPeer === p && call != Call.IDLE) finishCall("📵 Appel coupé : ${p.name} n'est plus joignable")
        if (p.deviceId.isNotEmpty() && peers.none { it.alive && it.deviceId == p.deviceId })
            addSystem("⚠ ${p.name} déconnecté ($reason)")
        changed()
        main.post { events?.onPeersChanged() }
    }

    // ------------------------------------------------------------------ Wi-Fi

    private fun tcpLoop() {
        while (running) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                tcpServer = ss
                while (running) {
                    val s = ss.accept()
                    try { s.tcpNoDelay = true } catch (_: Exception) {}
                    addPeer(Peer(Proto.WIFI, s.inetAddress?.hostAddress ?: "?", false, s.getInputStream(), s.getOutputStream()) { s.close() })
                }
            } catch (e: Exception) {
                try { tcpServer?.close() } catch (_: Exception) {}
                if (running) try { Thread.sleep(3000) } catch (_: Exception) {}
            }
        }
    }

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
                        val ip = pk.address?.hostAddress ?: continue
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

    /** Groupe automatique : le plus petit identifiant appelle l'autre (évite les doublons). */
    private fun autoJoin(ip: String, id: String) {
        if (!Prefs.autoJoin(app) || myId >= id) return
        if (peers.any { it.deviceId == id && it.alive }) return
        val now = System.currentTimeMillis()
        if (now - (lastAttempt[ip] ?: 0L) < 15000) return
        lastAttempt[ip] = now
        connectWifi(ip) {}
    }

    fun isConnectedTo(id: String) = peers.any { it.deviceId == id && it.alive }

    fun connectWifi(ip: String, done: (Boolean) -> Unit) {
        Thread({
            val ok = try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, PORT), 6000)
                s.tcpNoDelay = true
                addPeer(Peer(Proto.WIFI, ip, true, s.getInputStream(), s.getOutputStream()) { s.close() })
                true
            } catch (e: Exception) { false }
            main.post { done(ok) }
        }, "hub-connect").start()
    }

    fun pruneFound() {
        val now = System.currentTimeMillis()
        wifiFound.entries.removeAll { now - it.value.seen > 8000 }
    }

    private fun interfaces() = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
    } catch (_: Exception) { emptyList() }

    fun localIps(): List<String> = interfaces().flatMap { ni ->
        ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }

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
        val label = try { d?.name } catch (_: Exception) { null } ?: d?.address ?: "Bluetooth"
        return Peer(Proto.BT, label, outgoing, s.inputStream, s.outputStream) { s.close() }
    }

    fun connectBt(d: BluetoothDevice, done: (Boolean) -> Unit) {
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
            if (sock != null) addPeer(btPeer(sock, true))
            main.post { done(sock != null) }
        }, "hub-bt-connect").start()
    }

    // ------------------------------------------------------------------ réception

    fun onFrame(p: Peer, type: Int, b: ByteArray) {
        when (type) {
            Proto.HELLO -> {
                val d = DataInputStream(ByteArrayInputStream(b))
                val id = d.getStr(); val name = d.getStr().ifBlank { "Correspondant" }
                if (id == myId) { io.execute { p.close("soi-même", true) }; return }
                val dup = peers.filter { it !== p && it.alive && it.deviceId == id }
                if (dup.isNotEmpty()) {
                    // Double connexion : les deux téléphones gardent la même (celle ouverte par le plus petit identifiant)
                    val all = dup + p
                    val keep = all.firstOrNull { it.outgoing == (myId < id) } ?: dup.first()
                    if (keep === p) { p.name = name; p.deviceId = id }
                    for (q in all) if (q !== keep) io.execute { q.close("doublon", true) }
                    changed()
                    return
                }
                p.name = name
                p.deviceId = id
                addSystem("🔗 $name a rejoint le salon (${p.transport})")
                changed()
                main.post { events?.onPeersChanged() }
            }
            Proto.TEXT -> {
                val d = DataInputStream(ByteArrayInputStream(b))
                val key = d.readLong()
                if (!seen.add(key)) return
                val origin = d.getStr(); val text = d.getStr()
                forward(p, type, b)
                incoming(Msg(key, false, origin, text, Msg.K_TEXT))
            }
            Proto.VOICE -> {
                val d = DataInputStream(ByteArrayInputStream(b))
                val key = d.readLong()
                if (!seen.add(key)) return
                val origin = d.getStr(); val dur = d.readInt(); val n = d.readInt()
                if (n < 0 || n > 6_000_000) return
                val audio = ByteArray(n); d.readFully(audio)
                forward(p, type, b)
                val f = File(voiceDir(), "$key.m4a")
                try { f.writeBytes(audio) } catch (e: Exception) { return }
                incoming(Msg(key, false, origin, "", Msg.K_VOICE, fileRef = f.absolutePath, durationMs = dur))
            }
            Proto.FILE_START -> {
                val d = DataInputStream(ByteArrayInputStream(b))
                val key = d.readLong()
                if (!seen.add(key)) return
                val origin = d.getStr(); val size = d.readLong(); val name = d.getStr()
                try {
                    inFiles[key] = Incoming(p, origin, name, size, FileStore.create(app, name))
                    progress("⬇ Réception de « $name » ($origin)…")
                } catch (e: Exception) {
                    addSystem("Réception impossible de « $name » : ${e.message}")
                }
                forward(p, type, b)
            }
            Proto.FILE_CHUNK -> {
                if (b.size < 8) return
                val key = ByteBuffer.wrap(b, 0, 8).long
                val f = inFiles[key]
                if (f == null) { return }
                if (f.src !== p) return
                try { f.target.out.write(b, 8, b.size - 8) } catch (e: Exception) {
                    inFiles.remove(key); FileStore.abort(app, f.target); progress(null)
                    addSystem("Erreur d'écriture de « ${f.name} »"); return
                }
                forward(p, type, b)
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
                FileStore.finish(app, f.target)
                forward(p, type, b)
                progress(null)
                incoming(Msg(key, false, f.origin, f.name, Msg.K_FILE, fileRef = f.target.ref, fileName = f.name, size = f.received))
            }
            Proto.CALL_REQ -> onCallRequest(p, b.isNotEmpty() && b[0].toInt() == 1)
            Proto.CALL_ACCEPT -> if (p === callPeer && call == Call.RINGING_OUT) beginCall()
            Proto.CALL_REJECT -> if (p === callPeer && call == Call.RINGING_OUT) finishCall("📵 ${p.name} n'a pas décroché (refusé ou occupé)")
            Proto.CALL_END -> if (p === callPeer) endCall()
            Proto.AUDIO -> if (p === callPeer && call == Call.ACTIVE) CallEngine.play(b)
            Proto.VIDEO -> if (p === callPeer && call == Call.ACTIVE) VideoEngine.onRemote(b)
        }
    }

    /** Relais vers les autres membres du salon (le groupe fonctionne même en étoile). */
    private fun forward(from: Peer, type: Int, b: ByteArray) {
        for (q in peers) if (q !== from && q.alive && q.deviceId.isNotEmpty()) q.send(type, b)
    }

    private fun broadcast(type: Int, b: ByteArray, off: Int = 0, len: Int = b.size): Int {
        var n = 0
        for (q in peers) if (q.alive && q.deviceId.isNotEmpty() && q.send(type, b, off, len)) n++
        return n
    }

    fun voiceDir(): File = File(app.filesDir, "voice").apply { mkdirs() }

    // ------------------------------------------------------------------ envoi

    fun sendText(t: String) {
        val text = t.trim()
        if (text.isEmpty()) return
        val key = newKey()
        val payload = pack { writeLong(key); putStr(myName()); putStr(text) }
        io.execute { broadcast(Proto.TEXT, payload) }
        addLocal(Msg(key, true, myName(), text, Msg.K_TEXT))
    }

    fun sendVoice(file: File, durationMs: Int) {
        val key = newKey()
        val dest = File(voiceDir(), "$key.m4a")
        val audio = try { file.readBytes() } catch (e: Exception) { return }
        try { dest.writeBytes(audio); file.delete() } catch (_: Exception) {}
        val payload = pack { writeLong(key); putStr(myName()); writeInt(durationMs); writeInt(audio.size); write(audio) }
        io.execute { broadcast(Proto.VOICE, payload) }
        addLocal(Msg(key, true, myName(), "", Msg.K_VOICE, fileRef = dest.absolutePath, durationMs = durationMs))
    }

    fun sendFile(uri: Uri) {
        Thread({ doSendFile(uri) }, "hub-file").start()
    }

    private fun doSendFile(uri: Uri) {
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
                if (broadcast(Proto.FILE_START, pack { writeLong(key); putStr(myName()); writeLong(size); putStr(name) }) == 0)
                    throw IOException("aucun correspondant connecté")
                val slow = peers.any { it.transport == Proto.BT }
                val chunk = if (slow) 8192 else 32768
                val buf = ByteArray(8 + chunk)
                ByteBuffer.wrap(buf).putLong(key)
                var last = -1
                while (true) {
                    val n = s.read(buf, 8, chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    if (broadcast(Proto.FILE_CHUNK, buf, 0, 8 + n) == 0) throw IOException("connexion perdue")
                    sent += n
                    if (size > 0) {
                        val pct = (sent * 100 / size).toInt()
                        if (pct != last) { last = pct; progress("⬆ Envoi de « $name » : $pct %") }
                    } else progress("⬆ Envoi de « $name » : ${FileStore.size(sent)}")
                }
                broadcast(Proto.FILE_END, ByteBuffer.allocate(8).putLong(key).array())
            }
            progress(null)
            addLocal(Msg(key, true, myName(), name, Msg.K_FILE, fileRef = uri.toString(), fileName = name, size = sent))
        } catch (e: Exception) {
            progress(null)
            addSystem("Échec de l'envoi de « $name » : ${e.message}")
        }
    }

    // ------------------------------------------------------------------ appels (1 à 1)

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
        finishCall("📵 Appel de ${p.name} refusé")
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
        CallEngine.start(app, if (callPeer?.transport == Proto.WIFI) 16000 else 8000)
        addSystem(if (callVideo) "🎥 Appel vidéo avec ${callPeer?.name}" else "📞 Appel vocal avec ${callPeer?.name}")
        notifyCall()
    }

    @Synchronized
    private fun endCall() {
        val name = callPeer?.name ?: ""
        when (call) {
            Call.ACTIVE -> finishCall("📞 Appel terminé avec $name (${duration()})")
            Call.RINGING_IN -> finishCall("📵 Appel manqué de $name")
            Call.RINGING_OUT -> finishCall("📵 Appel annulé")
            Call.IDLE -> {}
        }
    }

    @Synchronized
    private fun finishCall(msg: String) {
        call = Call.IDLE
        CallEngine.stop(app)
        VideoEngine.stopCamera()
        callPeer = null
        addSystem(msg)
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
