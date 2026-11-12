package ci.bsinaly.irscan

enum class DeviceType(val label: String, val icon: String) {
    TV("Téléviseur", "📺"),
    DECODEUR("Décodeur / TNT / Satellite", "📡"),
    PROJECTEUR("Vidéoprojecteur", "📽"),
    AUDIO("Barre de son / Ampli", "🔊"),
    VENTILATEUR("Ventilateur", "🌀"),
    CLIMATISEUR("Climatiseur", "❄"),
    AUTRE("Autre appareil", "🔘");

    companion object {
        fun of(name: String?): DeviceType = values().firstOrNull { it.name == name } ?: AUTRE
    }
}

/** Touches de télécommande gérées par l'application. */
object Keys {
    val labels = linkedMapOf(
        "POWER" to "⏻ Marche/Arrêt", "INPUT" to "Source", "MUTE" to "🔇 Muet",
        "VOL_UP" to "Vol +", "VOL_DOWN" to "Vol −", "CH_UP" to "Ch +", "CH_DOWN" to "Ch −",
        "UP" to "▲", "DOWN" to "▼", "LEFT" to "◀", "RIGHT" to "▶", "OK" to "OK",
        "MENU" to "Menu", "HOME" to "⌂ Accueil", "BACK" to "↩ Retour",
        "D1" to "1", "D2" to "2", "D3" to "3", "D4" to "4", "D5" to "5",
        "D6" to "6", "D7" to "7", "D8" to "8", "D9" to "9", "D0" to "0",
        "TEMP_UP" to "Temp +", "TEMP_DOWN" to "Temp −", "MODE" to "Mode",
        "FAN" to "Ventil.", "SWING" to "Oscill.", "TIMER" to "Minuterie",
        "SPEED" to "Vitesse", "OSC" to "Rotation"
    )

    fun label(key: String) = labels[key] ?: key

    private val nav = listOf(
        listOf("", "UP", ""),
        listOf("LEFT", "OK", "RIGHT"),
        listOf("BACK", "DOWN", "HOME")
    )
    private val digits = listOf(
        listOf("D1", "D2", "D3"), listOf("D4", "D5", "D6"),
        listOf("D7", "D8", "D9"), listOf("", "D0", "")
    )

    /** Disposition de la télécommande selon le type d'appareil. */
    fun layout(type: DeviceType): List<List<String>> = when (type) {
        DeviceType.TV, DeviceType.DECODEUR, DeviceType.AUTRE ->
            listOf(listOf("POWER", "INPUT", "MUTE")) + nav +
                listOf(listOf("VOL_UP", "MENU", "CH_UP"), listOf("VOL_DOWN", "", "CH_DOWN")) + digits
        DeviceType.PROJECTEUR ->
            listOf(listOf("POWER", "INPUT", "MENU")) + nav + listOf(listOf("VOL_UP", "MUTE", "VOL_DOWN"))
        DeviceType.AUDIO ->
            listOf(listOf("POWER", "INPUT", "MUTE"), listOf("VOL_UP", "", "VOL_DOWN"))
        DeviceType.VENTILATEUR ->
            listOf(listOf("POWER", "SPEED", "OSC"), listOf("MODE", "TIMER", ""))
        DeviceType.CLIMATISEUR ->
            listOf(listOf("POWER", "MODE", "FAN"), listOf("TEMP_UP", "SWING", "TEMP_DOWN"), listOf("", "TIMER", ""))
    }
}

data class Profile(
    val id: String,
    val brand: String,
    val type: DeviceType,
    val codes: Map<String, String>
)

/**
 * Base de codes intégrée. Chaque profil = une marque/protocole.
 * Le balayage envoie le code POWER de chaque profil, l'un après l'autre.
 * Toute touche manquante peut être ajoutée depuis la télécommande (appui long).
 */
object CodeDatabase {

    private fun nec(a: Int, vararg k: Pair<String, Int>) = k.associate { it.first to "NEC:%02X:%02X".format(a, it.second) }
    private fun necx(a: Int, vararg k: Pair<String, Int>) = k.associate { it.first to "NECX:%04X:%02X".format(a, it.second) }
    private fun sam(a: Int, vararg k: Pair<String, Int>) = k.associate { it.first to "SAMSUNG:%02X:%02X".format(a, it.second) }
    private fun sony(a: Int, vararg k: Pair<String, Int>) = k.associate { it.first to "SONY12:%02X:%02X".format(a, it.second) }
    private fun pana(vararg k: Pair<String, Int>) = k.associate { it.first to "PANASONIC:80:00:%02X".format(it.second) }
    private fun rc5(a: Int, vararg k: Pair<String, Int>) = k.associate { it.first to "RC5:%02X:%02X".format(a, it.second) }

