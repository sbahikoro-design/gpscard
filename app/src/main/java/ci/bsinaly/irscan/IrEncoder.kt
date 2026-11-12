package ci.bsinaly.irscan

import kotlin.math.roundToInt

/** Signal prêt à émettre : fréquence porteuse (Hz) + durées alternées marque/espace (µs). */
class IrSignal(val frequency: Int, val pattern: IntArray) {
    val durationUs: Long get() = pattern.fold(0L) { a, v -> a + v }
}

/**
 * Trame d'un protocole : premier envoi + motif de répétition.
 * Chaque motif se termine par une marque ; la période fixe l'espacement entre deux envois.
 */
class IrFrame(
    val frequency: Int,
    val first: IntArray, val firstPeriod: Int,
    val repeat: IntArray, val repeatPeriod: Int,
    val minCount: Int
)

/**
 * Convertit un « code » texte en signal infrarouge.
 *
 * Formats (valeurs hexadécimales) :
 *   NEC:04:08              NEC standard (adresse 8 bits)
 *   NECX:5583:90           NEC étendu (adresse 16 bits)
 *   SAMSUNG:07:02          Samsung32
 *   SONY12:01:15           Sony SIRC 12 bits (SONY15 : 15 bits)
 *   RC5:00:0C              Philips RC5
 *   PANASONIC:80:00:3D     Panasonic / Kaseikyo (appareil:sous-appareil:fonction)
 *   PRONTO:0000 006D ...   Pronto brut (bases irdb, RemoteCentral…)
 *   RAW:38000:9000,4500,…  Durées brutes en µs
 */
object IrEncoder {

    /** Durée maximale d'une émission (limite Android ≈ 2 s). */
    private const val MAX_US = 1_800_000L

    fun encode(spec: String, toggle: Boolean = false, count: Int = 0): IrSignal =
        build(frame(spec, toggle), count)

    fun isValid(spec: String): Boolean = try { frame(spec, false); true } catch (e: Exception) { false }

    fun frame(spec: String, toggle: Boolean): IrFrame {
        val s = spec.trim().replace("\n", " ")
        val sep = s.indexOf(':')
        require(sep > 0) { "Format invalide : « $s »" }
        val proto = s.substring(0, sep).trim().uppercase()
        val rest = s.substring(sep + 1).trim()
        return when (proto) {
            "PRONTO" -> pronto(rest)
            "RAW" -> raw(rest)
            else -> {
                val p = rest.split(":").map { it.trim().toInt(16) }
                when (proto) {
                    "NEC" -> { need(p, 2); nec(p[0], p[1], p[0] > 0xFF) }
                    "NECX" -> { need(p, 2); nec(p[0], p[1], true) }
                    "SAMSUNG" -> { need(p, 2); samsung(p[0], p[1]) }
                    "SONY12" -> { need(p, 2); sony(12, p[0], p[1]) }
                    "SONY15" -> { need(p, 2); sony(15, p[0], p[1]) }
                    "RC5" -> { need(p, 2); rc5(p[0], p[1], toggle) }
                    "PANASONIC" -> { need(p, 3); panasonic(p[0], p[1], p[2]) }
                    else -> throw IllegalArgumentException("Protocole inconnu : $proto")
                }
            }
        }
    }

    private fun need(p: List<Int>, n: Int) =
        require(p.size == n) { "Nombre de valeurs incorrect (attendu : $n)" }

    /** Assemble les envois successifs, sans dépasser la limite de durée. */
    fun build(f: IrFrame, count: Int): IrSignal {
        val n = maxOf(count, f.minCount, 1)
        val out = ArrayList<Int>()
        var total = 0L
        var lastPeriod = f.firstPeriod
        var lastDur = 0
        for (i in 0 until n) {
            val burst = if (i == 0) f.first else f.repeat
            val burstDur = burst.sum()
            if (i > 0) {
                val gap = (lastPeriod - lastDur).coerceAtLeast(8000)
                if (total + gap + burstDur > MAX_US) break
                out.add(gap); total += gap
            }
            burst.forEach { out.add(it) }
            total += burstDur
            lastDur = burstDur
            lastPeriod = if (i == 0) f.firstPeriod else f.repeatPeriod
        }
        return IrSignal(f.frequency, out.toIntArray())
    }

    // ---------- Codage « pulse distance » ----------
    private fun pulseDistance(header: IntArray, bytes: IntArray, mark: Int, zero: Int, one: Int): IntArray {
        val out = ArrayList<Int>()
        header.forEach { out.add(it) }
        for (b in bytes) for (i in 0 until 8) {
            out.add(mark)
            out.add(if ((b shr i) and 1 == 1) one else zero)
        }
        out.add(mark)
        return out.toIntArray()
    }

