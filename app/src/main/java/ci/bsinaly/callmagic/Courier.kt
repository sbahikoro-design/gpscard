package ci.bsinaly.callmagic

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Une enveloppe du facteur : voyage de téléphone en téléphone jusqu'à son destinataire. */
class Envelope(
    val id: Long,
    val kind: Int,
    val dest: String,        // identifiant du destinataire, ou "*" pour tout le monde
    val destCode: String,    // code BS du destinataire (pour passer par Internet)
    val origin: String,
    val originCode: String,
    val created: Long,
    val expires: Long,
    var hops: Int,
    val payload: ByteArray
) {
    fun encode(): ByteArray = pack {
        writeLong(id); writeByte(kind); putStr(dest); putStr(destCode); putStr(origin); putStr(originCode)
        writeLong(created); writeLong(expires); writeInt(hops); putBytes(payload)
    }
    val idBytes: ByteArray get() = ByteBuffer.allocate(8).putLong(id).array()
    val expired: Boolean get() = System.currentTimeMillis() > expires

    companion object {
        const val MAIL = 1
        const val RECEIPT = 2
        const val SOS = 3
        const val SOS_REPLY = 4
        const val SMS_REQ = 5
        const val SMS_RES = 6
        const val GEO = 7
        const val SOS_CANCEL = 8

        fun decode(b: ByteArray): Envelope {
            val d = DataInputStream(ByteArrayInputStream(b))
            return Envelope(d.readLong(), d.readUnsignedByte(), d.getStr(), d.getStr(), d.getStr(), d.getStr(),
                d.readLong(), d.readLong(), d.readInt(), d.getBytes())
        }
    }
}

/**
 * Le facteur invisible : stocke, transporte et remet les enveloppes (« stocker, transporter, livrer »).
 * Chaque rencontre entre deux téléphones échange les enveloppes manquantes ; dès qu'un téléphone
 * a Internet, il les expédie directement ; un accusé de réception revient et efface les copies.
 */
object Courier {
    private const val MAX_BAG_BYTES = 8L * 1024 * 1024
    private const val MAX_ENVELOPE = 700_000
    private const val UPLINK_MAX = 200_000
    private const val DAY = 24L * 3600 * 1000

