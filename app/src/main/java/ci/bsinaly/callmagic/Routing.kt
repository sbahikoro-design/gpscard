package ci.bsinaly.callmagic

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Chaîne humaine : chaque téléphone annonce qui il peut joindre et en combien de sauts.
 * Un appel ou un message vers quelqu'un hors de portée passe de proche en proche
 * par les téléphones intermédiaires (jusqu'à 6 sauts).
 */
object Routing {
    const val MAX_HOPS = 6

    class Route(val next: Peer, val hops: Int, val name: String, @Volatile var at: Long)

    private val table = ConcurrentHashMap<String, Route>()
    private val virtuals = ConcurrentHashMap<String, VirtualPeer>()

    /** Correspondant joint par la chaîne : se comporte comme une liaison ordinaire pour le reste de l'appli. */
    class VirtualPeer(id: String, name: String) : Peer(Proto.CHAIN, id, true, EmptyIn, NullOut, {}) {
        init { deviceId = id; this.name = name }
        override fun send(type: Int, p: ByteArray, off: Int, len: Int): Boolean =
            alive && sendRouted(deviceId, type, p, off, len)
    }

    private object EmptyIn : InputStream() { override fun read(): Int = -1 }
    private object NullOut : OutputStream() { override fun write(b: Int) {} }

    /** Liste « qui je peux joindre » envoyée régulièrement aux voisins directs. */
    fun advert(to: Peer? = null): ByteArray {
        val direct = Hub.readyPeers().filter { it.transport != Proto.CHAIN }.distinctBy { it.deviceId }
        val entries = ArrayList<Triple<String, String, Int>>()
        for (p in direct) if (p !== to && p.deviceId != to?.deviceId) entries.add(Triple(p.deviceId, p.name, 1))
        val now = System.currentTimeMillis()
        for ((id, r) in table) if (now - r.at < 30_000 && r.hops < MAX_HOPS && r.next !== to && id != to?.deviceId && direct.none { it.deviceId == id })
            entries.add(Triple(id, r.name, r.hops + 1))
        return pack { writeInt(entries.size); for ((id, n, h) in entries) { putStr(id); putStr(n); writeByte(h) } }
    }

    fun onAdvert(p: Peer, b: ByteArray) {
        val d = DataInputStream(ByteArrayInputStream(b))
        val n = d.readInt(); if (n < 0 || n > 2000) return
        val seen = HashSet<String>()
        val now = System.currentTimeMillis()
        repeat(n) {
            val id = d.getStr(); val name = d.getStr(); val h = d.readUnsignedByte()
            if (id == Hub.myId || id.isEmpty()) return@repeat
            seen.add(id)
            if (Hub.peers.any { it.alive && it.deviceId == id }) return@repeat   // déjà joignable en direct
            val hops = h + 1
            if (hops > MAX_HOPS) return@repeat
            val cur = table[id]
            if (cur == null || cur.next === p || hops < cur.hops || now - cur.at > 30_000 || !cur.next.alive)
                table[id] = Route(p, hops, name, now)
        }
        // Les routes que ce voisin n'annonce plus disparaissent
        table.entries.removeAll { it.value.next === p && it.key !in seen }
    }

    fun onPeerClosed(p: Peer) {
        table.entries.removeAll { it.value.next === p }
        for ((id, v) in virtuals) if (nextHop(id) == null && v.alive) { virtuals.remove(id); v.close("chaîne coupée", false) }
    }

    fun prune() {
        val now = System.currentTimeMillis()
        table.entries.removeAll { now - it.value.at > 30_000 || !it.value.next.alive }
        for (p in Hub.peers) if (p.alive && p.deviceId.isNotEmpty()) table.remove(p.deviceId)
        for ((id, v) in virtuals) if (nextHop(id) == null && v.alive) { virtuals.remove(id); v.close("chaîne coupée", false) }
    }

    fun route(id: String): Route? = table[id]?.takeIf { it.next.alive }

    /** Nombre de sauts pour joindre [id] par la chaîne (0 = injoignable). */
    fun hops(id: String): Int = route(id)?.hops ?: 0

    private fun nextHop(id: String): Peer? =
        Hub.peers.filter { it.alive && it.deviceId == id }.minByOrNull { Hub.rank(it.transport) } ?: route(id)?.next

    /** Liaison virtuelle vers [id] par la chaîne humaine (null si aucune route). */
    fun virtualFor(id: String, name: String? = null): VirtualPeer? {
        if (id.isEmpty() || route(id) == null) return null
        virtuals[id]?.let { if (it.alive) return it }
        val v = VirtualPeer(id, name ?: route(id)?.name ?: Contacts.nameFor(id))
        virtuals[id] = v
        return v
    }

    // ------------------------------------------------------------------ acheminement

    fun sendRouted(dest: String, type: Int, p: ByteArray, off: Int = 0, len: Int = p.size): Boolean {
        val nh = route(dest)?.next ?: return false
        val frame = pack { putStr(dest); putStr(Hub.myId); writeByte(MAX_HOPS + 2); writeByte(type); write(p, off, len) }
        return nh.send(Proto.ROUTED, frame)
    }

    fun onRouted(from: Peer, b: ByteArray) {
        val d = DataInputStream(ByteArrayInputStream(b))
        val dest = d.getStr(); val src = d.getStr()
        val ttlPos = 8 + dest.toByteArray().size + src.toByteArray().size
        val ttl = d.readUnsignedByte(); val type = d.readUnsignedByte()
        if (dest == Hub.myId) {
            val payload = b.copyOfRange(ttlPos + 2, b.size)
            // Le correspondant lointain devient une liaison virtuelle : tout le reste fonctionne pareil
            val v = virtuals[src]?.takeIf { it.alive } ?: run {
                if (route(src) == null) table[src] = Route(from, Routing.MAX_HOPS, Contacts.nameFor(src), System.currentTimeMillis())
                virtualFor(src) ?: return
            }
            Hub.onFrame(v, type, payload)
            return
        }
        if (ttl <= 1) return
        val nh = nextHop(dest) ?: return
        if (nh === from) return
        b[ttlPos] = (ttl - 1).toByte()
        nh.send(Proto.ROUTED, b)
    }

    /** Joignables uniquement par la chaîne : (id, nom, sauts). */
    fun reachable(): List<Triple<String, String, Int>> =
        table.entries.filter { it.value.next.alive }.map { Triple(it.key, it.value.name, it.value.hops) }
}
