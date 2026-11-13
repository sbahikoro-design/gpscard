package ci.bsinaly.callmagic

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Joindre quelqu'un par Internet avec son code BS, même derrière une box ou un opérateur mobile.
 * Les deux téléphones se retrouvent sur deux serveurs publics de messagerie (MQTT) en parallèle ;
 * tout le contenu est chiffré de bout en bout (ECDH P-256 + AES-256-GCM) : les serveurs ne voient rien.
 */
object Relay {
    private const val ROOT = "bscallmagic/v3/"
    private const val T_REQ = 1
    private const val T_ACK = 2
    private const val T_DATA = 3
    private const val T_CLOSE = 4
    private const val CHUNK = 48_000

    class Broker(val label: String, val uris: Array<String>) {
        @Volatile var client: MqttAsyncClient? = null
        @Volatile var connected = false
        @Volatile var connecting = false
    }

    class Presence {
        val on: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())
        @Volatile var name = ""
        @Volatile var ts = 0L
    }

    private class Pending(val code: String, val kp: KeyPair, val done: (Peer?) -> Unit)

    val brokers = listOf(
        Broker("HiveMQ", arrayOf("ssl://broker.hivemq.com:8883", "tcp://broker.hivemq.com:1883")),
        Broker("EMQX", arrayOf("ssl://broker.emqx.io:8883", "tcp://broker.emqx.io:1883"))
    )

    private val presence = ConcurrentHashMap<String, Presence>()
    private val pending = ConcurrentHashMap<Long, Pending>()
    internal val links = ConcurrentHashMap<Long, RelayLink>()
    private val rnd = SecureRandom()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    var myCode = ""
        private set

    val online: Boolean get() = brokers.any { it.connected }

    fun statusText(): String {
        val on = brokers.filter { it.connected }.map { it.label }
        return if (on.isEmpty()) "🌐 Internet : hors ligne (pas de connexion Internet)"
        else "🌐 Internet : en ligne (${on.joinToString(" + ")}) · chiffré de bout en bout"
    }

    fun isOnline(code: String): Boolean {
        val p = presence[code] ?: return false
        // Présence publiée toutes les 5 min : au-delà de 16 min sans nouvelle, on la considère périmée
        return p.on.isNotEmpty() && System.currentTimeMillis() - p.ts < 16 * 60_000
    }

    private fun presTopic(code: String) = ROOT + "p/" + code
    private fun inboxTopic(code: String) = ROOT + "i/" + code
    private fun presPayload(on: Boolean) = "${if (on) 1 else 0}|${System.currentTimeMillis()}|${Prefs.name(Hub.app)}".toByteArray()

    // ------------------------------------------------------------------ connexion aux serveurs

    fun start() {
        if (running) return
        running = true
        myCode = Contacts.codeOf(Hub.myId)
        Thread({
            var tick = 0
            while (running) {
                for (i in brokers.indices) {
                    val b = brokers[i]
                    if (!b.connected && !b.connecting && (b.client == null || tick % 2 == 0)) connectBroker(i)
                }
                // Présence rafraîchie toutes les 5 minutes
                if (tick % 15 == 14) for (i in brokers.indices) publishPresence(i, true)
                tick++
                try { Thread.sleep(20_000) } catch (_: Exception) {}
            }
        }, "relay-supervisor").start()
    }

    fun stop() {
        running = false
        for (l in links.values) l.close()
        for (i in brokers.indices) {
            val b = brokers[i]
            val c = b.client ?: continue
            try { c.publish(presTopic(myCode), presPayload(false), 1, true) } catch (_: Exception) {}
            try { c.disconnect(1500) } catch (_: Exception) {}
            b.client = null; b.connected = false; b.connecting = false
        }
    }

    private fun connectBroker(i: Int) {
        val b = brokers[i]
        try {
            val old = b.client
            if (old != null && old.isConnected) { b.connected = true; return }
            try { old?.close(true) } catch (_: Exception) {}
            val cid = "bscm-$myCode-$i-" + java.lang.Long.toHexString(rnd.nextLong()).take(6)
            val c = MqttAsyncClient(b.uris[0], cid, MemoryPersistence())
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    b.connected = true; b.connecting = false
                    onConnected(i)
                }
                override fun connectionLost(cause: Throwable?) {
                    b.connected = false
                    for (p in presence.values) p.on.remove(i)
                    Hub.changed()
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != null && message != null) try { onMessage(i, topic, message.payload) } catch (_: Exception) {}
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val o = MqttConnectOptions().apply {
                serverURIs = b.uris
                isAutomaticReconnect = true
                isCleanSession = true
                keepAliveInterval = 30
                connectionTimeout = 15
                maxInflight = 1000
                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                setWill(presTopic(myCode), presPayload(false), 1, true)
            }
            b.client = c
            b.connecting = true
            c.connect(o, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { b.connecting = false }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) { b.connecting = false; b.connected = false }
            })
        } catch (e: Throwable) {
            b.connecting = false
        }
    }

    private fun onConnected(i: Int) {
        val c = brokers[i].client ?: return
        try {
            c.subscribe(inboxTopic(myCode), 1)
            val codes = Contacts.codes().filter { it != myCode }.distinct()
            if (codes.isNotEmpty()) c.subscribe(codes.map { presTopic(it) }.toTypedArray(), IntArray(codes.size) { 1 })
        } catch (_: Exception) {}
        publishPresence(i, true)
        Hub.changed()
    }

    /** Suivre la présence d'un nouveau contact. */
    fun watch(code: String) {
        if (code.isEmpty() || code == myCode) return
        for (b in brokers) if (b.connected) try { b.client?.subscribe(presTopic(code), 1) } catch (_: Exception) {}
    }

    fun publishPresence(i: Int, on: Boolean) {
        val b = brokers[i]
        if (!b.connected) return
        try { b.client?.publish(presTopic(myCode), presPayload(on), 1, true) } catch (_: Exception) {}
    }

    /** Nouveau nom : republier la présence. */
    fun refreshPresence() = Thread({ for (i in brokers.indices) publishPresence(i, true) }, "relay-pres").start()

    /** Publication fiable vers la boîte d'un code (attend s'il y a trop de messages en vol). */
    internal fun publishTo(i: Int, code: String, payload: ByteArray) {
        val b = brokers[i]
        var tries = 0
        while (true) {
            val c = b.client
            if (c == null || !b.connected) throw IOException("serveur Internet déconnecté")
            try {
                c.publish(inboxTopic(code), payload, 1, false)
                return
            } catch (e: MqttException) {
                if (e.reasonCode.toShort() == MqttException.REASON_CODE_MAX_INFLIGHT && tries++ < 500) {
                    Thread.sleep(20); continue
                }
                throw IOException("envoi Internet impossible : ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ messages reçus

    private fun onMessage(i: Int, topic: String, payload: ByteArray) {
        if (topic.startsWith(ROOT + "p/")) {
            val code = topic.substringAfterLast('/')
            val parts = String(payload).split('|', limit = 3)
            if (parts.size < 3) return
            val p = presence.getOrPut(code) { Presence() }
            if (parts[0] == "1") p.on.add(i) else p.on.remove(i)
            p.ts = parts[1].toLongOrNull() ?: 0L
            p.name = parts[2]
            Hub.changed()
            return
        }
        if (topic != inboxTopic(myCode)) return
        val d = DataInputStream(ByteArrayInputStream(payload))
        val type = d.readUnsignedByte()
        val sid = d.readLong()
        when (type) {
            T_REQ -> {
                val from = d.getStr(); val pub = d.getBytes(1000)
                if (from == myCode || links.containsKey(sid)) return
                val kp = genKeyPair()
                val key = derive(kp.private, pub, sid)
                val link = RelayLink(i, sid, from, key, initiator = false)
                links[sid] = link
                val ack = pack { writeByte(T_ACK); writeLong(sid); putStr(myCode); putBytes(kp.public.encoded) }
                Thread({
                    try { publishTo(i, from, ack); Hub.addRelayPeer(link, from, false) }
                    catch (_: Exception) { links.remove(sid) }
                }, "relay-ack").start()
            }
            T_ACK -> {
                val from = d.getStr(); val pub = d.getBytes(1000)
                val pend = pending.remove(sid) ?: return
                if (pend.code != from) return
                val key = derive(pend.kp.private, pub, sid)
                val link = RelayLink(i, sid, from, key, initiator = true)
                links[sid] = link
                val peer = Hub.addRelayPeer(link, from, true)
                main.post { pend.done(peer) }
            }
            T_DATA -> {
                val ctr = d.readLong()
                val ct = ByteArray(payload.size - 17)
                d.readFully(ct)
                links[sid]?.onData(ctr, ct)
            }
            T_CLOSE -> links.remove(sid)?.remoteClosed()
        }
    }

    /** Ouvre une liaison chiffrée avec le code donné (réponse attendue 15 s). */
    fun connect(code: String, done: (Peer?) -> Unit) {
        if (code == myCode) { main.post { done(null) }; return }
        val ok = brokers.indices.filter { brokers[it].connected }
        if (ok.isEmpty()) { main.post { done(null) }; return }
        Thread({
            try {
                val sid = rnd.nextLong()
                val kp = genKeyPair()
                pending[sid] = Pending(code, kp, done)
                val req = pack { writeByte(T_REQ); writeLong(sid); putStr(myCode); putBytes(kp.public.encoded) }
                // Envoyée sur les deux serveurs : le premier qui répond l'emporte
                var sent = 0
                for (i in ok) try { publishTo(i, code, req); sent++ } catch (_: Exception) {}
                if (sent == 0) { pending.remove(sid); main.post { done(null) } }
                else main.postDelayed({ pending.remove(sid)?.let { it.done(null) } }, 15_000)
            } catch (e: Exception) {
                main.post { done(null) }
            }
        }, "relay-connect").start()
    }

    internal fun sendClose(i: Int, code: String, sid: Long) {
        Thread({ try { publishTo(i, code, pack { writeByte(T_CLOSE); writeLong(sid) }) } catch (_: Exception) {} }, "relay-close").start()
    }

    // ------------------------------------------------------------------ chiffrement

    private fun genKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun derive(priv: PrivateKey, pubBytes: ByteArray, sid: Long): SecretKeySpec {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ka.generateSecret())
        md.update(ByteBuffer.allocate(8).putLong(sid).array())
        md.update("BS-CALLMagic-v3".toByteArray())
        return SecretKeySpec(md.digest(), "AES")
    }
}

/** Liaison chiffrée par Internet, présentée comme un flux ordinaire au reste de l'application. */
class RelayLink(
    private val broker: Int,
    val sid: Long,
    val peerCode: String,
    private val key: SecretKeySpec,
    initiator: Boolean
) {
    private val myDir = if (initiator) 0 else 1
    private val peerDir = 1 - myDir
    private var sendCtr = 0L
    private var recvCtr = 0L
    private val recvLock = Any()
    private val sidBytes = ByteBuffer.allocate(8).putLong(sid).array()
    @Volatile var closed = false
        private set

    val input = QueueInput()

    val output: OutputStream = object : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) { buf.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { buf.write(b, off, len) }
        override fun flush() {
            val data = buf.toByteArray()
            buf.reset()
            var off = 0
            while (off < data.size) {
                val n = minOf(48_000, data.size - off)
                sendChunk(data, off, n)
                off += n
            }
        }
        override fun close() { this@RelayLink.close() }
    }

    private fun nonce(dir: Int, ctr: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(dir).putLong(ctr).array()

    @Synchronized
    private fun sendChunk(d: ByteArray, off: Int, n: Int) {
        if (closed) throw IOException("liaison fermée")
        val ctr = sendCtr++
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(myDir, ctr)))
        c.updateAAD(sidBytes)
        val ct = c.doFinal(d, off, n)
        val payload = pack { writeByte(3); writeLong(sid); writeLong(ctr); write(ct) }
        Relay.publishTo(broker, peerCode, payload)
    }

    fun onData(ctr: Long, ct: ByteArray) {
        synchronized(recvLock) {
            if (closed || ctr < recvCtr) return          // doublon
            if (ctr > recvCtr) { fail(); return }        // message perdu : flux inutilisable
            val plain = try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(peerDir, ctr)))
                c.updateAAD(sidBytes)
                c.doFinal(ct)
            } catch (e: Exception) { fail(); return }   // altéré ou faux expéditeur
            recvCtr++
            input.feed(plain)
        }
    }

    private fun fail() { close() }

    fun close() {
        if (closed) return
        closed = true
        Relay.links.remove(sid)
        Relay.sendClose(broker, peerCode, sid)
        input.end()
    }

    fun remoteClosed() {
        closed = true
        input.end()
    }
}

/** Flux d'entrée alimenté par blocs. */
class QueueInput : InputStream() {
    private val q = LinkedBlockingQueue<ByteArray>()
    private var cur: ByteArray? = null
    private var pos = 0
    private val eof = ByteArray(0)

    fun feed(b: ByteArray) { if (b.isNotEmpty()) q.put(b) }
    fun end() { q.put(eof) }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val c = cur
            if (c != null && pos < c.size) {
                val k = minOf(len, c.size - pos)
                System.arraycopy(c, pos, b, off, k)
                pos += k
                return k
            }
            val n = q.take()
            if (n === eof) { q.put(eof); return -1 }
            cur = n; pos = 0
        }
    }

    override fun available(): Int = cur?.let { it.size - pos } ?: 0
}
