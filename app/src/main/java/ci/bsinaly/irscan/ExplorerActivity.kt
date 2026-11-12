package ci.bsinaly.irscan

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Explorateur de touches : parcourt toutes les commandes du protocole/adresse de l'appareil.
 * Quand l'appareil réagit (volume, chaîne, menu…), on assigne la commande à une touche.
 */
class ExplorerActivity : Activity() {

    private lateinit var ir: IrTransmitter
    private lateinit var store: DeviceStore
    private lateinit var device: Device
    private lateinit var family: CodeFamily
    private val handler = Handler(Looper.getMainLooper())

    private var command = -1
    private var auto = false
    private var generation = 0
    private lateinit var cmdText: TextView
    private lateinit var infoText: TextView
    private lateinit var autoBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ir = IrTransmitter(this)
        ir.onError = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        store = DeviceStore(this)
        val d = store.get(intent.getStringExtra(RemoteActivity.EXTRA_ID))
        val f = d?.let { familyOf(it) }
        if (d == null || f == null) {
            Toast.makeText(this, "Exploration impossible : protocole non reconnu", Toast.LENGTH_LONG).show()
            finish(); return
        }
        device = d; family = f
        render()
    }

    override fun onPause() { super.onPause(); stopAuto() }

    override fun onDestroy() { super.onDestroy(); stopAuto(); if (::ir.isInitialized) ir.onError = null }

    private fun render() {
        val root = Ui.screen(this)
        root.addView(Ui.title(this, "🧭 Explorateur de touches"))
        root.addView(Ui.text(this, "${device.name} · ${family.prefix.replace(":", " · adr ")}", 13f, Ui.MUTED))

        root.addView(Ui.text(this,
            "Le téléphone essaie les commandes une par une. Dès que l'appareil fait quelque chose (volume, chaîne, menu…), appuyez sur « Assigner » et choisissez la touche correspondante. La commande Marche/Arrêt est sautée automatiquement.",
            13f, Ui.MUTED), Ui.lp(this, top = 10))

        val card = Ui.card(this)
        cmdText = Ui.center(Ui.text(this, "—", 30f, Ui.TEXT, true))
        infoText = Ui.center(Ui.text(this, "Appuyez sur ▶ pour commencer", 13f, Ui.MUTED))
        card.addView(cmdText); card.addView(infoText, Ui.lp(this, top = 4))
        root.addView(card, Ui.lp(this, top = 14))

        val row = Ui.horizontal(this)
        row.addView(Ui.button(this, "◀", Ui.GREY) { stopAuto(); go(-1) }, Ui.weighted(this))
        row.addView(Ui.button(this, "↻", Ui.BLUE) { stopAuto(); resend() }, Ui.weighted(this))
        row.addView(Ui.button(this, "▶", Ui.GREY) { stopAuto(); go(+1) }, Ui.weighted(this))
        root.addView(row, Ui.lp(this, top = 10))

        autoBtn = Ui.button(this, "⏵ Défilement automatique", Ui.AMBER) { if (auto) stopAuto() else startAuto() }
        root.addView(autoBtn, Ui.lp(this, top = 6))

        root.addView(Ui.button(this, "✅  Assigner cette commande à une touche", Ui.GREEN) { assign() },
            android.widget.LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 70)).apply { topMargin = Ui.dp(this@ExplorerActivity, 14) })

        root.addView(Ui.button(this, "Aller à une commande…", Ui.CARD) { jump() }, Ui.lp(this, top = 10))
        root.addView(Ui.button(this, "Terminer", Ui.CARD) { finish() }, Ui.lp(this))
    }

    private fun powerCommand(): Int? = device.codes()["POWER"]?.let { family.commandOf(it) }

    private fun go(delta: Int, done: (() -> Unit)? = null) {
        var c = command
        val power = powerCommand()
        for (i in 0..family.maxCommand) {
            c = if (c < 0 && delta > 0) 0 else Math.floorMod(c + delta, family.maxCommand + 1)
            if (c != power) break
        }
        command = c
        resend(done)
    }

    private fun resend(done: (() -> Unit)? = null) {
        if (command < 0) { go(+1, done); return }
        val spec = family.spec(command)
        cmdText.text = "0x%02X".format(command)
        val assigned = device.codes().filterValues { it.equals(spec, true) }.keys.joinToString { Keys.label(it) }
        infoText.text = "Commande ${command} / ${family.maxCommand}" + if (assigned.isNotEmpty()) "  ·  déjà : $assigned" else ""
        ir.send(spec, done = done)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show(); done?.invoke() }
    }

    private fun startAuto() {
        auto = true
        autoBtn.text = "⏸ Arrêter le défilement"
        val g = ++generation
        tick(g)
    }

    private fun tick(g: Int) {
        if (!auto || g != generation) return
        val c = command
        go(+1) {
            if (auto && g == generation) handler.postDelayed({ tick(g) }, 900)
        }
        if (command <= c && c >= 0) {
            stopAuto()
            Toast.makeText(this, "Toutes les commandes ont été essayées", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAuto() {
        auto = false
        generation++
        handler.removeCallbacksAndMessages(null)
        if (::autoBtn.isInitialized) autoBtn.text = "⏵ Défilement automatique"
    }

    private fun assign() {
        stopAuto()
        if (command < 0) { Toast.makeText(this, "Envoyez d'abord une commande", Toast.LENGTH_SHORT).show(); return }
        val keys = Keys.layout(device.type).flatten().filter { it.isNotEmpty() }.distinct()
        val codes = device.codes()
        val labels = keys.map { k -> (if (codes.containsKey(k)) "✓ " else "") + Keys.label(k) }.toTypedArray()
        val spec = family.spec(command)
        AlertDialog.Builder(this)
            .setTitle("0x%02X fait quoi ?".format(command))
            .setItems(labels) { _, which ->
                device.custom[keys[which]] = spec
                store.upsert(device)
                Toast.makeText(this, "${Keys.label(keys[which])} enregistrée", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun jump() {
        stopAuto()
        val input = EditText(this).apply {
            hint = "Numéro en hexadécimal (ex : 1A)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        AlertDialog.Builder(this).setTitle("Aller à la commande").setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                if (v == null || v < 0 || v > family.maxCommand) Toast.makeText(this, "Valeur invalide", Toast.LENGTH_SHORT).show()
                else { command = v; resend() }
            }.setNegativeButton("Annuler", null).show()
    }

    companion object {
        fun familyOf(d: Device): CodeFamily? {
            val codes = d.codes()
            return CodeFamily.of(codes["POWER"]) ?: codes.values.firstNotNullOfOrNull { CodeFamily.of(it) }
        }
    }
}
