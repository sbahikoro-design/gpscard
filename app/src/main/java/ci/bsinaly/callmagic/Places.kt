package ci.bsinaly.callmagic

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.math.roundToInt

// ====================================================================== position GPS

object Loc {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var last: Location? = null

    fun has(c: Context) = Build.VERSION.SDK_INT < 23 ||
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(c: Context): Location? {
        if (!has(c)) return last
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best = last
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    /** Position actuelle (au plus [timeoutMs] d'attente ; sinon la dernière connue). */
    @SuppressLint("MissingPermission")
    fun current(c: Context, timeoutMs: Long = 20_000, cb: (Location?) -> Unit) {
        val known = lastKnown(c)
        if (!has(c)) { cb(known); return }
        if (known != null && System.currentTimeMillis() - known.time < 60_000 && known.accuracy < 80) { cb(known); return }
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var finished = false
        lateinit var listener: LocationListener
        val finish = { l: Location? ->
            if (!finished) {
                finished = true
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
                if (l != null) last = l
                cb(l ?: known)
            }
        }
        listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { if (l.accuracy < 100 || l.provider == LocationManager.GPS_PROVIDER) finish(l) }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
            try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) } catch (_: Exception) {}
        main.postDelayed({ finish(null) }, timeoutMs)
    }

    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val r = FloatArray(2); Location.distanceBetween(aLat, aLon, bLat, bLon, r); return r[0]
    }

    fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val r = FloatArray(2); Location.distanceBetween(aLat, aLon, bLat, bLon, r); return (r[1] + 360) % 360
    }

    fun fmtDistance(m: Float): String = if (m < 1000) "${m.roundToInt()} m" else String.format("%.1f km", m / 1000)

    fun direction(deg: Float): String = arrayOf("au nord", "au nord-est", "à l'est", "au sud-est", "au sud", "au sud-ouest", "à l'ouest", "au nord-ouest")[(((deg + 22.5f) % 360) / 45).toInt()]

    fun mapIntent(lat: Double, lon: Double, label: String) =
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon(${android.net.Uri.encode(label)})"))
}

// ====================================================================== SOS « tam-tam »

object Sos {
    val TYPES = listOf("🚗 Accident", "🤒 Malade / blessé", "🔥 Feu", "⚠ Agression / danger", "🆘 Autre urgence")
    private const val VALID_MS = 2 * 3600_000L          // une alerte vit 2 h, pas plus
    private const val RING_MAX_AGE = 20 * 60_000L        // on ne fait sonner que les alertes de moins de 20 min

    /** Réception des alertes : 0 = sirène pour tous, 1 = sirène pour mes contacts seulement, 2 = notification simple. */
    const val MODE_ALL = 0
    const val MODE_CONTACTS = 1
    const val MODE_QUIET = 2
    fun mode(c: Context) = Prefs.raw(c).getInt("sos_mode", MODE_CONTACTS)
    fun setMode(c: Context, m: Int) = Prefs.raw(c).edit().putInt("sos_mode", m).apply()

    class Alert(val id: Long, val origin: String, val originCode: String, val name: String, val type: String, val note: String,
                val lat: Double, val lon: Double, val acc: Float, val time: Long, val known: Boolean,
                var cancelled: Boolean = false, val replies: MutableList<String> = mutableListOf())

    val alerts = ArrayList<Alert>()         // fil principal

    /** Mon alerte en cours (gardée même si le téléphone redémarre). */
    var myActive: Long
        get() {
            val sp = Prefs.raw(Hub.app)
            val at = sp.getLong("sos_mine_at", 0L)
            return if (System.currentTimeMillis() - at < VALID_MS) sp.getLong("sos_mine", 0L) else 0L
        }
        set(v) { Prefs.raw(Hub.app).edit().putLong("sos_mine", v).putLong("sos_mine_at", if (v == 0L) 0L else System.currentTimeMillis()).apply() }

    fun myActiveSince(): Long = Prefs.raw(Hub.app).getLong("sos_mine_at", 0L)

    private fun ctx(id: Long) = java.nio.ByteBuffer.allocate(8).putLong(id).array()

