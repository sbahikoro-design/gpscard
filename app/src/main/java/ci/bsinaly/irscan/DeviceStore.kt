package ci.bsinaly.irscan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Device(
    val id: String,
    var name: String,
    var type: DeviceType,
    val profileId: String?,
    val brand: String,
    val custom: MutableMap<String, String> = mutableMapOf()
) {
    /** Codes effectifs : base intégrée + codes personnalisés (prioritaires). */
    fun codes(): Map<String, String> = (CodeDatabase.byId(profileId)?.codes ?: emptyMap()) + custom

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("type", type.name)
        put("profileId", profileId ?: ""); put("brand", brand)
        put("custom", JSONObject(custom as Map<*, *>))
    }

    companion object {
        fun fromJson(o: JSONObject): Device {
            val c = o.optJSONObject("custom") ?: JSONObject()
            val custom = mutableMapOf<String, String>()
            c.keys().forEach { k -> custom[k] = c.getString(k) }
            return Device(
                o.getString("id"), o.getString("name"), DeviceType.of(o.optString("type")),
                o.optString("profileId").ifEmpty { null }, o.optString("brand"), custom
            )
        }
    }
}

/** Enregistrement local des appareils (SharedPreferences, format JSON). */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("bs_ir_scan", Context.MODE_PRIVATE)

    fun all(): MutableList<Device> {
        val arr = try { JSONArray(prefs.getString("devices", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            try { Device.fromJson(arr.getJSONObject(it)) } catch (e: Exception) { null }
        }.toMutableList()
    }

    private fun saveAll(list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("devices", arr.toString()).apply()
    }

    fun get(id: String?): Device? = all().firstOrNull { it.id == id }

    fun upsert(d: Device) {
        val list = all()
        val i = list.indexOfFirst { it.id == d.id }
        if (i >= 0) list[i] = d else list.add(d)
        saveAll(list)
    }

    fun delete(id: String) = saveAll(all().filter { it.id != id })

    fun newId(): String = "dev_" + System.currentTimeMillis()
}
