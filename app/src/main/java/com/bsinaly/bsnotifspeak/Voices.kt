package com.bsinaly.bsnotifspeak

import android.speech.tts.Voice
import java.util.Locale

/** Classement des voix disponibles : africaines d'abord, puis françaises, puis les autres. */
object Voices {

    /** Pays d'Afrique (codes ISO). */
    private val AFRICA = setOf(
        "DZ", "AO", "BJ", "BW", "BF", "BI", "CV", "CM", "CF", "TD", "KM", "CG", "CD", "CI", "DJ",
        "EG", "GQ", "ER", "SZ", "ET", "GA", "GM", "GH", "GN", "GW", "KE", "LS", "LR", "LY", "MG",
        "MW", "ML", "MR", "MU", "MA", "MZ", "NA", "NE", "NG", "RW", "ST", "SN", "SC", "SL", "SO",
        "ZA", "SS", "SD", "TZ", "TG", "TN", "UG", "ZM", "ZW", "EH"
    )

    /** Langues africaines (codes ISO 639). */
    private val AFRICAN_LANGS = setOf(
        "sw", "swa", "yo", "yor", "ha", "hau", "ig", "ibo", "am", "amh", "af", "afr", "zu", "zul",
        "xh", "xho", "so", "som", "rw", "kin", "wo", "wol", "ff", "ful", "ln", "lin", "bm", "bam",
        "dyu", "ak", "aka", "tw", "twi", "ee", "ewe", "om", "orm", "ti", "tir", "mg", "mlg", "sn",
        "sna", "st", "sot", "tn", "tsn", "ny", "nya", "lg", "lug", "ki", "kik", "kab", "ber", "tzm",
        "nso", "ts", "tso", "ve", "ven", "ss", "ssw", "nr", "sg", "sag", "luo", "kln", "mos", "fon",
        "bci", "dje", "kg", "lua", "rn", "run"
    )

    fun isAfrican(v: Voice): Boolean {
        val l = v.locale
        return l.country.uppercase() in AFRICA || l.language.lowercase() in AFRICAN_LANGS
    }

    fun isFrench(v: Voice): Boolean = v.locale.language.lowercase() in setOf("fr", "fra", "fre")

    fun usable(v: Voice): Boolean =
        !v.features.contains("notInstalled")

    /** Tri : africaines, françaises, autres ; puis par langue. */
    fun sort(list: Collection<Voice>): List<Voice> =
        list.filter { usable(it) }.sortedWith(
            compareBy<Voice>(
                { if (isAfrican(it)) 0 else if (isFrench(it)) 1 else 2 },
                { it.locale.getDisplayName(Locale.FRENCH) },
                { it.name }
            )
        )

    fun group(v: Voice): String = when {
        isAfrican(v) -> "Voix africaines"
        isFrench(v) -> "Voix françaises"
        else -> "Autres voix"
    }

    /** Libellé lisible : « Anglais (Nigeria) · voix 2 · hors ligne ». */
    fun label(v: Voice, index: Int): String {
        val lang = v.locale.getDisplayName(Locale.FRENCH).replaceFirstChar { it.uppercase() }
        val net = if (v.isNetworkConnectionRequired) "en ligne" else "hors ligne"
        val gender = when {
            v.name.contains("female", true) || v.features.any { it.contains("female", true) } -> " · femme"
            v.name.contains("male", true) || v.features.any { it.contains("male", true) } -> " · homme"
            else -> ""
        }
        return "$lang · voix $index$gender · $net"
    }

    /** Phrase d'essai adaptée à la langue de la voix. */
    fun sample(v: Voice?): String {
        val l = v?.locale?.language?.lowercase() ?: "fr"
        return when (l) {
            "fr", "fra" -> "Bonjour ! Ici BS NOTIF SPEAK. Nouveau message WhatsApp de Moussa : on se voit ce soir à Odienné ?"
            "en", "eng" -> "Hello! This is BS NOTIF SPEAK. New WhatsApp message from Moussa: see you tonight?"
            "sw", "swa" -> "Habari! Huyu ni BS NOTIF SPEAK. Ujumbe mpya kutoka kwa Moussa."
            "yo", "yor" -> "Ẹ n lẹ o! BS NOTIF SPEAK ni yìí."
            "ha", "hau" -> "Sannu! Wannan shine BS NOTIF SPEAK."
            "af", "afr" -> "Hallo! Dit is BS NOTIF SPEAK."
            "zu", "zul" -> "Sawubona! Lena yi BS NOTIF SPEAK."
            "am", "amh" -> "ሰላም! ይህ BS NOTIF SPEAK ነው።"
            "ar", "ara" -> "مرحبا! هذا تطبيق BS NOTIF SPEAK."
            "pt", "por" -> "Olá! Aqui é o BS NOTIF SPEAK. Nova mensagem de Moussa."
            else -> "BS NOTIF SPEAK. Moussa."
        }
    }
}
