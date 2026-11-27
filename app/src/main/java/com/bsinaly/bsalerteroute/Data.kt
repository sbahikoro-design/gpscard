package com.bsinaly.bsalerteroute

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Types d'obstacles : libellé écran, mot prononcé par la voix, couleur, rayon conseillé (m). */
data class OType(val code: Int, val label: String, val voice: String, val color: Int, val radius: Int)

object Types {
    val ALL = listOf(
        OType(0, "Dos d'âne", "dos d'âne", 0xFFFFD000.toInt(), 120),
        OType(1, "Nid-de-poule", "nid de poule", 0xFFFF8A00.toInt(), 120),
        OType(2, "Virage dangereux", "virage dangereux", 0xFFE10600.toInt(), 200),
        OType(3, "Pont / radier", "pont étroit", 0xFF3DA5FF.toInt(), 180),
        OType(4, "Village / école", "traversée de village", 0xFF4CD08A.toInt(), 250),
        OType(5, "Contrôle routier", "contrôle routier", 0xFFB46CFF.toInt(), 300),
        OType(6, "Animaux", "passage d'animaux", 0xFFC98B4B.toInt(), 200),
        OType(7, "Route dégradée", "route dégradée", 0xFFFF5A8A.toInt(), 150),
        OType(8, "Autre danger", "danger", 0xFFE8E8E8.toInt(), 150),
    )
    fun of(code: Int): OType = ALL.firstOrNull { it.code == code } ?: ALL.last()
}

/** Un obstacle enregistré sur le trajet. radius = 0 → rayon du type. */
data class Obstacle(
    val id: Long,
    var lat: Double,
    var lon: Double,
    var type: Int,
    var radius: Int,
    val created: Long,
    var acc: Float,
    var heading: Float,      // sens de circulation au moment du pointage (-1 = inconnu)
    var confirms: Int,       // nombre de fois pointé au même endroit
    var alerts: Int,         // nombre d'alertes déclenchées
    var muted: Boolean,
) {
    fun effRadius(): Int = if (radius > 0) radius else Types.of(type).radius
    fun loc(): Location = Location("o").also { it.latitude = lat; it.longitude = lon }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("lat", lat); put("lon", lon); put("type", type); put("radius", radius)
        put("created", created); put("acc", acc.toDouble()); put("heading", heading.toDouble())
        put("confirms", confirms); put("alerts", alerts); put("muted", muted)
    }

    companion object {
        fun from(o: JSONObject) = Obstacle(
            o.optLong("id", System.nanoTime()), o.getDouble("lat"), o.getDouble("lon"),
            o.optInt("type", 8), o.optInt("radius", 0), o.optLong("created", System.currentTimeMillis()),
            o.optDouble("acc", 0.0).toFloat(), o.optDouble("heading", -1.0).toFloat(),
            o.optInt("confirms", 1), o.optInt("alerts", 0), o.optBoolean("muted", false)
        )
    }
}

/** Stockage local (fichier JSON), partagé entre l'écran et le service. */
object Store {
    private val list = mutableListOf<Obstacle>()
    private var loaded = false
    @Volatile var version = 0
        private set

