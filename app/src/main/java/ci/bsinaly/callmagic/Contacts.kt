package ci.bsinaly.callmagic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Une personne connue (rencontrée ou ajoutée), avec tous les moyens de la joindre. */
class Contact(
    var id: String,          // identifiant de l'appareil (vide si ajouté à la main et jamais rencontré)
    var name: String,
    var code: String,        // code BS (BSXXXXXXXX) — joignable par Internet partout
    var ip: String = "",     // adresse Internet directe (IPv4 publique ou IPv6)
    var port: Int = Hub.PORT,
    var lanIp: String = "",  // dernière adresse sur un réseau local
    var bt: String = "",     // adresse Bluetooth
    var lastSeen: Long = 0,
    var lastVia: String = "",
    var saved: Boolean = true
) {
    /** Clé de conversation privée. */
    val key: String get() = id.ifEmpty { "code:$code" }
}

/** Une manière de joindre une personne, avec son état. */
class Access(val via: String, val state: Int, val label: String) {
    companion object {
        const val CONNECTED = 0
        const val AVAILABLE = 1
        const val MAYBE = 2
    }
}

object Contacts {
    private val list = ArrayList<Contact>()
    private var app: Context? = null

    // Alphabet de Crockford : pas de I, L, O, U (évite les confusions à la lecture)
    private const val ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        try {
            val arr = JSONArray(c.getSharedPreferences("bscm", Context.MODE_PRIVATE).getString("contacts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Contact(
                    o.optString("id"), o.optString("name"), o.optString("code"), o.optString("ip"),
                    o.optInt("port", Hub.PORT), o.optString("lan"), o.optString("bt"), o.optLong("seen"), o.optString("via")
                ))
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val c = app ?: return
        val arr = JSONArray()
        synchronized(list) {
            for (k in list) arr.put(JSONObject().apply {
                put("id", k.id); put("name", k.name); put("code", k.code); put("ip", k.ip); put("port", k.port)
                put("lan", k.lanIp); put("bt", k.bt); put("seen", k.lastSeen); put("via", k.lastVia)
            })
        }
        c.getSharedPreferences("bscm", Context.MODE_PRIVATE).edit().putString("contacts", arr.toString()).apply()
    }

    fun all(): List<Contact> = synchronized(list) { list.toList() }
    fun codes(): List<String> = synchronized(list) { list.mapNotNull { it.code.ifEmpty { null } } }
    fun byId(id: String): Contact? = if (id.isEmpty()) null else synchronized(list) { list.firstOrNull { it.id == id } }
    fun byKey(key: String): Contact? = synchronized(list) { list.firstOrNull { it.key == key } }
    fun byCode(code: String): Contact? = synchronized(list) { list.firstOrNull { it.code == code } }

    /** Met à jour l'annuaire quand un correspondant se présente. */
    fun upsertFromPeer(p: Peer) {
        if (p.deviceId.isEmpty()) return
        val code = codeOf(p.deviceId)
        var isNew = false
        synchronized(list) {
            var c = list.firstOrNull { it.id == p.deviceId } ?: list.firstOrNull { it.code == code }
            if (c == null && p.transport == Proto.NET_IP) c = list.firstOrNull { it.id.isEmpty() && it.ip == p.address }
            if (c == null) { c = Contact(p.deviceId, p.name, code); list.add(c); isNew = true }
            c.id = p.deviceId; c.code = code; c.name = p.name
            c.lastSeen = System.currentTimeMillis(); c.lastVia = p.transport
            when (p.transport) {
                Proto.WIFI -> c.lanIp = p.address
                Proto.NET_IP -> if (p.outgoing) c.ip = p.address
                Proto.BT -> c.bt = p.address
            }
        }
        save()
        if (isNew) Relay.watch(code)
    }

    fun addManual(name: String, text: String): Contact? {
        val code = parseCode(text)
        val ips = parseIps(text)
        if (code == null && ips.isEmpty()) return null
        val c: Contact
        synchronized(list) {
            val found = (if (code != null) list.firstOrNull { it.code == code } else null)
                ?: ips.firstNotNullOfOrNull { ip -> list.firstOrNull { it.ip == ip.first } }
            c = found ?: Contact("", "", code ?: "").also { list.add(it) }
            if (name.isNotBlank()) c.name = name.trim()
            if (c.name.isBlank()) c.name = if (code != null) pretty(code) else ips[0].first
            if (code != null) c.code = code
            if (ips.isNotEmpty()) { c.ip = ips[0].first; c.port = ips[0].second }
        }
        save()
        if (c.code.isNotEmpty()) Relay.watch(c.code)
        return c
    }

    fun addFromDirectory(c: Contact) {
        c.saved = true
        synchronized(list) { if (!list.contains(c) && list.none { c.id.isNotEmpty() && it.id == c.id }) list.add(c) }
        save()
        Relay.watch(c.code)
    }

    fun rename(c: Contact, name: String) { if (name.isNotBlank()) { c.name = name.trim(); save() } }

    fun remove(c: Contact) { synchronized(list) { list.remove(c) }; save() }

    // ------------------------------------------------------------------ code BS

    fun codeOf(id: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        var bits = 0L
        for (i in 0 until 5) bits = (bits shl 8) or (h[i].toLong() and 0xff)
        val sb = StringBuilder("BS")
        for (i in 7 downTo 0) sb.append(ALPHA[((bits shr (i * 5)) and 31).toInt()])
        return sb.toString()
    }