    fun nec(address: Int, command: Int, extended: Boolean): IrFrame {
        val a = if (extended) intArrayOf(address and 0xFF, (address shr 8) and 0xFF)
        else intArrayOf(address and 0xFF, address.inv() and 0xFF)
        val bytes = a + intArrayOf(command and 0xFF, command.inv() and 0xFF)
        val frame = pulseDistance(intArrayOf(9000, 4500), bytes, 560, 560, 1690)
        // Code de répétition NEC (touche maintenue)
        return IrFrame(38000, frame, 108000, intArrayOf(9000, 2250, 560), 108000, 2)
    }

    fun samsung(address: Int, command: Int): IrFrame {
        val bytes = intArrayOf(address and 0xFF, address and 0xFF, command and 0xFF, command.inv() and 0xFF)
        val frame = pulseDistance(intArrayOf(4500, 4500), bytes, 560, 560, 1690)
        return IrFrame(38000, frame, 108000, frame, 108000, 1)
    }

    fun panasonic(device: Int, sub: Int, function: Int): IrFrame {
        val d = device and 0xFF; val s = sub and 0xFF; val f = function and 0xFF
        val bytes = intArrayOf(0x02, 0x20, d, s, f, d xor s xor f)
        val frame = pulseDistance(intArrayOf(3456, 1728), bytes, 432, 432, 1296)
        return IrFrame(37000, frame, 130000, frame, 130000, 1)
    }

    fun sony(bits: Int, address: Int, command: Int): IrFrame {
        val data = (command and 0x7F) or (address shl 7)
        val frame = ArrayList<Int>()
        frame.add(2400); frame.add(600)
        for (i in 0 until bits) {
            frame.add(if ((data shr i) and 1 == 1) 1200 else 600)
            frame.add(600)
        }
        frame.removeAt(frame.size - 1)
        val f = frame.toIntArray()
        return IrFrame(40000, f, 45000, f, 45000, 3)
    }

    fun rc5(address: Int, command: Int, toggle: Boolean): IrFrame {
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
        val f = out.toIntArray()
        return IrFrame(36000, f, 113778, f, 113778, 1)
    }

    fun pronto(hex: String): IrFrame {
        val w = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16) }
        require(w.size >= 4 && w[0] == 0) { "Seul le format Pronto brut (commençant par 0000) est accepté" }
        require(w[1] > 0) { "Fréquence Pronto invalide" }
        val freq = (1_000_000.0 / (w[1] * 0.241246)).roundToInt()
        val n1 = w[2]
        val n2 = w[3]
        require(n1 + n2 > 0) { "Code Pronto vide" }
        require(w.size >= 4 + 2 * (n1 + n2)) { "Code Pronto incomplet" }
        val period = 1_000_000.0 / freq
        fun seq(from: Int, n: Int) = w.subList(from, from + 2 * n).map { (it * period).roundToInt().coerceAtLeast(1) }
        val s1 = if (n1 > 0) seq(4, n1) else seq(4 + 2 * n1, n2)
        val s2 = if (n2 > 0) seq(4 + 2 * n1, n2) else s1
        return IrFrame(
            freq,
            s1.dropLast(1).toIntArray(), s1.sum(),
            s2.dropLast(1).toIntArray(), s2.sum(),
            if (n1 > 0 && n2 > 0) 2 else 1
        )
    }

    fun raw(rest: String): IrFrame {
        val sep = rest.indexOf(':')
        require(sep > 0) { "Attendu RAW:FREQUENCE:durées" }
        val freq = rest.substring(0, sep).trim().toInt()
        require(freq in 20000..60000) { "Fréquence hors plage (20 000 – 60 000 Hz)" }
        val values = rest.substring(sep + 1).split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            .map { kotlin.math.abs(it.toInt()) }.toMutableList()
        require(values.isNotEmpty()) { "Aucune durée" }
        if (values.size % 2 == 0) values.removeAt(values.size - 1)
        val f = values.toIntArray()
        return IrFrame(freq, f, f.sum() + 40000, f, f.sum() + 40000, 1)
    }
}

/** Famille d'un code (protocole + adresse) : permet d'explorer toutes les commandes d'un appareil. */
class CodeFamily(val prefix: String, val maxCommand: Int) {
    fun spec(command: Int) = "%s:%02X".format(prefix, command)
    fun commandOf(spec: String): Int? =
        if (spec.trim().uppercase().startsWith(prefix.uppercase() + ":"))
            spec.trim().substringAfterLast(':').toIntOrNull(16) else null

    companion object {
        fun of(spec: String?): CodeFamily? {
            if (spec == null) return null
            val parts = spec.trim().split(":").map { it.trim() }
            val proto = parts[0].uppercase()
            return when {
                proto in listOf("NEC", "NECX", "SAMSUNG") && parts.size == 3 -> CodeFamily("$proto:${parts[1]}", 0xFF)
                proto in listOf("SONY12", "SONY15", "RC5") && parts.size == 3 -> CodeFamily("$proto:${parts[1]}", 0x7F)
                proto == "PANASONIC" && parts.size == 4 -> CodeFamily("$proto:${parts[1]}:${parts[2]}", 0xFF)
                else -> null
            }
        }
    }
}
