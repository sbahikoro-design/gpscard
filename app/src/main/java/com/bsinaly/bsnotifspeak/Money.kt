package com.bsinaly.bsnotifspeak

import java.util.Locale

/**
 * Reconnaît les notifications d'argent (Wave, Orange Money, MTN MoMo, Moov Money, SMS de banque…)
 * et les transforme en une phrase courte : « Paiement reçu : 5 000 francs, de Bahikoro Sinaly. »
 */
object Money {

    enum class Kind { RECU, ENVOYE, PAIEMENT_FAIT, RETRAIT }

    data class Info(val kind: Kind, val amount: Long, val who: String?)

    // Montant : 5F · 5 000 FCFA · 5.000 F · 12,500 XOF · 2500 F CFA · FCFA 5000
    private val AMOUNT = Regex(
        "(?:(?:F\\s?CFA|FCFA|XOF|CFA)\\s*)?(\\d{1,3}(?:[ .,\\u00A0\\u202F]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?\\s?(?:F\\s?CFA|FCFA|CFA|XOF|F\\b|FRS?\\b|francs?)",
        RegexOption.IGNORE_CASE
    )
    private val AMOUNT_PREFIX = Regex(
        "(?:F\\s?CFA|FCFA|XOF|CFA)\\s*(\\d{1,3}(?:[ .,\\u00A0\\u202F]\\d{3})+|\\d+)",
        RegexOption.IGNORE_CASE
    )

    private val RECU = Regex(
        "re[çc]u|received|vous a (envoy|transf|pay)|a [ée]t[ée] cr[ée]dit|cr[ée]dit[ée]|d[ée]p[oô]t (re[çc]u|effectu)|paiement re[çc]u|encaiss|vous avez re[çc]u",
        RegexOption.IGNORE_CASE
    )
    private val RETRAIT = Regex("retrait|withdraw", RegexOption.IGNORE_CASE)
    private val PAIEMENT = Regex("paiement (r[ée]ussi|effectu)|vous avez pay[ée]|achat|facture", RegexOption.IGNORE_CASE)
    private val ENVOYE = Regex("envoy[ée]|transfert r[ée]ussi|sent|d[ée]bit[ée]|transf[ée]r[ée]", RegexOption.IGNORE_CASE)

    // Ce qui ne doit jamais être pris pour le montant
    private val SOLDE = Regex("(nouveau\\s+)?solde|balance|frais|fee", RegexOption.IGNORE_CASE)

    private val NAME_AFTER = Regex(
        "(?:\\bde\\b|\\bpar\\b|\\bfrom\\b|\\b[àa]\\b|\\bto\\b|\\bvers\\b)\\s+([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ'’.\\- ]{1,40}?)\\s*(?:\\(|\\+?\\d|,|\\.|;|:|\\ble\\b|\\bau\\b|$)",
        RegexOption.IGNORE_CASE
    )
    private val NOT_NAMES = setOf("votre", "vous", "compte", "wave", "orange", "money", "mtn", "moov", "momo", "solde", "frais", "la", "le", "les")

    fun parse(text: String): Info? {
        if (text.isBlank()) return null
        val amount = firstAmount(text) ?: return null
        val kind = when {
            RECU.containsMatchIn(text) -> Kind.RECU
            RETRAIT.containsMatchIn(text) -> Kind.RETRAIT
            PAIEMENT.containsMatchIn(text) -> Kind.PAIEMENT_FAIT
            ENVOYE.containsMatchIn(text) -> Kind.ENVOYE
            else -> return null
        }
        return Info(kind, amount, findName(text))
    }

    /** Premier montant qui n'est ni un solde ni des frais. */
    private fun firstAmount(text: String): Long? {
        val all = (AMOUNT.findAll(text).map { it.range.first to it.groupValues[1] } +
            AMOUNT_PREFIX.findAll(text).map { it.range.first to it.groupValues[1] })
            .sortedBy { it.first }
        for ((pos, raw) in all) {
            val before = text.substring(maxOf(0, pos - 22), pos)
            if (SOLDE.containsMatchIn(before)) continue
            val n = raw.replace(Regex("[^0-9]"), "").toLongOrNull() ?: continue
            if (n <= 0 || n > 100_000_000_000L) continue
            return n
        }
        return null
    }

    private fun findName(original: String): String? {
        val text = original.replace(Regex("de la part de|de la part d'", RegexOption.IGNORE_CASE), "de")
        for (m in NAME_AFTER.findAll(text)) {
            val raw = m.groupValues[1].trim().trim('-', '.', '\'')
            val words = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty() || words.size > 5) continue
            if (words.first().lowercase() in NOT_NAMES) continue
            if (words.any { it.any(Char::isDigit) }) continue
            return words.joinToString(" ") { niceWord(it) }
        }
        return null
    }

    fun niceWord(w: String): String =
        w.lowercase(Locale.FRENCH).split("-").joinToString("-") { p -> p.replaceFirstChar { it.titlecase(Locale.FRENCH) } }

    /** « 5000 » → lu « cinq mille » par la voix française. */
    fun phrase(i: Info): String {
        val montant = "${i.amount} francs"
        return when (i.kind) {
            Kind.RECU -> "Paiement reçu : $montant" + (i.who?.let { ", de $it" } ?: "") + "."
            Kind.ENVOYE -> "Argent envoyé : $montant" + (i.who?.let { ", à $it" } ?: "") + "."
            Kind.PAIEMENT_FAIT -> "Paiement effectué : $montant."
            Kind.RETRAIT -> "Retrait : $montant."
        }
    }
}
