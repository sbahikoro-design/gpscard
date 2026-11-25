package com.bsinaly.bsnotifspeak

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager

/** Tous les réglages de l'appli, stockés sur le téléphone. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("bs_notif_speak", Context.MODE_PRIVATE)

    /** En marche par défaut dès l'installation. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var apps: Set<String>
        get() = sp.getStringSet("apps", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("apps", v.toSet()).apply()

    var appsInitialised: Boolean
        get() = sp.getBoolean("apps_init", false)
        set(v) = sp.edit().putBoolean("apps_init", v).apply()

    /** Moteur de synthèse vocale (vide = moteur par défaut du téléphone). */
    var engine: String
        get() = sp.getString("engine", "") ?: ""
        set(v) = sp.edit().putString("engine", v).apply()

    /** Nom exact de la voix choisie (vide = voix par défaut). */
    var voice: String
        get() = sp.getString("voice", "") ?: ""
        set(v) = sp.edit().putString("voice", v).apply()

    /** Vitesse 50..200 (%) */
    var rate: Int
        get() = sp.getInt("rate", 95)
        set(v) = sp.edit().putInt("rate", v).apply()

    /** Hauteur 50..200 (%) */
    var pitch: Int
        get() = sp.getInt("pitch", 100)
        set(v) = sp.edit().putInt("pitch", v).apply()

    /** Ce qu'on lit : 0 = appli + expéditeur + message, 1 = expéditeur + message, 2 = annonce seulement */
    var readMode: Int
        get() = sp.getInt("read_mode", 1)
        set(v) = sp.edit().putInt("read_mode", v).apply()

    var skipDuringCall: Boolean
        get() = sp.getBoolean("skip_call", true)
        set(v) = sp.edit().putBoolean("skip_call", v).apply()

    var respectSilent: Boolean
        get() = sp.getBoolean("respect_silent", true)
        set(v) = sp.edit().putBoolean("respect_silent", v).apply()

    var onlyScreenOff: Boolean
        get() = sp.getBoolean("only_screen_off", false)
        set(v) = sp.edit().putBoolean("only_screen_off", v).apply()

    /** Longueur maximale lue (caractères) */
    var maxChars: Int
        get() = sp.getInt("max_chars", 220)
        set(v) = sp.edit().putInt("max_chars", v).apply()

    var quietEnabled: Boolean
        get() = sp.getBoolean("quiet", false)
        set(v) = sp.edit().putBoolean("quiet", v).apply()

    /** Heures de silence (début / fin, 0..23) */
    var quietFrom: Int
        get() = sp.getInt("quiet_from", 22)
        set(v) = sp.edit().putInt("quiet_from", v).apply()

    var quietTo: Int
        get() = sp.getInt("quiet_to", 6)
        set(v) = sp.edit().putInt("quiet_to", v).apply()

    var history: List<String>
        get() = (sp.getString("history", "") ?: "").split("\u0001").filter { it.isNotBlank() }
        set(v) = sp.edit().putString("history", v.take(30).joinToString("\u0001")).apply()

    fun addHistory(line: String) {
        history = listOf(line) + history
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    /** Au premier lancement : cocher les messageries courantes déjà installées. */
    fun initDefaultApps(pm: PackageManager) {
        if (appsInitialised) return
        val found = DEFAULT_APPS.filter { pkg ->
            try { pm.getApplicationInfo(pkg, 0); true } catch (e: Exception) { false }
        }
        apps = found.toSet()
        appsInitialised = true
    }

    companion object {
        val DEFAULT_APPS = listOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.android.mms", "com.transsion.message", "com.android.messaging",
            "org.telegram.messenger", "com.facebook.orca",
            "com.wave.personal", "com.wave.business"
        )
    }
}
