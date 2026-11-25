package com.bsinaly.bsnotifspeak

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private var speaker: Speaker? = null
    private lateinit var root: LinearLayout

    // éléments mis à jour dynamiquement
    private lateinit var power: PowerButton
    private lateinit var powerText: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var voiceBox: LinearLayout
    private lateinit var historyBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.initDefaultApps(packageManager)
        speaker = Speaker(this) { runOnUiThread { fillVoice() } }
        build()
    }

    override fun onResume() {
        super.onResume()
        fillPermissions()
        fillApps()
        fillHistory()
        power.on = prefs.enabled
        updatePowerText()
        if (listenerAllowed()) {
            try { android.service.notification.NotificationListenerService.requestRebind(ComponentName(this, SpeakService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    // ───────────────────────── Construction de l'écran ─────────────────────────

    private fun build() {
        val scroll = ScrollView(this).apply { setBackgroundColor(C.NUIT); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        // En-tête bogolan
        val header = FrameLayout(this)
        header.addView(BogolanHeader(this), FrameLayout.LayoutParams(-1, dp(150)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), 0)
            addView(tv("BS NOTIF SPEAK", 27f, C.TEXT, true).apply { letterSpacing = 0.04f })
            addView(tv("Vos notifications, lues à haute voix", 14.5f, C.AMBRE))
        }
        header.addView(titles, FrameLayout.LayoutParams(-1, -2))
        root.addView(header, matchWrap())

        // Bouton marche/arrêt
        power = PowerButton(this).apply {
            on = prefs.enabled
            setOnClickListener {
                prefs.enabled = !prefs.enabled
                on = prefs.enabled
                updatePowerText()
                if (prefs.enabled) speaker?.say("BS NOTIF SPEAK est en marche.", flush = true)
                else speaker?.stop()
            }
        }
        root.addView(power, LinearLayout.LayoutParams(-1, dp(230)).apply { topMargin = dp(4) })
        powerText = tv("", 14f, C.GREY).apply { gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), dp(8)) }
        root.addView(powerText, matchWrap())

        // Autorisations
        root.addView(card().also { c ->
            c.addView(sectionTitle("Autorisations", "🔐"))
            permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(permBox)
        })

        // Applications
        root.addView(card().also { c ->
            c.addView(sectionTitle("Applications à lire", "📲"))
            appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(appsBox)
            c.addView(pill("Choisir les applications") {
                startActivity(Intent(this, AppsActivity::class.java))
            })
        })

        // Voix
        root.addView(card().also { c ->
            c.addView(sectionTitle("La voix", "🗣️"))
            voiceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(voiceBox)
        })
        fillVoice()

        // Ce qu'on lit
        root.addView(card().also { c ->
            c.addView(sectionTitle("Ce qui est lu", "📝"))
            val modes = listOf("Appli + nom + message", "Nom + message", "Annonce seule")
            val chips = row()
            fun redraw() {
                chips.removeAllViews()
                modes.forEachIndexed { i, m ->
                    chips.addView(chip(m, prefs.readMode == i) { prefs.readMode = i; redraw() },
                        weight1().apply { if (i > 0) leftMargin = dp(6) })
                }
            }
            redraw()
            c.addView(chips, matchWrap())
            c.addView(tv("Paiements (Wave, Orange Money, MTN, Moov, SMS) : toujours lus en premier et en court — « Paiement reçu : 5 000 francs, de Moussa. »", 12.5f, C.AMBRE).apply { setPadding(0, dp(8), 0, 0) })
            c.addView(slider("Longueur maximale lue", 60, 600, prefs.maxChars, { "$it caractères" }) { prefs.maxChars = it })
        })

        // Quand se taire
        root.addView(card().also { c ->
            c.addView(sectionTitle("Quand se taire", "🤫"))
            c.addView(switchRow("Pendant un appel", "Ne rien lire quand vous êtes au téléphone", prefs.skipDuringCall) { prefs.skipDuringCall = it })
            c.addView(switchRow("Mode silencieux / Ne pas déranger", "Se taire si le téléphone est en silencieux ou vibreur", prefs.respectSilent) { prefs.respectSilent = it })
            c.addView(switchRow("Seulement écran éteint", "Ne lire que lorsque vous n'êtes pas sur le téléphone", prefs.onlyScreenOff) { prefs.onlyScreenOff = it })
            val quietLabel = tv("", 13f, C.AMBRE)
            fun upd() { quietLabel.text = "De ${prefs.quietFrom} h à ${prefs.quietTo} h — toucher pour changer" }
            upd()
            c.addView(switchRow("Heures de silence", "Par exemple la nuit", prefs.quietEnabled) { prefs.quietEnabled = it })
            c.addView(quietLabel.apply {
                setPadding(0, 0, 0, dp(4))
                setOnClickListener { pickHour("Début du silence", prefs.quietFrom) { h -> prefs.quietFrom = h
                    pickHour("Fin du silence", prefs.quietTo) { h2 -> prefs.quietTo = h2; upd() } } }
            })
        })

        // Historique
        root.addView(card().also { c ->
            c.addView(sectionTitle("Derniers messages lus", "🕘"))
            historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(historyBox)
            c.addView(pill("Effacer l'historique", filled = false) { prefs.history = emptyList(); fillHistory() })
        })

        root.addView(tv("© BSINALY – 0779687995", 12f, C.GREY).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
        }, matchWrap())
    }

    private fun updatePowerText() {
        powerText.text = when {
            !listenerAllowed() -> "Autorisez l'accès aux notifications ci-dessous pour démarrer."
            prefs.enabled -> "Je lis vos notifications à haute voix. Je redémarre tout seul à chaque allumage du téléphone."
            else -> "En pause. Touchez le bouton pour reprendre la lecture."
        }
        powerText.setTextColor(if (prefs.enabled && listenerAllowed()) C.TEXT else C.GREY)
    }

    // ───────────────────────── Autorisations ─────────────────────────

    private fun listenerAllowed(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, SpeakService::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    private fun batteryFree(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun fillPermissions() {
        permBox.removeAllViews()
        val okListen = listenerAllowed()
        permBox.addView(permRow("Accès aux notifications", if (okListen) "Autorisé" else "Obligatoire pour lire les messages", okListen))
        if (!okListen) {
            permBox.addView(pill("1. Autoriser l'accès aux notifications") {
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (e: Exception) { openAppDetails() }
            })
            permBox.addView(tv("Si Android affiche « Paramètre restreint » : ouvrez les infos de l'appli, touchez ⋮ en haut à droite, puis « Autoriser les paramètres restreints », et recommencez.", 12.5f, C.GREY).apply { setPadding(0, dp(8), 0, 0) })
            permBox.addView(pill("Ouvrir les infos de l'appli", filled = false) { openAppDetails() })
        }
        val okBat = batteryFree()
        permBox.addView(permRow("Batterie sans restriction", if (okBat) "Le téléphone ne m'endormira pas" else "Recommandé pour ne rien rater", okBat).apply { setPadding(0, dp(14), 0, 0) })
        if (!okBat) {
            permBox.addView(pill("2. Ne pas limiter la batterie", filled = okListen) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            })
        }
        permBox.addView(tv("Tecno, Infinix, Itel, Xiaomi, Oppo… : activez aussi « Démarrage automatique » pour BS NOTIF SPEAK dans les infos de l'appli.", 12.5f, C.GREY).apply { setPadding(0, dp(12), 0, 0) })
        permBox.addView(pill("Démarrage automatique (infos de l'appli)", filled = false) { openAppDetails() })
        updatePowerText()
    }

    private fun permRow(title: String, sub: String, ok: Boolean): LinearLayout = row().apply {
        addView(dot(ok))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 15f, C.TEXT, true))
            addView(tv(sub, 12.5f, if (ok) C.OK else C.GREY))
        }, weight1())
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    // ───────────────────────── Applications ─────────────────────────

    private fun fillApps() {
        appsBox.removeAllViews()
        val pm = packageManager
        val names = prefs.apps.mapNotNull { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { null }
        }.sorted()
        if (names.isEmpty()) {
            appsBox.addView(tv("Aucune application choisie : rien ne sera lu.", 14f, C.BAD))
            return
        }
        appsBox.addView(tv("${names.size} application(s) lue(s) à haute voix :", 13.5f, C.GREY))
        appsBox.addView(tv(names.joinToString("  •  "), 15f, C.TEXT, true).apply { setPadding(0, dp(6), 0, 0) })
    }

    // ───────────────────────── Voix ─────────────────────────

    private fun fillVoice() {
        if (!::voiceBox.isInitialized) return
        voiceBox.removeAllViews()
        val sp = speaker
        if (sp == null || !sp.ready) {
            voiceBox.addView(tv("Préparation de la synthèse vocale…", 14f, C.GREY))
            voiceBox.addView(pill("Installer / régler la synthèse vocale", filled = false) { openTtsSettings() })
            return
        }

        // Moteur
        val engines = sp.engines()
        val engName = engines.firstOrNull { it.name == (prefs.engine.ifEmpty { sp.defaultEngine() }) }?.label ?: "Moteur par défaut"
        voiceBox.addView(tv("Moteur de voix", 12.5f, C.GREY))
        voiceBox.addView(row().apply {
            addView(tv(engName.toString(), 15f, C.TEXT, true), weight1())
            if (engines.size > 1) addView(tv("Changer ›", 14f, C.ORANGE, true).apply {
                setPadding(dp(8), dp(6), 0, dp(6))
                setOnClickListener { pickEngine(engines) }
            })
        })

        // Voix courante
        val list = sp.voices()
        val cur = list.firstOrNull { it.name == prefs.voice }
        val african = list.count { Voices.isAfrican(it) }
        voiceBox.addView(tv("Voix", 12.5f, C.GREY).apply { setPadding(0, dp(12), 0, 0) })
        voiceBox.addView(row().apply {
            addView(tv(cur?.let { labelFor(it, list) } ?: "Voix française par défaut", 15f, C.TEXT, true), weight1())
            addView(tv("Changer ›", 14f, C.ORANGE, true).apply {
                setPadding(dp(8), dp(6), 0, dp(6))
                setOnClickListener { pickVoice(list) }
            })
        })
        voiceBox.addView(tv(
            if (african > 0) "🌍 $african voix africaine(s) disponible(s) sur ce téléphone (en tête de liste)."
            else "🌍 Aucune voix africaine installée pour l'instant : touchez « Ajouter des voix ».",
            12.5f, C.AMBRE
        ).apply { setPadding(0, dp(6), 0, 0) })

        // Tons rapides
        voiceBox.addView(tv("Ton", 12.5f, C.GREY).apply { setPadding(0, dp(14), 0, dp(6)) })
        val tones = listOf(Triple("Grave", 90, 75), Triple("Posé", 85, 95), Triple("Naturel", 95, 100), Triple("Vif", 120, 108))
        val toneRow = row()
        tones.forEachIndexed { i, (name, rate, pitch) ->
            val sel = prefs.rate == rate && prefs.pitch == pitch
            toneRow.addView(chip(name, sel) {
                prefs.rate = rate; prefs.pitch = pitch
                sp.refresh(); fillVoice(); test()
            }, weight1().apply { if (i > 0) leftMargin = dp(6) })
        }
        voiceBox.addView(toneRow, matchWrap())
        voiceBox.addView(slider("Vitesse", 50, 200, prefs.rate, { "$it %" }) { prefs.rate = it; sp.refresh() })
        voiceBox.addView(slider("Hauteur de la voix", 50, 200, prefs.pitch, { "$it %" }) { prefs.pitch = it; sp.refresh() })

        voiceBox.addView(pill("▶  Écouter un essai") { test() })
        voiceBox.addView(pill("Ajouter des voix (africaines, françaises…)", filled = false) { addVoices() })
    }

    private fun labelFor(v: Voice, list: List<Voice>): String {
        val sameLang = list.filter { it.locale == v.locale }
        return Voices.label(v, sameLang.indexOf(v) + 1)
    }

    private fun test() {
        val sp = speaker ?: return
        sp.refresh()
        sp.say(Voices.sample(sp.currentVoice()), flush = true)
    }

    private fun dialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun pickEngine(engines: List<TextToSpeech.EngineInfo>) {
        val labels = engines.map { it.label ?: it.name }.toTypedArray()
        dialog().setTitle("Moteur de voix")
            .setItems(labels) { _, i ->
                prefs.engine = engines[i].name
                prefs.voice = ""
                speaker?.shutdown()
                speaker = Speaker(this) { runOnUiThread { fillVoice() } }
                fillVoice()
            }.show()
    }

    /** Liste des voix groupées : africaines, françaises, autres. */
    private fun pickVoice(list: List<Voice>) {
        if (list.isEmpty()) { addVoices(); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val scroll = ScrollView(this).apply { addView(box) }
        val dlg = dialog().setTitle("Choisir la voix").setView(scroll).setNegativeButton("Fermer", null).create()

        box.addView(voiceItem("Voix française par défaut", prefs.voice.isEmpty()) {
            prefs.voice = ""; speaker?.refresh(); fillVoice(); dlg.dismiss(); test()
        })
        var lastGroup = ""
        val counter = HashMap<java.util.Locale, Int>()
        for (v in list) {
            val g = Voices.group(v)
            if (g != lastGroup) {
                box.addView(tv(g.uppercase(), 12f, C.AMBRE, true).apply { setPadding(dp(8), dp(16), 0, dp(4)); letterSpacing = 0.1f })
                lastGroup = g
            }
            val n = (counter[v.locale] ?: 0) + 1
            counter[v.locale] = n
            box.addView(voiceItem(Voices.label(v, n), v.name == prefs.voice) {
                prefs.voice = v.name; speaker?.refresh(); fillVoice()
                speaker?.say(Voices.sample(v), flush = true)
            })
        }
        dlg.show()
    }

    private fun voiceItem(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        tv((if (selected) "●  " else "○  ") + text, 14.5f, if (selected) C.ORANGE else C.TEXT, selected).apply {
            setPadding(dp(8), dp(11), dp(8), dp(11))
            setOnClickListener { onClick() }
        }

    private fun addVoices() {
        val choices = arrayOf(
            "Télécharger des voix (moteur actuel)",
            "Réglages de synthèse vocale du téléphone",
            "Installer « Services vocaux Google » (Play Store)"
        )
        dialog().setTitle("Ajouter des voix")
            .setItems(choices) { _, i ->
                when (i) {
                    0 -> try {
                        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                            val e = prefs.engine.ifEmpty { speaker?.defaultEngine() ?: "" }
                            if (e.isNotEmpty()) setPackage(e)
                        })
                    } catch (e: Exception) { openTtsSettings() }
                    1 -> openTtsSettings()
                    2 -> openStore("com.google.android.tts")
                }
            }.show()
        Toast.makeText(this, "Astuce : installez les langues africaines et françaises que propose votre moteur de voix.", Toast.LENGTH_LONG).show()
    }

    private fun openTtsSettings() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
    }

    private fun openStore(pkg: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
        catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) } catch (_: Exception) {}
        }
    }

    // ───────────────────────── Divers ─────────────────────────

    private fun pickHour(title: String, current: Int, onPick: (Int) -> Unit) {
        val hours = (0..23).map { "$it h" + if (it == current) "  (actuel)" else "" }.toTypedArray()
        dialog().setTitle(title).setItems(hours) { _, i -> onPick(i) }.show()
    }

    private fun fillHistory() {
        historyBox.removeAllViews()
        val h = prefs.history
        if (h.isEmpty()) {
            historyBox.addView(tv("Rien pour l'instant. Les messages lus apparaîtront ici.", 13.5f, C.GREY))
            return
        }
        h.take(12).forEach { line ->
            historyBox.addView(tv(line, 13.5f, C.TEXT).apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(C.CARD2, 12)
                layoutParams = matchWrap(dp(6))
            })
        }
    }
}