    /** Envoie l'alerte (signée) : elle saute de téléphone en téléphone, part par Internet et par SMS. */
    fun send(c: Context, type: String, note: String, done: (Boolean) -> Unit) {
        Loc.current(c, 12_000) { loc ->
            val numbers = Prefs.sosNumbers(c)
            val id = Courier.newId()
            val body = pack {
                putStr(Hub.myName()); putStr(type); putStr(note)
                writeDouble(loc?.latitude ?: Double.NaN); writeDouble(loc?.longitude ?: Double.NaN)
                writeFloat(loc?.accuracy ?: 0f); writeLong(System.currentTimeMillis()); putStr(numbers)
            }
            Courier.create(Envelope.SOS, "*", "", Identity.signedPack(body, ctx(id)), 1, id, ttlMs = VALID_MS)
            myActive = id
            val text = smsText(Hub.myName(), type, note, loc?.latitude, loc?.longitude)
            var sent = sendSms(c, numbers, text)
            // Édition standard : on ouvre l'application SMS du téléphone avec le message déjà écrit
            if (sent == 0 && numbers.isNotBlank()) sent = openSmsComposer(c, numbers, text)
            Hub.addSystem("🆘 Alerte envoyée à tout le réseau" + (if (sent > 0) " et par SMS à $sent numéro(s)" else "") +
                (if (loc == null) " (position inconnue)" else "") + ". Elle s'arrêtera seule dans 2 h, ou quand vous l'annulez.")
            done(true)
        }
    }

    /** Annule mon alerte : tous les téléphones arrêtent la sirène. Fonctionne aussi après un redémarrage. */
    fun cancel(c: Context) {
        val id = myActive
        if (id == 0L) return
        val cid = Courier.newId()
        Courier.create(Envelope.SOS_CANCEL, "*", "", Identity.signedPack(pack { writeLong(id); putStr(Hub.myName()) }, ctx(cid)), 1, cid, ttlMs = VALID_MS)
        myActive = 0L
        Hub.addSystem("✅ Alerte annulée : tout le monde est prévenu.")
    }

    fun smsText(name: String, type: String, note: String, lat: Double?, lon: Double?): String {
        val t = type.replace(Regex("^[^A-Za-zÀ-ÿ]+"), "")
        return "SOS $name : $t." + (if (note.isNotBlank()) " $note." else "") +
            (if (lat != null && !lat.isNaN() && lon != null) " Position : https://maps.google.com/?q=$lat,$lon" else "") + " (BS CALLMagic)"
    }

    fun sendSms(c: Context, numbers: String, text: String): Int {
        if (!SmsBridge.canSend(c)) return 0
        var n = 0
        for (num in numbers.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.length >= 8 }.take(5)) {
            try {
                @Suppress("DEPRECATION") val sm = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null); n++
            } catch (_: Exception) {}
        }
        return n
    }

    /** Ouvre l'application SMS avec les numéros et le texte (il reste à toucher « Envoyer »). */
    fun openSmsComposer(c: Context, numbers: String, text: String): Int {
        val list = numbers.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.length >= 8 }.take(5)
        if (list.isEmpty()) return 0
        return try {
            val i = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + list.joinToString(";")))
                .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i); list.size
        } catch (_: Exception) { 0 }
    }

    private fun acked(id: Long): Boolean = Prefs.raw(Hub.app).getString("sos_acked", "")!!.split(',').contains(id.toString())
    fun ack(id: Long) {
        val sp = Prefs.raw(Hub.app)
        val list = sp.getString("sos_acked", "")!!.split(',').filter { it.isNotEmpty() }.takeLast(50) + id.toString()
        sp.edit().putString("sos_acked", list.joinToString(",")).apply()
    }

    private val lastRingFrom = HashMap<String, Long>()

    fun onAlert(env: Envelope) {
        // Une alerte non signée, ou signée par un autre que l'expéditeur annoncé, est une fausse alerte
        val body = Identity.openSigned(env.payload, ctx(env.id), env.origin)?.first ?: return
        val d = DataInputStream(ByteArrayInputStream(body))
        val known = Contacts.byId(env.origin) != null
        val a = Alert(env.id, env.origin, env.originCode, d.getStr(), d.getStr(), d.getStr(), d.readDouble(), d.readDouble(),
            d.readFloat(), d.readLong(), known)
        val numbers = d.getStr()
        val now = System.currentTimeMillis()
        if (now - a.time > VALID_MS || a.time - now > 10 * 60_000) return          // trop vieille (ou date absurde)
        Hub.main.post {
            if (alerts.any { it.id == a.id }) return@post
            alerts.add(0, a)
            Hub.addSystem("🆘 ALERTE de ${a.name} : ${a.type}" + if (!a.known) " (personne inconnue)" else "")
            val c = Hub.app
            val m = mode(c)
            val loud = !acked(a.id) && now - a.time < RING_MAX_AGE &&
                (m == MODE_ALL || (m == MODE_CONTACTS && a.known)) &&
                now - (lastRingFrom[a.origin] ?: 0L) > 10 * 60_000                   // pas plus d'une sirène / 10 min / personne
            if (loud) lastRingFrom[a.origin] = now
            HubService.instance?.onSos(a, loud)
        }
        // Un pont SMS relaie l'alerte vers les numéros d'urgence de la personne
        val c = Hub.app
        if (Prefs.smsBridge(c) && numbers.isNotBlank())
            sendSms(c, numbers, "Relayé par ${Hub.myName()} — " + smsText(a.name, a.type, a.note, a.lat, a.lon))
    }

    fun onCancel(env: Envelope) {
        val body = Identity.openSigned(env.payload, ctx(env.id), env.origin)?.first ?: return
        val d = DataInputStream(ByteArrayInputStream(body))
        val ref = d.readLong(); val name = d.getStr()
        Hub.main.post {
            val a = alerts.firstOrNull { it.id == ref }
            // Seul l'auteur de l'alerte peut l'annuler
            if (a != null && a.origin != env.origin) return@post
            a?.cancelled = true
            ack(ref)
            Hub.addSystem("✅ $name a annulé son alerte.")
            HubService.instance?.onSosCancelled(ref)
        }
    }

    fun reply(a: Alert, text: String) {
        val pub = Contacts.byId(a.origin)?.pubKey()
        val id = Courier.newId()
        val ctx = java.nio.ByteBuffer.allocate(8).putLong(id).array()
        val inner = Identity.signedPack(pack { writeLong(a.id); putStr(Hub.myName()); putStr(text) }, ctx)
        val payload = if (pub != null) Identity.seal(pub, inner, ctx) else inner
        Courier.create(Envelope.SOS_REPLY, a.origin, a.originCode, pack { writeBoolean(pub != null); putBytes(payload) }, 1, id)
    }

    fun onReply(env: Envelope) {
        val d = DataInputStream(ByteArrayInputStream(env.payload))
        val sealed = d.readBoolean(); val body = d.getBytes()
        val signed = if (sealed) Identity.open(body, env.idBytes) ?: return else body
        val inner = Identity.openSigned(signed, env.idBytes, env.origin)?.first ?: return
        val r = DataInputStream(ByteArrayInputStream(inner))
        r.readLong(); val who = r.getStr(); val text = r.getStr()
        Hub.addSystem("🏃 $who : $text")
        Hub.main.post { HubService.instance?.notifySimple("🆘 Réponse à votre alerte", "$who : $text") }
    }
}

