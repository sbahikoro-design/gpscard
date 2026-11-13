package ci.bsinaly.callmagic

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText

/** Accueil : pseudo, choix du mode d'échange (Wi-Fi ou Bluetooth). */
class MainActivity : Activity() {

    private lateinit var resume: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Permet d'ouvrir les fichiers reçus (chemin direct) sur Android 7 à 9
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

        val root = Ui.screen(this)
        root.addView(Ui.title(this, "BS CALLMagic"))
        root.addView(Ui.text(this, "Messages, fichiers et appels vocaux sans Internet ni crédit — par Wi-Fi ou Bluetooth.", 14f, Ui.MUTED))

        // Pseudo
        val card = Ui.card(this)
        card.addView(Ui.text(this, "Votre nom (vu par le correspondant)", 13f, Ui.MUTED))
        val name = EditText(this).apply {
            setText(Prefs.name(this@MainActivity))
            setTextColor(Ui.TEXT); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        card.addView(name)
        card.addView(Ui.button(this, "Enregistrer le nom", Ui.GREY) {
            Prefs.setName(this, name.text.toString()); Ui.toast(this, "Nom enregistré")
        }, Ui.lp(this, top = 6))
        root.addView(card, Ui.lp(this, top = 18))

        resume = Ui.button(this, "💬  Reprendre la conversation en cours", Ui.BLUE) {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        root.addView(resume, Ui.lp(this, top = 18))

        root.addView(Ui.button(this, "📶  Échanger par Wi-Fi", Ui.GREEN) {
            save(name); startActivity(Intent(this, WifiActivity::class.java))
        }, Ui.lp(this, top = 18))
        root.addView(Ui.text(this, "Même réseau Wi-Fi, ou un téléphone connecté au point d'accès (hotspot) de l'autre. Portée et qualité d'appel meilleures.", 12f, Ui.MUTED), Ui.lp(this, top = 4))

        root.addView(Ui.button(this, "🔵  Échanger par Bluetooth", Ui.BLUE) {
            save(name); startActivity(Intent(this, BluetoothActivity::class.java))
        }, Ui.lp(this, top = 16))
        root.addView(Ui.text(this, "Sans aucun réseau, à une dizaine de mètres. Transfert de fichiers plus lent.", 12f, Ui.MUTED), Ui.lp(this, top = 4))

        val help = Ui.card(this)
        help.addView(Ui.text(this, "Comment ça marche", 16f, Ui.GOLD, true))
        help.addView(Ui.text(this,
            "1. Installez BS CALLMagic sur les deux téléphones.\n" +
            "2. Choisissez le même mode (Wi-Fi ou Bluetooth) sur les deux.\n" +
            "3. Sur l'un des deux, touchez le nom de l'autre appareil dans la liste.\n" +
            "4. Écrivez, envoyez des fichiers 📎 ou appelez 📞.\n\n" +
            "Les fichiers reçus sont rangés dans Téléchargements/BS CALLMagic.", 13f, Ui.TEXT), Ui.lp(this, top = 6))
        root.addView(help, Ui.lp(this, top = 22))

        root.addView(Ui.center(Ui.text(this, "BSINALY · 0779687995 · v1.0", 12f, Ui.MUTED)), Ui.lp(this, top = 20))

        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        resume.visibility = if (Link.connected) View.VISIBLE else View.GONE
    }

    private fun save(e: EditText) {
        if (e.text.isNotBlank()) Prefs.setName(this, e.text.toString())
    }
}