    @Synchronized fun all(ctx: Context): List<Obstacle> { load(ctx); return list.toList() }

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) list.add(Obstacle.from(arr.getJSONObject(i)))
        } catch (_: Exception) { }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "obstacles.json")

    @Synchronized fun save(ctx: Context) {
        load(ctx)
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        tmp.writeText(arr.toString()); tmp.renameTo(f)
        version++
    }

    /**
     * Ajoute un obstacle. Intelligent : si un obstacle du même type existe déjà à moins de 25 m,
     * on le confirme et on affine sa position (moyenne pondérée) au lieu de créer un doublon.
     * Retourne (obstacle, fusionné ?).
     */
    @Synchronized fun add(ctx: Context, loc: Location, type: Int): Pair<Obstacle, Boolean> {
        load(ctx)
        val near = list.filter { it.type == type }.minByOrNull { loc.distanceTo(it.loc()) }
        if (near != null && loc.distanceTo(near.loc()) < 25f) {
            val n = near.confirms.toDouble()
            near.lat = (near.lat * n + loc.latitude) / (n + 1)
            near.lon = (near.lon * n + loc.longitude) / (n + 1)
            near.confirms++
            near.acc = minOf(near.acc, loc.accuracy)
            save(ctx)
            return near to true
        }
        val o = Obstacle(
            System.currentTimeMillis(), loc.latitude, loc.longitude, type, 0,
            System.currentTimeMillis(), loc.accuracy,
            if (loc.hasBearing() && loc.speed > 2f) loc.bearing else -1f, 1, 0, false
        )
        list.add(o); save(ctx)
        return o to false
    }

    @Synchronized fun update(ctx: Context, id: Long, f: (Obstacle) -> Unit) {
        load(ctx); list.firstOrNull { it.id == id }?.let(f); save(ctx)
    }

    @Synchronized fun remove(ctx: Context, id: Long) { load(ctx); list.removeAll { it.id == id }; save(ctx) }

    @Synchronized fun clear(ctx: Context) { load(ctx); list.clear(); save(ctx) }

    @Synchronized fun exportJson(ctx: Context): String {
        load(ctx)
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        return JSONObject().put("app", "BS ALERTE ROUTE").put("version", 1).put("obstacles", arr).toString(2)
    }

    /** Importe un fichier exporté (par ex. d'un autre chauffeur). Retourne le nombre ajouté. */
    @Synchronized fun importJson(ctx: Context, text: String): Int {
        load(ctx)
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("obstacles")
        var added = 0
        for (i in 0 until arr.length()) {
            val o = Obstacle.from(arr.getJSONObject(i))
            val dup = list.any { it.type == o.type && it.loc().distanceTo(o.loc()) < 25f }
            if (!dup) { list.add(o.copy(id = System.nanoTime())); added++ }
        }
        save(ctx)
        return added
    }
}

/** Réglages. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("bsar", Context.MODE_PRIVATE)
    var voice: Boolean
        get() = p.getBoolean("voice", true)
        set(v) = p.edit().putBoolean("voice", v).apply()
    var vibrate: Boolean
        get() = p.getBoolean("vib", true)
        set(v) = p.edit().putBoolean("vib", v).apply()
    var volumeKey: Boolean
        get() = p.getBoolean("volkey", true)
        set(v) = p.edit().putBoolean("volkey", v).apply()
    var keepScreen: Boolean
        get() = p.getBoolean("screen", true)
        set(v) = p.edit().putBoolean("screen", v).apply()
    var smartDir: Boolean
        get() = p.getBoolean("dir", true)
        set(v) = p.edit().putBoolean("dir", v).apply()
    /** Anticipation en secondes : à grande vitesse l'alerte arrive plus tôt. */
    var anticipation: Int
        get() = p.getInt("anticip", 8)
        set(v) = p.edit().putInt("anticip", v).apply()
    /** Volume du bip 10..100. */
    var beepVolume: Int
        get() = p.getInt("beepvol", 90)
        set(v) = p.edit().putInt("beepvol", v).apply()
    var lastType: Int
        get() = p.getInt("lasttype", 0)
        set(v) = p.edit().putInt("lasttype", v).apply()
}

/** État partagé en direct entre le service GPS et l'écran. */
object Live {
    @Volatile var running = false
    @Volatile var last: Location? = null
    @Volatile var nearestId: Long = -1
    @Volatile var nearestDist: Float = -1f
    @Volatile var alertText: String = ""
    @Volatile var alertAt: Long = 0
    @Volatile var gpsFixAt: Long = 0
    val justMarked: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
}

fun fmtDist(m: Float): String = when {
    m < 0 -> "—"
    m < 1000 -> "${m.toInt()} m"
    else -> String.format(java.util.Locale.FRANCE, "%.1f km", m / 1000f)
}