    fun pretty(code: String): String =
        if (code.length == 10) "BS-${code.substring(2, 6)}-${code.substring(6)}" else code

    /** Trouve un code BS dans un texte (tolère espaces, tirets, minuscules, O/0 et I/1). */
    fun parseCode(text: String): String? {
        val m = Regex("BS[\\s-]*([0-9A-Za-z]{4})[\\s-]*([0-9A-Za-z]{4})", RegexOption.IGNORE_CASE).find(text) ?: return null
        val raw = (m.groupValues[1] + m.groupValues[2]).uppercase()
            .replace('O', '0').replace('I', '1').replace('L', '1').replace('U', 'V')
        return if (raw.all { it in ALPHA }) "BS$raw" else null
    }

    /** Trouve les adresses IPv4/IPv6 (avec port éventuel) dans un texte. */
    fun parseIps(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val portLine = Regex("port\\s*:?\\s*(\\d{2,5})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        val defPort = portLine ?: Hub.PORT
        // IPv6 entre crochets avec port : [2001:db8::1]:47800
        Regex("\\[([0-9a-fA-F:]+)]:(\\d{2,5})").findAll(text).forEach {
            out.add(it.groupValues[1] to (it.groupValues[2].toIntOrNull() ?: defPort))
        }
        // IPv4 avec port éventuel
        Regex("(?<![\\d.])((?:\\d{1,3}\\.){3}\\d{1,3})(?::(\\d{2,5}))?(?![\\d.])").findAll(text).forEach {
            val ip = it.groupValues[1]
            if (ip.split('.').all { p -> (p.toIntOrNull() ?: 999) <= 255 })
                out.add(ip to (it.groupValues[2].toIntOrNull() ?: defPort))
        }
        // IPv6 nue
        Regex("(?<![0-9a-fA-F:\\[])([0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7})(?![0-9a-fA-F:\\]])").findAll(text).forEach {
            val ip = it.groupValues[1]
            if (ip.count { ch -> ch == ':' } >= 2 && ip.any { ch -> ch.isLetterOrDigit() } && out.none { o -> o.first == ip })
                out.add(ip to defPort)
        }
        return out.distinctBy { it.first }
    }

    // ------------------------------------------------------------------ disponibilité

    /** Tous les moyens de joindre une personne, du meilleur au moins sûr. */
    fun access(c: Contact): List<Access> {
        val out = ArrayList<Access>()
        if (c.id.isNotEmpty()) for (p in Hub.peers) if (p.alive && p.deviceId == c.id)
            out.add(Access(p.transport, Access.CONNECTED, "🟢 Connecté · ${Proto.icon(p.transport)} ${p.transport}"))
        val connectedVia = out.map { it.via }.toSet()
        if (Proto.WIFI !in connectedVia && c.id.isNotEmpty() && Hub.wifiFound.values.any { it.id == c.id })
            out.add(Access(Proto.WIFI, Access.AVAILABLE, "📶 Wi-Fi — sur le même réseau"))
        if (Proto.NET_CODE !in connectedVia && c.code.isNotEmpty() && Relay.isOnline(c.code))
            out.add(Access(Proto.NET_CODE, Access.AVAILABLE, "🔒🌐 Internet — en ligne (code ${pretty(c.code)})"))
        if (Proto.NET_IP !in connectedVia && c.ip.isNotEmpty())
            out.add(Access(Proto.NET_IP, Access.MAYBE, "🌐 Internet direct — IP ${c.ip}"))
        if (Proto.BT !in connectedVia && c.bt.isNotEmpty() && Hub.btReady())
            out.add(Access(Proto.BT, Access.MAYBE, "🔵 Bluetooth — si à portée"))
        if (Proto.WIFI !in connectedVia && out.none { it.via == Proto.WIFI } && c.lanIp.isNotEmpty() && Hub.localIps().isNotEmpty())
            out.add(Access(Proto.WIFI, Access.MAYBE, "📶 Wi-Fi — dernière adresse ${c.lanIp}"))
        if (Proto.NET_CODE !in connectedVia && out.none { it.via == Proto.NET_CODE } && c.code.isNotEmpty() && Relay.online)
            out.add(Access(Proto.NET_CODE, Access.MAYBE, "🔒🌐 Internet (code) — semble hors ligne"))
        return out
    }

    fun bestState(c: Contact): Int = access(c).minOfOrNull { it.state } ?: 9

    /** Annuaire affiché : contacts enregistrés + appareils détectés sur le Wi-Fi + connectés. */
    fun directory(): List<Contact> {
        val out = ArrayList(all())
        for (f in Hub.wifiFound.values) if (out.none { it.id == f.id })
            out.add(Contact(f.id, f.name, codeOf(f.id), saved = false))
        for (p in Hub.readyPeers()) if (out.none { it.id == p.deviceId })
            out.add(Contact(p.deviceId, p.name, codeOf(p.deviceId), saved = false))
        return out.sortedWith(compareBy<Contact> { bestState(it) }.thenBy { it.name.lowercase() })
    }

    fun nameFor(id: String): String = byId(id)?.name ?: Hub.peers.firstOrNull { it.deviceId == id }?.name ?: "Correspondant"
}