// ====================================================================== messages posés

object Geo {
    class Drop(val id: Long, val fromId: String, val fromCode: String, val fromName: String, val toName: String,
               val lat: Double, val lon: Double, val radius: Int, val text: String, val created: Long,
               var opened: Boolean, val mine: Boolean, val forAll: Boolean)

    val drops = ArrayList<Drop>()
    private var loaded = false

    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val arr = JSONArray(Prefs.raw(c).getString("geo_drops", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                drops.add(Drop(o.getLong("id"), o.optString("fi"), o.optString("fc"), o.optString("fn"), o.optString("tn"),
                    o.getDouble("la"), o.getDouble("lo"), o.optInt("r", 50), o.optString("t"), o.optLong("c"),
                    o.optBoolean("op"), o.optBoolean("mi"), o.optBoolean("all")))
            }
        } catch (_: Exception) {}
    }

    private fun save(c: Context) {
        val arr = JSONArray()
        synchronized(drops) {
            for (d in drops.takeLast(300)) arr.put(JSONObject().apply {
                put("id", d.id); put("fi", d.fromId); put("fc", d.fromCode); put("fn", d.fromName); put("tn", d.toName)
                put("la", d.lat); put("lo", d.lon); put("r", d.radius); put("t", d.text); put("c", d.created)
                put("op", d.opened); put("mi", d.mine); put("all", d.forAll)
            })
        }
        Prefs.raw(c).edit().putString("geo_drops", arr.toString()).apply()
    }

    fun all(c: Context): List<Drop> { load(c); return synchronized(drops) { drops.toList() } }

    /** Dépose un message à l'endroit où l'on se trouve. [to] = null : pour tout le monde. */
    fun drop(c: Context, to: Contact?, radius: Int, text: String, done: (String?) -> Unit) {
        load(c)
        val pub = to?.pubKey()
        if (to != null && pub == null) { done("Il faut avoir rencontré ${to.name} au moins une fois (pour la clé de chiffrement)."); return }
        Loc.current(c, 25_000) { loc ->
            if (loc == null) { done("Position introuvable : activez la localisation (GPS) et réessayez."); return@current }
            val inner = pack { putStr(Hub.myName()); writeDouble(loc.latitude); writeDouble(loc.longitude); writeInt(radius); putStr(text) }
            val id = Courier.newId()
            val ctx = java.nio.ByteBuffer.allocate(8).putLong(id).array()
            val signed = Identity.signedPack(inner, ctx)
            val payload = if (pub != null) Identity.seal(pub, signed, ctx) else signed
            Courier.create(Envelope.GEO, to?.id ?: "*", to?.code ?: "", payload, 30, id)
            synchronized(drops) {
                drops.add(Drop(id, Hub.myId, Hub.myCode(), Hub.myName(), to?.name ?: "tout le monde", loc.latitude, loc.longitude,
                    radius, text, System.currentTimeMillis(), false, true, to == null))
            }
            save(c)
            done(null)
        }
    }

    fun onReceived(env: Envelope, plain: ByteArray) {
        val c = Hub.app
        load(c)
        val d = DataInputStream(ByteArrayInputStream(plain))
        val name = d.getStr(); val lat = d.readDouble(); val lon = d.readDouble(); val r = d.readInt(); val text = d.getStr()
        synchronized(drops) {
            if (drops.any { it.id == env.id } || env.origin == Hub.myId) return
            drops.add(Drop(env.id, env.origin, env.originCode, name, if (env.dest == "*") "tout le monde" else "moi",
                lat, lon, r, text, env.created, false, false, env.dest == "*"))
        }
        save(c)
        Hub.main.post {
            val here = Loc.last
            val hint = if (here != null) " à ${Loc.fmtDistance(Loc.distance(here.latitude, here.longitude, lat, lon))}" else ""
            HubService.instance?.notifySimple("📍 Un message vous attend quelque part", "$name a laissé un message$hint. Il s'ouvrira quand vous serez sur place.")
            Hub.changed()
            Loc.last?.let { check(c, it) }
        }
    }

    /** Appelé à chaque nouvelle position : ouvre les messages dont on a atteint le lieu. */
    fun check(c: Context, loc: Location) {
        load(c)
        val toOpen = synchronized(drops) {
            drops.filter { !it.mine && !it.opened && Loc.distance(loc.latitude, loc.longitude, it.lat, it.lon) <= it.radius + loc.accuracy.coerceAtMost(60f) }
        }
        if (toOpen.isEmpty()) return
        for (dr in toOpen) {
            dr.opened = true
            Hub.deliverPlaced(dr)
            if (!dr.forAll) Courier.create(Envelope.RECEIPT, dr.fromId, dr.fromCode, pack { writeLong(dr.id); writeByte(2) }, 7)
        }
        save(c)
    }

    fun markOpenedByRecipient(c: Context, id: Long) {
        load(c)
        synchronized(drops) { drops.firstOrNull { it.id == id && it.mine }?.opened = true }
        save(c)
    }
}

