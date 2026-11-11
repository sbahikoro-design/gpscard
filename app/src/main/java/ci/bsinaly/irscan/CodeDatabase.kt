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
}