    private val lock = Any()
    private val bag = LinkedHashMap<Long, Envelope>()
    private val done = LinkedHashSet<Long>()          // déjà livrées : servent « d'antidote »
    private val broadcastSeen = LinkedHashSet<Long>()
    private val uplinked = HashSet<Long>()
    private val exec = Executors.newSingleThreadExecutor()
    private val saver = Executors.newSingleThreadExecutor()
    private val rnd = SecureRandom()
    private var app: Context? = null

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        load()
    }

    private fun file() = File(app!!.filesDir, "facteur.bin")

    // ------------------------------------------------------------------ statistiques

    fun carriedForOthers(): Int = synchronized(lock) { bag.values.count { !it.expired && it.origin != Hub.myId && it.dest != Hub.myId && it.kind != Envelope.RECEIPT } }
    fun mineInTransit(): Int = synchronized(lock) { bag.values.count { it.origin == Hub.myId && (it.kind == Envelope.MAIL || it.kind == Envelope.SMS_REQ || it.kind == Envelope.GEO) } }
    fun helped(): Int = app?.let { Prefs.raw(it).getInt("post_helped", 0) } ?: 0
    private fun incHelped() { app?.let { val sp = Prefs.raw(it); sp.edit().putInt("post_helped", sp.getInt("post_helped", 0) + 1).apply() } }

    // ------------------------------------------------------------------ création

    fun newId(): Long = rnd.nextLong()

    /** Crée une enveloppe, la garde dans le sac et la fait circuler. */
    fun create(kind: Int, dest: String, destCode: String, payload: ByteArray, ttlDays: Int = 7, id: Long = newId(), ttlMs: Long = 0L): Envelope {
        val now = System.currentTimeMillis()
        val env = Envelope(id, kind, dest, destCode, Hub.myId, Hub.myCode(), now, now + (if (ttlMs > 0) ttlMs else ttlDays * DAY), 0, payload)
        synchronized(lock) { bag[env.id] = env }
        if (kind == Envelope.SOS || dest == "*") synchronized(lock) { broadcastSeen.add(env.id) }
        saveSoon()
        exec.execute { spread(env, null) }
        return env
    }

    /** Lettre chiffrée (texte ou vocal) : [inner] est déjà construit par le Hub. */
    fun sendMail(c: Contact, key: Long, inner: ByteArray): Boolean {
        val pub = c.pubKey() ?: return false
        val tmp = ByteBuffer.allocate(8).putLong(key).array()
        // Signée par moi puis scellée pour le destinataire : ni lisible, ni falsifiable en route
        create(Envelope.MAIL, c.id, c.code, Identity.seal(pub, Identity.signedPack(inner, tmp), tmp), 7, key)
        return true
    }

    fun receipt(env: Envelope, what: Int) {
        if (env.origin.isEmpty() || env.origin == Hub.myId) return
        create(Envelope.RECEIPT, env.origin, env.originCode, pack { writeLong(env.id); writeByte(what) }, 7)
    }

    // ------------------------------------------------------------------ échanges entre téléphones

    fun summary(): ByteArray = synchronized(lock) {
        val held = bag.values.filter { !it.expired }.map { it.id }
        val dn = done.toList().takeLast(600)
        pack {
            writeInt(held.size); for (h in held) writeLong(h)
            writeInt(dn.size); for (x in dn) writeLong(x)
        }
    }

    fun onSummary(p: Peer, b: ByteArray) {
        val d = DataInputStream(ByteArrayInputStream(b))
        val nh = d.readInt(); if (nh < 0 || nh > 20000) return
        val held = HashSet<Long>(); repeat(nh) { held.add(d.readLong()) }
        val nd = d.readInt(); if (nd < 0 || nd > 20000) return
        val dn = ArrayList<Long>(); repeat(nd) { dn.add(d.readLong()) }
        p.knownEnvelopes.addAll(held); p.knownEnvelopes.addAll(dn)
        var changed = false
        synchronized(lock) {
            for (x in dn) {
                val e = bag[x]
                if (e != null && e.origin != Hub.myId) { bag.remove(x); changed = true }
                if (e != null && e.origin == Hub.myId && e.dest != "*") { bag.remove(x); changed = true }
                done.add(x)
            }
            trimDone()
        }
        if (changed) saveSoon()
        // Envoie ce qu'il n'a pas (d'abord ce qui lui est destiné), dans la limite de 3 Mo par rencontre
        val toSend = synchronized(lock) {
            bag.values.filter { !it.expired && it.id !in p.knownEnvelopes }
                .sortedBy { if (it.dest == p.deviceId) 0 else if (it.kind == Envelope.SOS) 1 else 2 }
        }
        var budget = 3_000_000
        exec.execute {
            for (e in toSend) {
                val bytes = e.encode()
                if (budget - bytes.size < 0) break
                budget -= bytes.size
                p.knownEnvelopes.add(e.id)
                if (!p.send(Proto.BAG_ITEM, bytes)) break
            }
        }
    }

    /** Une enveloppe arrive (d'un téléphone voisin, ou d'Internet si [from] est null). */
    fun onItem(from: Peer?, b: ByteArray) {
        val env = try { Envelope.decode(b) } catch (e: Exception) { return }
        from?.knownEnvelopes?.add(env.id)
        handle(env, from)
    }

    private fun handle(env: Envelope, from: Peer?) {
        if (env.expired || env.payload.size > MAX_ENVELOPE) return
        synchronized(lock) { if (env.id in done) return }
        env.hops++
        val me = Hub.myId
        if (env.kind == Envelope.RECEIPT) {
            val d = DataInputStream(ByteArrayInputStream(env.payload))
            val ref = d.readLong(); val what = d.readUnsignedByte()
            var carried = false
            synchronized(lock) {
                val e = bag.remove(ref)
                carried = e != null && e.origin != me
                done.add(ref); trimDone()
            }
            if (carried) incHelped()
            if (env.dest == me) {
                synchronized(lock) { done.add(env.id); bag.remove(env.id) }
                Hub.onReceipt(ref, what)
                saveSoon()
            } else if (store(env)) exec.execute { spread(env, from) }
            return
        }
        if (env.dest == me) {
            synchronized(lock) { done.add(env.id); bag.remove(env.id); trimDone() }
            saveSoon()
            deliver(env)
            if (env.kind == Envelope.MAIL || env.kind == Envelope.SMS_REQ || env.kind == Envelope.SMS_RES || env.kind == Envelope.SOS_REPLY)
                receipt(env, 1)
            return
        }
        if (env.dest == "*") {
            val first = synchronized(lock) { broadcastSeen.add(env.id) }
            if (!first) return
            if (env.origin != me) deliver(env)
            store(env)
            exec.execute { spread(env, from) }
            return
        }
        if (store(env)) exec.execute { spread(env, from) }
    }

    private fun store(env: Envelope): Boolean {
        val added = synchronized(lock) {
            if (bag.containsKey(env.id)) false
            else {
                bag[env.id] = env
                // Sac plein : on laisse tomber les plus anciennes enveloppes des autres
                var total = bag.values.sumOf { it.payload.size.toLong() + 200 }
                val it = bag.entries.iterator()
                while (total > MAX_BAG_BYTES && it.hasNext()) {
                    val e = it.next().value
                    if (e.origin != Hub.myId && e.id != env.id) { total -= e.payload.size + 200; it.remove() }
                }
                true
            }
        }
        if (added) saveSoon()
        return added
    }

    /** Fait circuler une enveloppe : directement au destinataire s'il est là, sinon à tout le monde. */
    private fun spread(env: Envelope, from: Peer?) {
        val bytes = env.encode()
        val direct = if (env.dest != "*") Hub.peerFor(env.dest)?.takeIf { it.transport != Proto.CHAIN } else null
        val targets = if (direct != null) listOf(direct) else Hub.readyPeers().distinctBy { it.deviceId }
        for (p in targets) {
            if (p === from || env.id in p.knownEnvelopes) continue
            if (from != null && p.deviceId == from.deviceId) continue
            p.knownEnvelopes.add(env.id)
            p.send(Proto.BAG_ITEM, bytes)
        }
        uplink(env, bytes)
    }

    /** Partage d'Internet : si ce téléphone est en ligne, l'enveloppe part tout de suite par Internet. */
    private fun uplink(env: Envelope, bytes: ByteArray) {
        val c = app ?: return
        if (!Relay.online || bytes.size > UPLINK_MAX) return
        if (env.origin != Hub.myId && !Prefs.shareInternet(c)) return
        synchronized(lock) { if (!uplinked.add(env.id)) return }
        if (env.dest == "*") {
            for (code in Contacts.codes().take(40)) Relay.sendEnvelope(code, bytes)
        } else if (env.destCode.isNotEmpty() && env.destCode != Hub.myCode()) {
            Relay.sendEnvelope(env.destCode, bytes)
        }
    }

    /** Internet vient de revenir : expédier ce qui attend dans le sac. */
    fun onInternet() {
        exec.execute {
            val list = synchronized(lock) { bag.values.filter { !it.expired }.toList() }
            for (e in list) uplink(e, e.encode())
        }
    }

    // ------------------------------------------------------------------ remise au destinataire

    /** Ouvre (si scellée) puis vérifie la signature de l'expéditeur annoncé ; null si faux. */
    private fun authentic(env: Envelope, sealed: Boolean): ByteArray? {
        val plain = if (sealed) Identity.open(env.payload, env.idBytes) ?: return null else env.payload
        return Identity.openSigned(plain, env.idBytes, env.origin)?.first
    }

    private fun deliver(env: Envelope) {
        try {
            when (env.kind) {
                Envelope.MAIL -> authentic(env, true)?.let { Hub.deliverMail(env, it) }
                Envelope.SMS_REQ -> authentic(env, true)?.let { SmsBridge.onRequest(env, it) }
                Envelope.SMS_RES -> authentic(env, true)?.let { SmsBridge.onResult(env, it) }
                Envelope.GEO -> authentic(env, env.dest != "*")?.let { Geo.onReceived(env, it) }
                Envelope.SOS -> Sos.onAlert(env)
                Envelope.SOS_REPLY -> Sos.onReply(env)
                Envelope.SOS_CANCEL -> Sos.onCancel(env)
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ persistance

    private fun trimDone() {
        while (done.size > 6000) done.remove(done.first())
        while (broadcastSeen.size > 2000) broadcastSeen.remove(broadcastSeen.first())
    }

    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun saveSoon() {
        if (!savePending.compareAndSet(false, true)) return
        saver.execute {
            Thread.sleep(800)
            savePending.set(false)
            val snap: List<Envelope>; val dn: List<Long>; val bs: List<Long>
            synchronized(lock) {
                bag.entries.removeAll { it.value.expired }
                snap = bag.values.toList(); dn = done.toList(); bs = broadcastSeen.toList()
            }
            try {
                val tmp = File(app!!.filesDir, "facteur.tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { o ->
                    o.writeInt(1)
                    o.writeInt(snap.size); for (e in snap) o.putBytes(e.encode())
                    o.writeInt(dn.size); for (x in dn) o.writeLong(x)
                    o.writeInt(bs.size); for (x in bs) o.writeLong(x)
                }
                tmp.renameTo(file())
            } catch (_: Exception) {}
        }
    }

    private fun load() {
        try {
            val f = file()
            if (!f.exists()) return
            DataInputStream(f.inputStream().buffered()).use { d ->
                d.readInt()
                repeat(d.readInt()) { val e = Envelope.decode(d.getBytes()); if (!e.expired) bag[e.id] = e }
                repeat(d.readInt()) { done.add(d.readLong()) }
                repeat(d.readInt()) { broadcastSeen.add(d.readLong()) }
            }
        } catch (_: Exception) {}
    }
}