// ====================================================================== pont SMS

object SmsBridge {
    /** Seule l'édition « Pont SMS » peut envoyer des SMS elle-même. */
    fun canSend(c: Context) = BuildConfig.SMS && (Build.VERSION.SDK_INT < 23 ||
        c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)

    fun active(c: Context) = Prefs.smsBridge(c) && canSend(c)

    fun digits(n: String) = n.filter { it.isDigit() }.takeLast(10)

    private fun usedToday(c: Context): Int {
        val sp = Prefs.raw(c)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        return if (sp.getString("sms_day", "") == day) sp.getInt("sms_used", 0) else 0
    }

    private fun use(c: Context) {
        val sp = Prefs.raw(c)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        sp.edit().putString("sms_day", day).putInt("sms_used", usedToday(c) + 1).apply()
    }

    fun remainingToday(c: Context) = (Prefs.smsQuota(c) - usedToday(c)).coerceAtLeast(0)

    /** Ponts connus (contacts qui ont annoncé être pont SMS). */
    fun bridges(): List<Contact> = Contacts.all().filter { it.caps and Proto.CAP_SMS != 0 && it.pubKey() != null }

    /** Demande à un pont d'envoyer un SMS. */
    fun request(c: Context, bridge: Contact, number: String, text: String): Boolean {
        val pub = bridge.pubKey() ?: return false
        val id = Courier.newId()
        val inner = pack { putStr(number); putStr(text); putStr(Hub.myName()); putBytes(Identity.publicKey) }
        val ctx = java.nio.ByteBuffer.allocate(8).putLong(id).array()
        Courier.create(Envelope.SMS_REQ, bridge.id, bridge.code, Identity.seal(pub, Identity.signedPack(inner, ctx), ctx), 3, id)
        Prefs.setLastBridge(c, bridge.id)
        Hub.addSmsOutgoing(id, number, text, bridge.name)
        return true
    }

