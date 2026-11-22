package com.bsinaly.bsmagickeyboard

import java.text.Normalizer

/** Modificateurs HID (octet 0 du rapport clavier). */
object Mod {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val WIN = 0x08
    const val ALTGR = 0x40

    fun names(m: Int): List<String> = buildList {
        if (m and CTRL != 0) add("ctrl")
        if (m and SHIFT != 0) add("shift")
        if (m and ALT != 0) add("alt")
        if (m and WIN != 0) add("win")
        if (m and ALTGR != 0) add("altgr")
    }
}

/** Une frappe : modificateurs + code de touche HID. */
data class Stroke(val mod: Int, val code: Int)

/**
 * Correspondance caractère -> touches physiques, pour les deux dispositions
 * de clavier les plus courantes sur les PC : AZERTY (France / Côte d'Ivoire) et QWERTY (US).
 */
object Keys {

    val named: Map<String, Int> = mapOf(
        "enter" to 0x28, "esc" to 0x29, "backspace" to 0x2A, "tab" to 0x2B,
        "space" to 0x2C, "capslock" to 0x39, "printscreen" to 0x46,
        "insert" to 0x49, "home" to 0x4A, "pgup" to 0x4B, "delete" to 0x4C,
        "end" to 0x4D, "pgdn" to 0x4E, "right" to 0x4F, "left" to 0x50,
        "down" to 0x51, "up" to 0x52, "menu" to 0x65,
        "num0" to 0x27 // touche 0 de la rangée du haut (Ctrl+0)
    ) + (1..12).associate { "f$it" to (0x39 + it) }

    /** Touches multimédia (page « Consumer » HID). */
    val consumer: Map<String, Int> = mapOf(
        "vol_up" to 0xE9, "vol_down" to 0xEA, "mute" to 0xE2,
        "play" to 0xCD, "next" to 0xB5, "prev" to 0xB6
    )

    private fun letter(c: Char) = 0x04 + (c - 'a')

    private val us: Map<Char, List<Stroke>> by lazy { buildUs() }
    private val fr: Map<Char, List<Stroke>> by lazy { buildFr() }

    fun layout(azerty: Boolean) = if (azerty) fr else us

    private fun buildUs(): Map<Char, List<Stroke>> {
        val m = HashMap<Char, List<Stroke>>()
        for (c in 'a'..'z') {
            m[c] = listOf(Stroke(0, letter(c)))
            m[c.uppercaseChar()] = listOf(Stroke(Mod.SHIFT, letter(c)))
        }
        val digits = "1234567890"
        val shifted = "!@#\$%^&*()"
        for (i in 0..9) {
            m[digits[i]] = listOf(Stroke(0, 0x1E + i))
            m[shifted[i]] = listOf(Stroke(Mod.SHIFT, 0x1E + i))
        }
        val pairs = listOf(
            Triple('-', '_', 0x2D), Triple('=', '+', 0x2E), Triple('[', '{', 0x2F),
            Triple(']', '}', 0x30), Triple('\\', '|', 0x31), Triple(';', ':', 0x33),
            Triple('\'', '"', 0x34), Triple('`', '~', 0x35), Triple(',', '<', 0x36),
            Triple('.', '>', 0x37), Triple('/', '?', 0x38)
        )
        for ((a, b, code) in pairs) {
            m[a] = listOf(Stroke(0, code))
            m[b] = listOf(Stroke(Mod.SHIFT, code))
        }
        m[' '] = listOf(Stroke(0, 0x2C))
        m['\n'] = listOf(Stroke(0, 0x28))
        m['\t'] = listOf(Stroke(0, 0x2B))
        return m
    }

    /** Une touche AZERTY : position (code HID US) + caractère normal / Maj / AltGr. */
    private class K(val code: Int, val n: Char?, val s: Char? = null, val a: Char? = null)

