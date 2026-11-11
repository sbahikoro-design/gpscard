package ci.bsinaly.irscan

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout

/** Accueil : état de l'émetteur IR, lancement du balayage, appareils enregistrés. */
class MainActivity : Activity() {

    private lateinit var ir: IrTransmitter
    private lateinit var store: DeviceStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = IrTransmitter(this)
        store = DeviceStore(this)

        val root = Ui.screen(this)
        root.addView(Ui.title(this, "BS IR SCAN"))
        root.addView(Ui.text(this, "Télécommande universelle infrarouge", 14f, Ui.MUTED))

        // État de l'émetteur
        val status = Ui.card(this)
        if (ir.hasEmitter) {
            status.addView(Ui.text(this, "✅ Émetteur infrarouge détecté", 16f, Ui.TEXT, true))
            status.addView(Ui.text(this, "Fréquences prises en charge : ${ir.frequencyInfo()}", 13f, Ui.MUTED))
        } else {
            status.addView(Ui.text(this, "⚠ Aucun émetteur infrarouge sur ce téléphone", 16f, Ui.AMBER, true))
            status.addView(Ui.text(this, "L'application fonctionne en mode simulation (vibration seulement) : utile pour tester l'interface.", 13f, Ui.MUTED))
        }
        root.addView(status, Ui.lp(this, top = 16))

        root.addView(Ui.button(this, "🔍  Balayer la zone", Ui.GREEN) {
            startActivity(Intent(this, ScanActivity::class.java))
        }, Ui.lp(this, top = 18))
        root.addView(Ui.button(this, "➕  Ajouter un appareil manuellement", Ui.GREY) { addManual() }, Ui.lp(this))

        root.addView(Ui.text(this, "Mes appareils", 18f, Ui.TEXT, true), Ui.lp(this, top = 26))
        list = Ui.vertical(this)
        root.addView(list)

        root.addView(Ui.text(this, "Appui long sur un appareil pour le supprimer.", 12f, Ui.MUTED), Ui.lp(this, top = 14))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val devices = store.all()
        if (devices.isEmpty()) {
            list.addView(Ui.text(this, "Aucun appareil. Lancez un balayage en pointant le téléphone vers l'appareil à contrôler.", 14f, Ui.MUTED), Ui.lp(this))
            return
        }
        for (d in devices) {
            val b = Ui.button(this, "${d.type.icon}  ${d.name}   ·   ${d.brand}", Ui.CARD) { open(d.id) }
            b.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Supprimer « ${d.name} » ?")
                    .setPositiveButton("Supprimer") { _, _ -> store.delete(d.id); refresh() }
                    .setNegativeButton("Annuler", null).show()
                true
            }
            list.addView(b, Ui.lp(this, top = 8))
        }
    }

    private fun open(id: String) =
        startActivity(Intent(this, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, id))

    /** Création sans balayage : type + nom, les codes s'ajoutent ensuite touche par touche. */
    private fun addManual() {
        val types = DeviceType.values()
        AlertDialog.Builder(this)
            .setTitle("Type d'appareil")
            .setItems(types.map { "${it.icon}  ${it.label}" }.toTypedArray()) { _, which ->
                val type = types[which]
                val input = EditText(this).apply { setText(type.label); setSelection(text.length) }
                AlertDialog.Builder(this)
                    .setTitle("Nom de l'appareil")
                    .setView(input)
                    .setPositiveButton("Créer") { _, _ ->
                        val d = Device(store.newId(), input.text.toString().ifBlank { type.label }, type, null, "Personnalisé")
                        store.upsert(d)
                        open(d.id)
                    }
                    .setNegativeButton("Annuler", null).show()
            }.show()
    }
}