    /** Côté pont : envoie le SMS demandé. */
    fun onRequest(env: Envelope, plain: ByteArray) {
        val c = Hub.app
        val d = DataInputStream(ByteArrayInputStream(plain))
        val number = d.getStr(); val text = d.getStr(); val who = d.getStr()
        val pub = if (d.available() > 4) d.getBytes(1000) else Contacts.byId(env.origin)?.pubKey()
        val status = when {
            !active(c) -> "refusé : le pont est désactivé"
            remainingToday(c) <= 0 -> "refusé : quota du jour atteint"
            else -> try {
                @Suppress("DEPRECATION") val sm = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sm.sendMultipartTextMessage(number, null, sm.divideMessage("[$who via BS CALLMagic] $text"), null, null)
                use(c)
                rememberReply(c, number, env.origin, env.originCode, who, pub)
                "envoyé"
            } catch (e: Exception) { "échec : ${e.message}" }
        }
        reply(env.origin, env.originCode, pub, number, if (status == "envoyé") "" else status, kind = 0, ref = env.id)
        Hub.main.post {
            HubService.instance?.notifySimple("🌉 Pont SMS", "SMS pour $who vers $number : $status (reste ${remainingToday(c)} aujourd'hui)")
        }
    }

    private fun rememberReply(c: Context, number: String, id: String, code: String, name: String, pub: ByteArray?) {
        val sp = Prefs.raw(c)
        val o = try { JSONObject(sp.getString("sms_map", "{}")) } catch (_: Exception) { JSONObject() }
        o.put(digits(number), JSONObject().put("id", id).put("code", code).put("name", name).put("t", System.currentTimeMillis())
            .put("pub", if (pub != null) android.util.Base64.encodeToString(pub, android.util.Base64.NO_WRAP) else ""))
        sp.edit().putString("sms_map", o.toString()).apply()
    }

    /** Côté pont : un SMS arrive ; s'il répond à une demande récente, il repart vers le demandeur. */
    fun onSmsReceived(c: Context, number: String, body: String) {
        if (!Prefs.smsBridge(c)) return
        val o = try { JSONObject(Prefs.raw(c).getString("sms_map", "{}")) } catch (_: Exception) { return }
        val m = o.optJSONObject(digits(number)) ?: return
        if (System.currentTimeMillis() - m.optLong("t") > 72 * 3600_000L) return
        Hub.init(c); Identity.init(c); Courier.init(c)
        val pub = m.optString("pub").takeIf { it.isNotEmpty() }?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        reply(m.getString("id"), m.optString("code"), pub, number, body, kind = 1, ref = 0L)
        HubService.instance?.notifySimple("🌉 Pont SMS", "Réponse de $number transmise à ${m.optString("name")}")
    }

    private fun reply(toId: String, code: String, pubIn: ByteArray?, number: String, text: String, kind: Int, ref: Long) {
        val ct = Contacts.byId(toId)
        val pub = pubIn ?: ct?.pubKey() ?: return
        val id = Courier.newId()
        val inner = pack { writeByte(kind); writeLong(ref); putStr(number); putStr(text); putStr(Hub.myName()) }
        val ctx = java.nio.ByteBuffer.allocate(8).putLong(id).array()
        Courier.create(Envelope.SMS_RES, toId, code.ifEmpty { ct?.code ?: "" }, Identity.seal(pub, Identity.signedPack(inner, ctx), ctx), 3, id)
    }

    /** Côté demandeur : résultat d'envoi ou réponse SMS. */
    fun onResult(env: Envelope, plain: ByteArray) {
        val d = DataInputStream(ByteArrayInputStream(plain))
        val kind = d.readUnsignedByte(); val ref = d.readLong(); val number = d.getStr(); val text = d.getStr(); val bridge = d.getStr()
        if (kind == 0) Hub.onSmsStatus(ref, number, text, bridge)
        else Hub.addSmsIncoming(env.id, number, text, bridge)
    }
}

/** Réception des SMS (pour le pont). */
class SmsReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != "android.provider.Telephony.SMS_RECEIVED") return
        val msgs = try { android.provider.Telephony.Sms.Intents.getMessagesFromIntent(i) } catch (_: Exception) { null } ?: return
        val byFrom = LinkedHashMap<String, StringBuilder>()
        for (m in msgs) {
            val from = m.displayOriginatingAddress ?: continue
            byFrom.getOrPut(from) { StringBuilder() }.append(m.displayMessageBody ?: "")
        }
        for ((from, body) in byFrom) SmsBridge.onSmsReceived(c, from, body.toString())
    }
}

@Suppress("unused")
private fun notificationManager(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