    val profiles: List<Profile> = listOf(
        Profile(
            "samsung_tv", "Samsung", DeviceType.TV, sam(
                0x07,
                "POWER" to 0x02, "INPUT" to 0x01, "MUTE" to 0x0F,
                "VOL_UP" to 0x07, "VOL_DOWN" to 0x0B, "CH_UP" to 0x12, "CH_DOWN" to 0x10,
                "MENU" to 0x1A, "HOME" to 0x79, "BACK" to 0x58,
                "UP" to 0x60, "DOWN" to 0x61, "LEFT" to 0x65, "RIGHT" to 0x62, "OK" to 0x68,
                "D1" to 0x04, "D2" to 0x05, "D3" to 0x06, "D4" to 0x08, "D5" to 0x09,
                "D6" to 0x0A, "D7" to 0x0C, "D8" to 0x0D, "D9" to 0x0E, "D0" to 0x11
            )
        ),
        Profile(
            "lg_tv", "LG", DeviceType.TV, nec(
                0x04,
                "POWER" to 0x08, "INPUT" to 0x0B, "MUTE" to 0x09,
                "VOL_UP" to 0x02, "VOL_DOWN" to 0x03, "CH_UP" to 0x00, "CH_DOWN" to 0x01,
                "MENU" to 0x43, "HOME" to 0x7C, "BACK" to 0x28,
                "UP" to 0x40, "DOWN" to 0x41, "LEFT" to 0x07, "RIGHT" to 0x06, "OK" to 0x44,
                "D1" to 0x11, "D2" to 0x12, "D3" to 0x13, "D4" to 0x14, "D5" to 0x15,
                "D6" to 0x16, "D7" to 0x17, "D8" to 0x18, "D9" to 0x19, "D0" to 0x10
            )
        ),
        Profile(
            "sony_tv", "Sony", DeviceType.TV, sony(
                0x01,
                "POWER" to 21, "INPUT" to 37, "MUTE" to 20,
                "VOL_UP" to 18, "VOL_DOWN" to 19, "CH_UP" to 16, "CH_DOWN" to 17,
                "MENU" to 96, "BACK" to 99,
                "UP" to 116, "DOWN" to 117, "LEFT" to 52, "RIGHT" to 51, "OK" to 101,
                "D1" to 0, "D2" to 1, "D3" to 2, "D4" to 3, "D5" to 4,
                "D6" to 5, "D7" to 6, "D8" to 7, "D9" to 8, "D0" to 9
            )
        ),
        Profile(
            "philips_tv", "Philips (RC5)", DeviceType.TV, rc5(
                0x00,
                "POWER" to 12, "MUTE" to 13,
                "VOL_UP" to 16, "VOL_DOWN" to 17, "CH_UP" to 32, "CH_DOWN" to 33,
                "D1" to 1, "D2" to 2, "D3" to 3, "D4" to 4, "D5" to 5,
                "D6" to 6, "D7" to 7, "D8" to 8, "D9" to 9, "D0" to 0
            )
        ),
        Profile(
            "panasonic_tv", "Panasonic", DeviceType.TV, pana(
                "POWER" to 0x3D, "INPUT" to 0x05, "MUTE" to 0x32,
                "VOL_UP" to 0x20, "VOL_DOWN" to 0x21, "CH_UP" to 0x34, "CH_DOWN" to 0x35,
                "D1" to 0x10, "D2" to 0x11, "D3" to 0x12, "D4" to 0x13, "D5" to 0x14,
                "D6" to 0x15, "D7" to 0x16, "D8" to 0x17, "D9" to 0x18, "D0" to 0x19
            )
        ),
        Profile(
            "toshiba_tv", "Toshiba", DeviceType.TV, nec(
                0x40,
                "POWER" to 0x12, "MUTE" to 0x10,
                "VOL_UP" to 0x1A, "VOL_DOWN" to 0x1E, "CH_UP" to 0x1B, "CH_DOWN" to 0x1F
            )
        ),
        Profile(
            "epson_proj", "Epson", DeviceType.PROJECTEUR, necx(
                0x5583,
                "POWER" to 0x90
            )
        )
    )

    fun byId(id: String?): Profile? = profiles.firstOrNull { it.id == id }

    // ---------------- Candidats de balayage ----------------

    /** Adresses NEC/Samsung les plus répandues (TV génériques, décodeurs, ventilateurs, LED…). */
    private val COMMON_ADDR = listOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0E, 0x10,
        0x18, 0x20, 0x40, 0x44, 0x50, 0x6D, 0x80, 0x86, 0xA0, 0xBF, 0xEF, 0xFE
    )

    /** Commandes « Marche/Arrêt » les plus fréquentes en NEC. */
    private val NEC_POWER = listOf(0x08, 0x12, 0x45, 0x40, 0x02, 0x0A, 0x1C, 0x00)

    fun candidates(mode: ScanMode): List<Candidate> {
        val list = profiles.mapNotNull { p ->
            p.codes["POWER"]?.let { Candidate(p.brand, p.type, it, p.id) }
        }.toMutableList()
        if (mode == ScanMode.MARQUES) return list
        val known = list.map { it.spec }.toMutableSet()
        val addrs = if (mode == ScanMode.PROFOND) COMMON_ADDR
        else COMMON_ADDR + (0..0xFF).filter { it !in COMMON_ADDR }
        val cmds = if (mode == ScanMode.PROFOND) NEC_POWER.take(5) else NEC_POWER
        for (a in addrs) {
            for (c in cmds) {
                val s = "NEC:%02X:%02X".format(a, c)
                if (known.add(s)) list.add(Candidate("NEC · adr %02X · cmd %02X".format(a, c), null, s, null))
            }
            val ss = "SAMSUNG:%02X:02".format(a)
            if (known.add(ss)) list.add(Candidate("Samsung32 · adr %02X".format(a), null, ss, null))
        }
        return list
    }
}

/** Code testé pendant le balayage. */
data class Candidate(val label: String, val type: DeviceType?, val spec: String, val profileId: String?)

enum class ScanMode(val label: String, val detail: String) {
    MARQUES("Rapide — marques connues", "Samsung, LG, Sony, Panasonic, Philips, Toshiba, Epson"),
    PROFOND("Profond — marques + appareils génériques", "Ajoute les adresses NEC/Samsung courantes (décodeurs, TV génériques, ventilateurs)"),
    EXHAUSTIF("Exhaustif — toutes les adresses", "Plus de 2 300 codes : long, à lancer si rien n'a été trouvé")
}
