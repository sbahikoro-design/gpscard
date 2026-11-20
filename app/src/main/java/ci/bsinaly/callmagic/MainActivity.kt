package ci.bsinaly.callmagic

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StrictMode
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Accueil : mes coordonnées, état des accès, annuaire des personnes disponibles, réglages. */
class MainActivity : BaseActivity() {

    private lateinit var codeText: TextView
    private lateinit var netText: TextView
    private lateinit var salon: Button
    private lateinit var people: LinearLayout
    private lateinit var bInter: Button
    private lateinit var bBubble: Button
    private lateinit var bAuto: Button
    private lateinit var bBattery: Button
    private lateinit var sosBtn: Button
    private lateinit var bLock: Button
    private val handler = Handler(Looper.getMainLooper())
    private val observer: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() { Hub.pruneFound(); render(); handler.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Permet d'ouvrir les fichiers reçus (chemin direct) sur Android 7 à 9
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        Hub.init(this)
        HubService.start(this)
        if (Prefs.simpleMode(this)) { startActivity(Intent(this, SimpleActivity::class.java)); finish(); return }

        val root = Ui.screen(this)
        root.addView(Ui.title(this, "BS CALLMagic"))
        root.addView(Ui.text(this, "Messages, vocaux, fichiers, appels audio et vidéo — Wi-Fi, Bluetooth ou Internet.", 13f, Ui.MUTED))

        // SOS : maintenir 2 secondes
        sosBtn = Ui.button(this, "", Ui.RED) {}
        sosBtn.textSize = 17f
        var armed: Runnable? = null
        sosBtn.setOnTouchListener { _, e ->
            val active = Sos.myActive != 0L
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!active) armed = Runnable { askSos() }.also { handler.postDelayed(it, 1800) }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    armed?.let { handler.removeCallbacks(it) }; armed = null
                    // Alerte en cours : un simple toucher suffit pour l'annuler
                    if (active && e.action == android.view.MotionEvent.ACTION_UP) confirmCancelSos()
                    true
                }
                else -> true
            }
        }
        root.addView(sosBtn, Ui.lp(this, top = 12))

        // Mes coordonnées
        val me = Ui.card(this)
        me.addView(Ui.text(this, "Mon code (à donner pour être joint par Internet)", 12f, Ui.MUTED))
        codeText = Ui.text(this, "", 24f, Ui.GOLD, true)
        codeText.setOnClickListener { copyCode() }
        me.addView(codeText)
        netText = Ui.text(this, "", 13f, Ui.TEXT)
        me.addView(netText, Ui.lp(this, top = 6))
        root.addView(me, Ui.lp(this, top = 14))

        val row = Ui.horizontal(this)
        salon = Ui.button(this, "", Ui.GREEN) { startActivity(Intent(this, ChatActivity::class.java)) }
        row.addView(salon, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.dp(this@MainActivity, 4) })
        row.addView(Ui.button(this, "🌐 Internet", Ui.BLUE) { startActivity(Intent(this, InternetActivity::class.java)) },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.dp(this@MainActivity, 4) })
        root.addView(row, Ui.lp(this, top = 12))

        // Outils
        val tools = listOf(
            "📮 Facteur" to PostActivity::class.java, "🌉 Pont SMS" to SmsActivity::class.java,
            "📍 Messages posés" to GeoActivity::class.java, "🆘 Alertes reçues" to SosActivity::class.java)
        for (k in tools.indices step 2) {
            val r = Ui.horizontal(this)
            for (j in 0..1) {
                val (label, cls) = tools[k + j]
                r.addView(Ui.button(this, label, Ui.CARD) { startActivity(Intent(this, cls)) },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (j == 0) rightMargin = Ui.dp(this@MainActivity, 4) else leftMargin = Ui.dp(this@MainActivity, 4) })
            }
            root.addView(r, Ui.lp(this, top = 8))
        }

        // Annuaire
        root.addView(Ui.text(this, "Personnes", 20f, Ui.GOLD, true), Ui.lp(this, top = 20))
        root.addView(Ui.text(this, "🟢 connecté · 🟡 disponible · ⚪ peut-être joignable · ⚫ hors de portée\nTouchez une personne pour écrire, appeler ou envoyer un fichier, et choisir l'accès.", 11f, Ui.MUTED))
        people = Ui.vertical(this)
        root.addView(people)
        root.addView(Ui.button(this, "➕  Ajouter / contacter avec un code ou une IP", Ui.GREY) {
            startActivity(Intent(this, InternetActivity::class.java))
        }, Ui.lp(this, top = 12))
        val row2 = Ui.horizontal(this)
        row2.addView(Ui.button(this, "📶 Wi-Fi", Ui.GREY) { startActivity(Intent(this, WifiActivity::class.java)) },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.dp(this@MainActivity, 4) })
        row2.addView(Ui.button(this, "🔵 Bluetooth", Ui.GREY) { startActivity(Intent(this, BluetoothActivity::class.java)) },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.dp(this@MainActivity, 4) })
        root.addView(row2, Ui.lp(this, top = 8))

        // Nom
        val card = Ui.card(this)
        card.addView(Ui.text(this, "Votre nom (vu par les correspondants)", 13f, Ui.MUTED))
        val name = EditText(this).apply {
            setText(Prefs.name(this@MainActivity))
            setTextColor(Ui.TEXT); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        card.addView(name)
        card.addView(Ui.button(this, "Enregistrer le nom", Ui.GREY) {
            if (name.text.isNotBlank()) {
                Prefs.setName(this, name.text.toString()); Relay.refreshPresence(); Ui.toast(this, "Nom enregistré")
            }
        }, Ui.lp(this, top = 6))
        root.addView(card, Ui.lp(this, top = 22))

        // Réglages
        root.addView(Ui.text(this, "Réglages", 18f, Ui.GOLD, true), Ui.lp(this, top = 20))
        bInter = Ui.button(this, "", Ui.GREY) {
            Prefs.setInterphone(this, !Prefs.interphone(this))
            if (!Prefs.interphone(this)) Interphone.stopAll()
            HubService.instance?.refreshOverlay(); render()
        }
        root.addView(bInter, Ui.lp(this, top = 8))
        root.addView(Ui.text(this, "Lit à voix haute les messages reçus et joue les vocaux tout seul, même écran éteint.", 12f, Ui.MUTED), Ui.lp(this, top = 2))
        bBubble = Ui.button(this, "", Ui.GREY) { toggleBubble() }
        root.addView(bBubble, Ui.lp(this, top = 10))
        root.addView(Ui.text(this, "Bulle par-dessus toutes les applications : lire, répondre, parler, décrocher.", 12f, Ui.MUTED), Ui.lp(this, top = 2))
        bAuto = Ui.button(this, "", Ui.GREY) { Prefs.setAutoJoin(this, !Prefs.autoJoin(this)); render() }
        root.addView(bAuto, Ui.lp(this, top = 10))
        root.addView(Ui.text(this, "Sur un même Wi-Fi, les téléphones BS CALLMagic se connectent automatiquement.", 12f, Ui.MUTED), Ui.lp(this, top = 2))
        bLock = Ui.button(this, "", Ui.GREY) { toggleLock() }
        root.addView(bLock, Ui.lp(this, top = 10))
        root.addView(Ui.text(this, "Code (et empreinte) demandé à l'ouverture. Verrouillé, les notifications et la bulle ne montrent plus le contenu des messages.", 12f, Ui.MUTED), Ui.lp(this, top = 2))
        root.addView(Ui.button(this, "🆘 Numéros prévenus par SMS en cas d'alerte…", Ui.GREY) { sosNumbers() }, Ui.lp(this, top = 10))
        root.addView(Ui.button(this, "🗣 Passer en mode sans lecture", Ui.GREY) {
            android.app.AlertDialog.Builder(this).setTitle("Mode sans lecture")
                .setMessage("L'écran n'affichera plus que des visages et de grands boutons, et tout sera dit à voix haute. " +
                    "Pour revenir au mode normal : gardez le doigt 3 secondes sur ⚙ en bas de l'écran.\n\n" +
                    "Astuce : choisissez d'abord une photo pour chaque contact (touchez une personne › Choisir sa photo).")
                .setPositiveButton("Activer") { _, _ -> Prefs.setSimpleMode(this, true); startActivity(Intent(this, SimpleActivity::class.java)); finish() }
                .setNegativeButton("Annuler", null).show()
        }, Ui.lp(this, top = 10))
        bBattery = Ui.button(this, "🔋 Empêcher la mise en veille de l'application", Ui.GREY) { askBattery() }
        root.addView(bBattery, Ui.lp(this, top = 10))
        root.addView(Ui.button(this, "⏻  Arrêter BS CALLMagic", Ui.RED) {
            HubService.stop(this); Ui.toast(this, "BS CALLMagic est arrêté. Relancez l'application pour le réactiver.")
            finishAffinity()
        }, Ui.lp(this, top = 18))
        root.addView(Ui.center(Ui.text(this, "BSINALY · 0779687995 · v" + BuildConfig.VERSION_NAME, 12f, Ui.MUTED)), Ui.lp(this, top = 20))

        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        Hub.observe(observer)
        HubService.instance?.applyBubble()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Hub.unobserve(observer)
        handler.removeCallbacks(ticker)
    }

    private fun render() {
        sosBtn.text = if (Sos.myActive != 0L) {
            val min = ((System.currentTimeMillis() - Sos.myActiveSince()) / 60_000).toInt()
            "🆘 VOTRE ALERTE EST EN COURS (${min} min)\nTouchez ici pour l'arrêter"
        } else "🆘 SOS — gardez le doigt 2 secondes"
        sosBtn.background = Ui.rounded(if (Sos.myActive != 0L) 0xFF8B0000.toInt() else Ui.RED, Ui.dp(this, 12).toFloat())
        val carried = Courier.carriedForOthers()
        val active = Sos.alerts.count { !it.cancelled && System.currentTimeMillis() - it.time < 6 * 3600_000 }
        codeText.text = Contacts.pretty(Hub.myCode())
        val ps = Hub.readyPeers()
        val wifi = Hub.localIps()
        netText.text = (if (HubService.instance != null) "🟢 Actif en arrière-plan\n" else "⏳ Démarrage…\n") +
            (if (wifi.isNotEmpty()) "📶 Réseau local : ${wifi.joinToString(", ")}" else "📶 Pas de réseau Wi-Fi") + "\n" +
            "🔵 Bluetooth : " + (if (Hub.btReady()) "actif" else "désactivé ou non autorisé") + "\n" +
            Relay.statusText() +
            (if (carried > 0) "\n📮 Vous transportez $carried lettre(s) scellée(s) pour d'autres" else "") +
            (if (Routing.reachable().isNotEmpty()) "\n🔗 ${Routing.reachable().size} personne(s) joignable(s) par la chaîne humaine" else "") +
            (if (active > 0) "\n🆘 $active alerte(s) active(s) autour de vous" else "")
        val unreadSalon = Hub.unreadBy[""] ?: 0
        salon.text = "💬 Salon" + (if (ps.isNotEmpty()) " (${ps.size})" else "") + (if (unreadSalon > 0) " · $unreadSalon 🔴" else "")

        people.removeAllViews()
        val dir = Contacts.directory()
        if (dir.isEmpty()) people.addView(Ui.text(this,
            "Personne pour l'instant. Ouvrez BS CALLMagic sur un autre téléphone (même Wi-Fi ou Bluetooth), ou ajoutez quelqu'un avec son code ou son adresse IP.",
            13f, Ui.MUTED), Ui.lp(this, top = 8))
        for (c in dir) people.addView(People.row(this, c) { People.showActions(this, c) { render() } }, Ui.lp(this, top = 8))

        bInter.text = "🔊 Mode interphone : " + if (Prefs.interphone(this)) "ACTIVÉ" else "désactivé"
        bInter.background = Ui.rounded(if (Prefs.interphone(this)) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
        val bubbleOn = Prefs.bubble(this) && overlayAllowed()
        bBubble.text = "🫧 Bulle flottante : " + if (bubbleOn) "ACTIVÉE" else "désactivée"
        bBubble.background = Ui.rounded(if (bubbleOn) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
        bAuto.text = "🔗 Connexion automatique Wi-Fi : " + if (Prefs.autoJoin(this)) "ACTIVÉE" else "désactivée"
        bAuto.background = Ui.rounded(if (Prefs.autoJoin(this)) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
        Ui.show(bBattery, !batteryIgnored())
        val locked = Lock.enabled(this)
        bLock.text = "🔒 Verrouillage de l'application : " + if (locked) "ACTIVÉ" else "désactivé"
        bLock.background = Ui.rounded(if (locked) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
    }

    private fun copyCode() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Code BS CALLMagic", Contacts.pretty(Hub.myCode())))
        Ui.toast(this, "Code copié")
    }

    private fun overlayAllowed() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun toggleBubble() {
        val on = Prefs.bubble(this) && overlayAllowed()
        if (on) Prefs.setBubble(this, false)
        else {
            Prefs.setBubble(this, true)
            if (!overlayAllowed()) {
                Ui.toast(this, "Autorisez « Affichage par-dessus les autres applis » pour BS CALLMagic, puis revenez.")
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
        }
        HubService.instance?.applyBubble()
        render()
    }

    private fun batteryIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        if (Build.VERSION.SDK_INT < 23) return
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {} }
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28) wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1) { HubService.start(this, HubService.ACTION_REFRESH); return }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun askSos() {
        try { (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(300) } catch (_: Exception) {}
        android.app.AlertDialog.Builder(this).setTitle("🆘 Quelle urgence ?")
            .setItems(Sos.TYPES.toTypedArray()) { _, i ->
                val note = EditText(this).apply { hint = "Précision (facultatif) : lieu, ce qu'il faut…" }
                android.app.AlertDialog.Builder(this).setTitle(Sos.TYPES[i]).setView(note)
                    .setPositiveButton("ENVOYER L'ALERTE") { _, _ ->
                        val go = { countdown(Sos.TYPES[i], note.text.toString().trim()) }
                        if (Prefs.sosNumbers(this).isNotBlank() && BuildConfig.SMS) needPerms(listOf("android.permission.SEND_SMS", Manifest.permission.ACCESS_FINE_LOCATION)) { go() }
                        else needPerms(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { go() }
                    }.setNegativeButton("Annuler", null).show()
            }.setNegativeButton("Annuler", null).show()
    }

    /** 5 secondes pour se raviser avant que l'alerte parte. */
    private fun countdown(type: String, note: String) {
        var left = 5
        val tv = android.widget.TextView(this).apply {
            textSize = 46f; gravity = android.view.Gravity.CENTER; setTextColor(Ui.RED)
            setPadding(0, Ui.dp(this@MainActivity, 20), 0, Ui.dp(this@MainActivity, 20))
        }
        lateinit var dlg: android.app.AlertDialog
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                if (left <= 0) {
                    dlg.dismiss()
                    Ui.toast(this@MainActivity, "Envoi de l'alerte avec votre position…")
                    Sos.send(this@MainActivity, type, note) { render() }
                    return
                }
                tv.text = left.toString(); left--
                handler.postDelayed(this, 1000)
            }
        }
        dlg = android.app.AlertDialog.Builder(this).setTitle("$type — envoi dans…").setView(tv).setCancelable(false)
            .setNegativeButton("ANNULER") { _, _ -> handler.removeCallbacks(tick) }.create()
        dlg.show()
        handler.post(tick)
    }

    private fun toggleLock() {
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "4 à 8 chiffres" }
        if (Lock.enabled(this)) {
            android.app.AlertDialog.Builder(this).setTitle("Désactiver le verrouillage").setMessage("Entrez votre code actuel :").setView(e)
                .setPositiveButton("Désactiver") { _, _ ->
                    if (Lock.check(this, e.text.toString())) { Lock.disable(this); render() } else Ui.toast(this, "Code incorrect.")
                }.setNegativeButton("Annuler", null).show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("Choisissez un code").setView(e)
            .setPositiveButton("Suivant") { _, _ ->
                val p1 = e.text.toString()
                if (p1.length !in 4..8) { Ui.toast(this, "Le code doit faire 4 à 8 chiffres."); return@setPositiveButton }
                val e2 = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
                android.app.AlertDialog.Builder(this).setTitle("Confirmez le code").setView(e2)
                    .setPositiveButton("Activer") { _, _ ->
                        if (e2.text.toString() == p1) {
                            Lock.setPin(this, p1); render()
                            Ui.toast(this, "Verrouillage activé" + if (Lock.biometricAvailable(this)) " (empreinte possible)." else ".")
                        } else Ui.toast(this, "Les deux codes sont différents.")
                    }.setNegativeButton("Annuler", null).show()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun confirmCancelSos() {
        android.app.AlertDialog.Builder(this).setTitle("Annuler l'alerte ?")
            .setMessage("Tout le monde sera prévenu que c'était une fausse alerte ou que tout va bien.")
            .setPositiveButton("Oui, annuler") { _, _ -> Sos.cancel(this); render() }
            .setNegativeButton("Non", null).show()
    }

    private fun sosNumbers() {
        val e = EditText(this).apply {
            setText(Prefs.sosNumbers(this@MainActivity)); hint = "ex. 0707070707, 0505050505"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        android.app.AlertDialog.Builder(this).setTitle("Numéros prévenus par SMS")
            .setMessage("En cas d'alerte, ces numéros reçoivent un SMS avec votre position (si votre téléphone ou un pont SMS a du réseau). 5 au maximum, séparés par des virgules." +
                if (BuildConfig.SMS) "" else "\n\nÉdition normale : au moment de l'alerte, votre application SMS s'ouvre avec le message déjà écrit — il suffit de toucher Envoyer.")
            .setView(e).setPositiveButton("Enregistrer") { _, _ ->
                Prefs.setSosNumbers(this, e.text.toString())
                if (e.text.isNotBlank() && BuildConfig.SMS) needPerms(listOf("android.permission.SEND_SMS")) {}
            }.setNegativeButton("Annuler", null).show()
    }
}