    private fun buildFr(): Map<Char, List<Stroke>> {
        val m = HashMap<Char, List<Stroke>>()
        // Lettres : seules a/q, z/w et m changent de place
        for (c in 'a'..'z') {
            val code = when (c) {
                'a' -> letter('q'); 'q' -> letter('a')
                'z' -> letter('w'); 'w' -> letter('z')
                'm' -> 0x33
                else -> letter(c)
            }
            m[c] = listOf(Stroke(0, code))
            m[c.uppercaseChar()] = listOf(Stroke(Mod.SHIFT, code))
        }
        val keys = listOf(
            K(0x35, '²'),
            K(0x1E, '&', '1'), K(0x1F, 'é', '2'), K(0x20, '"', '3', '#'),
            K(0x21, '\'', '4', '{'), K(0x22, '(', '5', '['), K(0x23, '-', '6', '|'),
            K(0x24, 'è', '7'), K(0x25, '_', '8', '\\'), K(0x26, 'ç', '9', '^'),
            K(0x27, 'à', '0', '@'), K(0x2D, ')', '°', ']'), K(0x2E, '=', '+', '}'),
            K(0x30, '$', '£', '¤'), K(0x34, 'ù', '%'), K(0x32, '*', 'µ'),
            K(0x64, '<', '>'), K(0x10, ',', '?'), K(0x36, ';', '.'),
            K(0x37, ':', '/'), K(0x38, '!', '§'), K(0x08, null, null, '€')
        )
        for (k in keys) {
            k.n?.let { m[it] = listOf(Stroke(0, k.code)) }
            k.s?.let { m[it] = listOf(Stroke(Mod.SHIFT, k.code)) }
            k.a?.let { m[it] = listOf(Stroke(Mod.ALTGR, k.code)) }
        }
        val space = Stroke(0, 0x2C)
        // Touches mortes : ^ ¨ ~ ` seuls = touche morte + espace
        m['~'] = listOf(Stroke(Mod.ALTGR, 0x1F), space)
        m['`'] = listOf(Stroke(Mod.ALTGR, 0x24), space)
        m['¨'] = listOf(Stroke(Mod.SHIFT, 0x2F), space)
        // Voyelles accentuées par touche morte
        val circ = Stroke(0, 0x2F)
        val trema = Stroke(Mod.SHIFT, 0x2F)
        "aeiou".zip("âêîôû").forEach { (b, c) -> m[c] = listOf(circ) + m[b]!! }
        "AEIOU".zip("ÂÊÎÔÛ").forEach { (b, c) -> m[c] = listOf(circ) + m[b]!! }
        "aeiouy".zip("äëïöüÿ").forEach { (b, c) -> m[c] = listOf(trema) + m[b]!! }
        "AEIOU".zip("ÄËÏÖÜ").forEach { (b, c) -> m[c] = listOf(trema) + m[b]!! }
        m[' '] = listOf(space)
        m['\n'] = listOf(Stroke(0, 0x28))
        m['\t'] = listOf(Stroke(0, 0x2B))
        return m
    }

    /** Remplace les caractères « typographiques » du clavier du téléphone. */
    private fun normalize(c: Char): Char = when (c) {
        '’', '‘', '´', 'ʼ' -> '\''
        '“', '”', '«', '»' -> '"'
        ' ', ' ' -> ' '
        '–', '—' -> '-'
        '…' -> '.'
        '\r' -> '\n'
        else -> c
    }

    fun strokes(text: String, azerty: Boolean): List<Stroke> {
        val map = layout(azerty)
        val out = ArrayList<Stroke>()
        for (raw in text) {
            val c = normalize(raw)
            val s = map[c] ?: run {
                // Repli : lettre sans accent (É -> E, ñ -> n…)
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                    .firstOrNull() ?: return@run null
                map[base]
            } ?: continue
            out.addAll(s)
            if (raw == '…') { out.addAll(s); out.addAll(s) }
        }
        return out
    }

    /** Touche nommée (« enter », « f5 ») ou caractère seul (« c » pour Ctrl+C). */
    fun stroke(name: String, azerty: Boolean): Stroke? {
        named[name]?.let { return Stroke(0, it) }
        if (name.length == 1) return strokes(name, azerty).lastOrNull()
        return null
    }
}
