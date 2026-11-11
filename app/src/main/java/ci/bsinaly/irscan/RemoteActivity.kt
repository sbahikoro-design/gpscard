package ci.bsinaly.irscan

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Télécommande de l'appareil.
 *  - Appui court : émet le code de la touche
 *  - Appui long  : saisir / remplacer le code de la touche (Pronto, NEC, Samsung, Sony, RC5, brut)
 * Les touches sans code apparaissent grisées.
 */
class RemoteActivity : Activity() {

    companion object { const val EXTRA_ID = "device_id" }

    private lateinit var ir: IrTransmitter
    private lateinit var store: DeviceStore
    private lateinit var device: Device

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = IrTransmitter(this)
        store = DeviceStore(this)
        val d = store.get(intent.getStringExtra(EXTRA_ID))
        if (d == null) { finish(); return }
        device = d
        render()
    }

    private fun render() {
        val root = Ui.screen(this)
        root.addView(Ui.title(this, "${device.type.icon} ${device.name}"))
        root.addView(Ui.text(this, "${device.type.label} · ${device.brand}" +
            if (!ir.hasEmitter) "  ·  mode simulation" else "", 13f, Ui.MUTED))

        val codes = device.codes()
        for (row in Keys.layout(device.type)) {
            val line = Ui.horizontal(this)
            for (key in row) {
                if (key.isEmpty()) {
                    line.addView(View(this), Ui.weighted(this)); continue
                }
                val has = codes.containsKey(key)
                val color = when {
                    !has -> Ui.DIM
                    key == "POWER" -> Ui.RED
                    key == "OK" -> Ui.BLUE
                    else -> Ui.GREY
                }
                val b = Ui.button(this, Keys.label(key), color) { press(key) }
                if (!has) b.setTextColor(Ui.MUTED)
                b.setOnLongClickListener { editCode(key); true }
                line.addView(b, Ui.weighted(this))
            }
            root.addView(line, Ui.lp(this, top = 2))
        }

        root.addView(Ui.text(this,
            "Appui long sur une touche pour saisir ou corriger son code. Formats : NEC:04:08 · SAMSUNG:07:02 · SONY12:01:15 · RC5:00:0C · PRONTO:0000 006D … · RAW:38000:9000,4500,…",
            12f, Ui.MUTED), Ui.lp(this, top = 18))

        val row = Ui.horizontal(this)
        row.addView(Ui.button(this, "✎ Renommer", Ui.CARD) { rename() }, Ui.weighted(this))
        row.addView(Ui.button(this, "⇄ Type", Ui.CARD) { changeType() }, Ui.weighted(this))
        root.addView(row, Ui.lp(this, top = 12))
    }

    private fun press(key: String) {
        val code = device.codes()[key]
        if (code == null) { editCode(key); return }
        ir.send(code)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun editCode(key: String) {
        val current = device.codes()[key] ?: ""
        val input = EditText(this).apply {
            setText(current)
            hint = "Ex : PRONTO:0000 006D 0022 0002 0156 00AB …"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@RemoteActivity, 20), Ui.dp(this@RemoteActivity, 8), Ui.dp(this@RemoteActivity, 20), 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Code de la touche « ${Keys.label(key)} »")
            .setView(box)
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler", null)
        if (device.custom.containsKey(key)) builder.setNeutralButton("Supprimer") { _, _ ->
            device.custom.remove(key); store.upsert(device); render()
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val spec = input.text.toString().trim().replace("\n", " ")
                val error = try { IrEncoder.encode(spec); null } catch (e: Exception) { e.message ?: "Code invalide" }
                if (error != null) input.error = error
                else {
                    device.custom[key] = spec
                    store.upsert(device)
                    ir.send(spec) // test immédiat
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun rename() {
        val input = EditText(this).apply { setText(device.name); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("Renommer").setView(input)
            .setPositiveButton("OK") { _, _ ->
                device.name = input.text.toString().ifBlank { device.name }
                store.upsert(device); render()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun changeType() {
        val types = DeviceType.values()
        AlertDialog.Builder(this).setTitle("Type d'appareil")
            .setSingleChoiceItems(types.map { "${it.icon}  ${it.label}" }.toTypedArray(), types.indexOf(device.type)) { dlg, which ->
                device.type = types[which]
                store.upsert(device); dlg.dismiss(); render()
            }.show()
    }
}
