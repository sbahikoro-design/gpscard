package ci.bsinaly.irscan

import kotlin.math.roundToInt

/** Signal prêt à émettre : fréquence porteuse (Hz) + durées alternées marque/espace (µs). */
class IrSignal(val frequency: Int, val pattern: IntArray)

/**
 * Convertit un « code » texte en signal infrarouge.
 *
 * Formats acceptés (valeurs en hexadécimal) :
 *   NEC:04:08            NEC standard  (adresse 8 bits, commande)
 *   NECX:5583:90         NEC étendu    (adresse 16 bits)
 *   SAMSUNG:07:02        Samsung32
 *   SONY12:01:15         Sony SIRC 12 bits  (SONY15 pour 15 bits)
 *   RC5:00:0C            Philips RC5
 *   PRONTO:0000 006D ... Code Pronto brut (bases irdb, RemoteCentral…)
 *   RAW:38000:9000,4500,560,...   Durées brutes en µs
 */
object IrEncoder {

    fun encode(spec: String, toggle: Boolean = false): IrSignal {
        val s = spec.trim()
        val sep = s.indexOf(':')
        require(sep > 0) { "Format invalide : « $s »" }
        val proto = s.substring(0, sep).trim().uppercase()
        val rest = s.substring(sep + 1).trim()
        return when (proto) {
            "PRONTO" -> pronto(rest)
            "RAW" -> raw(rest)
            else -> {
                val p = rest.split(":").map { it.trim().toInt(16) }
                require(p.size == 2) { "Attendu PROTOCOLE:ADRESSE:COMMANDE" }
                when (proto) {
                    "NEC" -> nec(p[0], p[1], extended = p[0] > 0xFF)
                    "NECX" -> nec(p[0], p[1], extended = true)
                    "SAMSUNG" -> samsung(p[0], p[1])
                    "SONY12" -> sony(12, p[0], p[1])
                    "SONY15" -> sony(15, p[0], p[1])
                    "RC5" -> rc5(p[0], p[1], toggle)
                    else -> throw IllegalArgumentException("Protocole inconnu : $proto")
                }
            }
        }
    }

    fun isValid(spec: String): Boolean = try { encode(spec); true } catch (e: Exception) { false }

    // ---------- Codage « pulse distance » (NEC / Samsung) ----------
    private fun pulseDistance(header: IntArray, bytes: IntArray): IntArray {
        val out = ArrayList<Int>()
        header.forEach { out.add(it) }
        for (b in bytes) for (i in 0 until 8) {
            out.add(560)
            out.add(if ((b shr i) and 1 == 1) 1690 else 560)
        }
        out.add(560) // bit d'arrêt
        return out.toIntArray()
    }

    fun nec(address: Int, command: Int, extended: Boolean): IrSignal {
        val a = if (extended) intArrayOf(address and 0xFF, (address shr 8) and 0xFF)
        else intArrayOf(address and 0xFF, address.inv() and 0xFF)
        val bytes = a + intArrayOf(command and 0xFF, command.inv() and 0xFF)
        return IrSignal(38000, pulseDistance(intArrayOf(9000, 4500), bytes))
    }

    fun samsung(address: Int, command: Int): IrSignal {
        val bytes = intArrayOf(address and 0xFF, address and 0xFF, command and 0xFF, command.inv() and 0xFF)
        return IrSignal(38000, pulseDistance(intArrayOf(4500, 4500), bytes))
    }

    // ---------- Sony SIRC (envoyé 3 fois, période 45 ms) ----------
    fun sony(bits: Int, address: Int, command: Int): IrSignal {
        val data = (command and 0x7F) or (address shl 7)
        val frame = ArrayList<Int>()
        frame.add(2400); frame.add(600)
        for (i in 0 until bits) {
            frame.add(if ((data shr i) and 1 == 1) 1200 else 600)
            frame.add(600)
        }
        val used = frame.sum()
        frame[frame.size - 1] = 600 + (45000 - used).coerceAtLeast(0)
        val out = ArrayList<Int>()
        repeat(3) { out.addAll(frame) }
        out.removeAt(out.size - 1) // le motif se termine par une marque
        return IrSignal(40000, out.toIntArray())
    }

    // ---------- Philips RC5 (Manchester, 889 µs) ----------
    fun rc5(address: Int, command: Int, toggle: Boolean): IrSignal {
        val bits = ArrayList<Int>()
        bits.add(1)
        bits.add(if (command < 64) 1 else 0)
        bits.add(if (toggle) 1 else 0)
        for (i in 4 downTo 0) bits.add((address shr i) and 1)
        for (i in 5 downTo 0) bits.add((command shr i) and 1)
        val levels = ArrayList<Int>()
        for (b in bits) if (b == 1) { levels.add(0); levels.add(1) } else { levels.add(1); levels.add(0) }
        val start = levels.indexOfFirst { it == 1 }
        val end = levels.indexOfLast { it == 1 }
        val out = ArrayList<Int>()
        var cur = -1
        var len = 0
        for (i in start..end) {
            if (levels[i] == cur) len += 889 else {
                if (cur != -1) out.add(len)
                cur = levels[i]; len = 889
            }
        }
        out.add(len)
        return IrSignal(36000, out.toIntArray())
    }

    // ---------- Pronto hexadécimal ----------
    fun pronto(hex: String): IrSignal {
        val w = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16) }
        require(w.size >= 4 && w[0] == 0) { "Seul le format Pronto brut (commençant par 0000) est accepté" }
        require(w[1] > 0) { "Fréquence Pronto invalide" }
        val freq = (1_000_000.0 / (w[1] * 0.241246)).roundToInt()
        val n1 = w[2]
        val n2 = w[3]
        require(w.size >= 4 + 2 * (n1 + n2)) { "Code Pronto incomplet" }
        val period = 1_000_000.0 / freq
        val words = ArrayList<Int>()
        if (n1 > 0) words.addAll(w.subList(4, 4 + 2 * n1))
        if (n2 > 0) words.addAll(w.subList(4 + 2 * n1, 4 + 2 * n1 + 2 * n2))
        require(words.isNotEmpty()) { "Code Pronto vide" }
        val pattern = words.map { (it * period).roundToInt().coerceAtLeast(1) }.toMutableList()
        if (pattern.size % 2 == 0) pattern.removeAt(pattern.size - 1)
        return IrSignal(freq, pattern.toIntArray())
    }

    // ---------- Brut ----------
    fun raw(rest: String): IrSignal {
        val sep = rest.indexOf(':')
        require(sep > 0) { "Attendu RAW:FREQUENCE:durées" }
        val freq = rest.substring(0, sep).trim().toInt()
        val values = rest.substring(sep + 1).split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            .map { kotlin.math.abs(it.toInt()) }.toMutableList()
        require(values.isNotEmpty()) { "Aucune durée" }
        if (values.size % 2 == 0) values.removeAt(values.size - 1)
        return IrSignal(freq, values.toIntArray())
    }
}
