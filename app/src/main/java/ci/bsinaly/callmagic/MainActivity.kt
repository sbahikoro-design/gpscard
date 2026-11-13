package ci.bsinaly.callmagic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.StrictMode
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Accueil : nom, réglages (interphone, bulle, groupe auto), accès Wi-Fi / Bluetooth / salon. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var salon: Button
    private lateinit var bInter: Button
    private lateinit var bBubble: Button
    private lateinit var bAuto: Button
    private lateinit var bBattery: Button
    private val observer: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Permet d'ouvrir les fichiers reçus (chemin direct) sur Android 7 à 9
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        Hub.init(this)

        val root = Ui.screen(this)
        root.addView(Ui.title(this, "BS CALLMagic"))
        root.addView(Ui.text(this, "Messages, vocaux, fichiers, appels audio et vidéo sans Internet — par Wi-Fi ou Bluetooth.", 14f, Ui.MUTED))

        val st = Ui.card(this)
        status = Ui.text(this, "", 14f, Ui.TEXT)
        st.addView(status)
        root.addView(st, Ui.lp(this, top = 16))

        salon = Ui.button(this, "", Ui.GREEN) { startActivity(Intent(this, ChatActivity::class.java)) }
        root.addView(salon, Ui.lp(this, top = 14))

        root.addView(Ui.button(this, "📶  Connexion Wi-Fi", Ui.BLUE) {
            startActivity(Intent(this, WifiActivity::class.java))
        }, Ui.lp(this, top = 12))
        root.addView(Ui.button(this, "🔵  Connexion Bluetooth", Ui.BLUE) {
            startActivity(Intent(this, BluetoothActivity::class.java))
        }, Ui.lp(this, top = 10))

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
            if (name.text.isNotBlank()) { Prefs.setName(this, name.text.toString()); Ui.toast(this, "Nom enregistré") }
        }, Ui.lp(this, top = 6))
        root.addView(card, Ui.lp(this, top = 18))

        // Réglages
        root.addView(Ui.text(this, "Réglages", 18f, Ui.GOLD, true), Ui.lp(this, top = 20))
        bInter = Ui.button(this, "", Ui.GREY) {
            Prefs.setInterphone(this, !Prefs.interphone(this))
            if (!Prefs.interphone(this)) Interphone.stopAll()
            HubService.instance?.refreshOverlay(); render()
        }
        root.addView(bInter, Ui.lp(this, top = 8))
        root.addView(Ui.text(this, "Le téléphone lit à voix haute les messages reçus et joue les vocaux tout seul, même écran éteint.", 12f, Ui.MUTED), Ui.lp(this, top = 2))

        bBubble = Ui.button(this, "", Ui.GREY) { toggleBubble() }
        root.addView(bBubble, Ui.lp(this, top = 10))
        root.addView(Ui.text(this, "Bulle flottante par-dessus toutes les applications : lire, répondre, parler (appuyer pour parler), décrocher.", 12f, Ui.MUTED), Ui.lp(this, top = 2))

        bAuto = Ui.button(this, "", Ui.GREY) { Prefs.setAutoJoin(this, !Prefs.autoJoin(this)); render() }
        root.addView(bAuto, Ui.lp(this, top = 10))
        root.addView(Ui.text(this, "Sur un même Wi-Fi, tous les téléphones qui ont BS CALLMagic rejoignent automatiquement le salon.", 12f, Ui.MUTED), Ui.lp(this, top = 2))

        bBattery = Ui.button(this, "🔋 Empêcher la mise en veille de l'application", Ui.GREY) { askBattery() }
        root.addView(bBattery, Ui.lp(this, top = 10))

        root.addView(Ui.button(this, "⏻  Arrêter BS CALLMagic", Ui.RED) {
            HubService.stop(this); Ui.toast(this, "BS CALLMagic est arrêté. Relancez l'application pour le réactiver.")
            finishAffinity()
        }, Ui.lp(this, top = 18))

        root.addView(Ui.center(Ui.text(this, "BSINALY · 0779687995 · v2.0", 12f, Ui.MUTED)), Ui.lp(this, top = 20))

        HubService.start(this)
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        Hub.observe(observer)
        HubService.instance?.applyBubble()
        render()
    }

    override fun onPause() {
        super.onPause()
        Hub.unobserve(observer)
    }

    private fun render() {
        val ps = Hub.readyPeers()
        status.text = (if (HubService.instance != null) "🟢 Actif en arrière-plan — l'application réagit même fermée.\n" else "⏳ Démarrage…\n") +
            (if (ps.isEmpty()) "Aucun correspondant connecté." else "${ps.size} connecté(s) : " + ps.joinToString(", ") { it.name })
        salon.text = "💬  Ouvrir le salon" + if (ps.isNotEmpty()) " (${ps.size} connecté${if (ps.size > 1) "s" else ""})" else ""
        bInter.text = "🔊 Mode interphone : " + if (Prefs.interphone(this)) "ACTIVÉ" else "désactivé"
        bInter.background = Ui.rounded(if (Prefs.interphone(this)) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
        val bubbleOn = Prefs.bubble(this) && overlayAllowed()
        bBubble.text = "🫧 Bulle flottante : " + if (bubbleOn) "ACTIVÉE" else "désactivée"
        bBubble.background = Ui.rounded(if (bubbleOn) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
        bAuto.text = "🔗 Salon automatique Wi-Fi : " + if (Prefs.autoJoin(this)) "ACTIVÉ" else "désactivé"
        bAuto.background = Ui.rounded(if (Prefs.autoJoin(this)) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
        Ui.show(bBattery, !batteryIgnored())
    }

    private fun overlayAllowed() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun toggleBubble() {
        val on = Prefs.bubble(this) && overlayAllowed()
        if (on) {
            Prefs.setBubble(this, false)
        } else {
            Prefs.setBubble(this, true)
            if (!overlayAllowed()) {
                Ui.toast(this, "Autorisez « Affichage par-dessus les autres applis » pour BS CALLMagic, puis revenez.")
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
        }
        HubService.instance?.applyBubble()
        render()
    }

    private fun batteryIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28) wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Relance le service au premier plan avec l'accès micro (appels et vocaux en arrière-plan)
        HubService.start(this, HubService.ACTION_REFRESH)
    }
}
