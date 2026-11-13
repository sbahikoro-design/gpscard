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

        val root = Ui.screen(this)
        root.addView(Ui.title(this, "BS CALLMagic"))
        root.addView(Ui.text(this, "Messages, vocaux, fichiers, appels audio et vidéo — Wi-Fi, Bluetooth ou Internet.", 13f, Ui.MUTED))

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
        bBattery = Ui.button(this, "🔋 Empêcher la mise en veille de l'application", Ui.GREY) { askBattery() }
        root.addView(bBattery, Ui.lp(this, top = 10))
        root.addView(Ui.button(this, "⏻  Arrêter BS CALLMagic", Ui.RED) {
            HubService.stop(this); Ui.toast(this, "BS CALLMagic est arrêté. Relancez l'application pour le réactiver.")
            finishAffinity()
        }, Ui.lp(this, top = 18))
        root.addView(Ui.center(Ui.text(this, "BSINALY · 0779687995 · v3.0", 12f, Ui.MUTED)), Ui.lp(this, top = 20))

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
        codeText.text = Contacts.pretty(Hub.myCode())
        val ps = Hub.readyPeers()
        val wifi = Hub.localIps()
        netText.text = (if (HubService.instance != null) "🟢 Actif en arrière-plan\n" else "⏳ Démarrage…\n") +
            (if (wifi.isNotEmpty()) "📶 Réseau local : ${wifi.joinToString(", ")}" else "📶 Pas de réseau Wi-Fi") + "\n" +
            "🔵 Bluetooth : " + (if (Hub.btReady()) "actif" else "désactivé ou non autorisé") + "\n" +
            Relay.statusText()
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
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28) wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1) { HubService.start(this, HubService.ACTION_REFRESH); return }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
