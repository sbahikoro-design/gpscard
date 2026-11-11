package ci.bsinaly.irscan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

/**
 * Balayage en 3 étapes :
 *  1. Préparer (filtre facultatif par type)
 *  2. Balayer : le téléphone émet tour à tour le code « Marche/Arrêt » de chaque profil ;
 *     l'utilisateur appuie sur « L'appareil a réagi » dès que l'appareil répond
 *  3. Identifier : choisir l'appareil, définir son type et son nom, puis ouvrir la télécommande
 */
class ScanActivity : Activity() {

    companion object { const val INTERVAL_MS = 2000L }

    private lateinit var ir: IrTransmitter
    private lateinit var store: DeviceStore
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var candidates: List<Profile> = emptyList()
    private var index = -1
    private var running = false

    private lateinit var stepText: TextView
    private lateinit var brandText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pauseBtn: android.widget.Button

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (index + 1 >= candidates.size) { endOfCycle(); return }
            sendAt(index + 1)
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ir = IrTransmitter(this)
        store = DeviceStore(this)
        root = Ui.screen(this)
        showPrepare()
    }

    override fun onPause() {
        super.onPause()
        stop()
        if (::pauseBtn.isInitialized) pauseBtn.text = "▶ Reprendre"
    }

    // ---------------- Étape 1 : préparation ----------------
    private fun showPrepare() {
        stop()
        root.removeAllViews()
        root.addView(Ui.title(this, "🔍 Balayage de la zone"))

        val tips = Ui.card(this)
        tips.addView(Ui.text(this, "Avant de commencer", 16f, Ui.TEXT, true))
        tips.addView(Ui.text(this,
            "1. Allumez l'appareil à contrôler.\n" +
            "2. Pointez le haut du téléphone vers l'appareil, à 1–3 m, sans obstacle.\n" +
            "3. Le téléphone envoie des codes « Marche/Arrêt » toutes les ${INTERVAL_MS / 1000} s.\n" +
            "4. Dès que l'appareil s'éteint ou réagit, appuyez sur « L'appareil a réagi ».",
            14f, Ui.MUTED), Ui.lp(this, top = 6))
        root.addView(tips, Ui.lp(this, top = 16))

        root.addView(Ui.text(this, "Limiter le balayage à un type (facultatif)", 15f, Ui.TEXT, true), Ui.lp(this, top = 20))
        val group = RadioGroup(this)
        val typesInDb = CodeDatabase.profiles.map { it.type }.distinct()
        val options = listOf<DeviceType?>(null) + typesInDb
        options.forEachIndexed { i, t ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = if (t == null) "Tous les appareils (${CodeDatabase.profiles.size} codes)"
                else "${t.icon}  ${t.label} (${CodeDatabase.profiles.count { it.type == t }} codes)"
                setTextColor(Ui.TEXT); textSize = 15f
                isChecked = i == 0
            })
        }
        root.addView(group, Ui.lp(this, top = 6))

        root.addView(Ui.button(this, "▶  Démarrer le balayage", Ui.GREEN) {
            val t = options[(group.checkedRadioButtonId - 1000).coerceIn(0, options.size - 1)]
            candidates = if (t == null) CodeDatabase.profiles else CodeDatabase.profiles.filter { it.type == t }
            showScanning()
        }, Ui.lp(this, top = 18))

        if (!ir.hasEmitter) root.addView(Ui.text(this,
            "⚠ Pas d'émetteur IR : balayage simulé.", 13f, Ui.AMBER), Ui.lp(this, top = 12))
    }

    // ---------------- Étape 2 : balayage ----------------
    private fun showScanning(startAfter: Int = -1) {
        root.removeAllViews()
        root.addView(Ui.title(this, "Balayage en cours…"))

        val card = Ui.card(this)
        stepText = Ui.center(Ui.text(this, "", 14f, Ui.MUTED))
        brandText = Ui.center(Ui.text(this, "", 22f, Ui.TEXT, true))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = candidates.size }
        card.addView(stepText)
        card.addView(brandText, Ui.lp(this, top = 6))
        card.addView(progress, Ui.lp(this, top = 12))
        root.addView(card, Ui.lp(this, top = 16))

        root.addView(Ui.button(this, "✅  L'appareil a réagi", Ui.GREEN) { onDetected() },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 76)).apply { topMargin = Ui.dp(this@ScanActivity, 18) })

        val row = Ui.horizontal(this)
        row.addView(Ui.button(this, "◀ Précédent", Ui.GREY) { manualStep(-1) }, Ui.weighted(this))
        pauseBtn = Ui.button(this, "⏸ Pause", Ui.AMBER) { togglePause() }
        row.addView(pauseBtn, Ui.weighted(this))
        row.addView(Ui.button(this, "Suivant ▶", Ui.GREY) { manualStep(+1) }, Ui.weighted(this))
        root.addView(row, Ui.lp(this, top = 10))

        root.addView(Ui.button(this, "Annuler", Ui.CARD) { showPrepare() }, Ui.lp(this, top = 10))
        root.addView(Ui.text(this,
            "Astuce : l'appareil réagit parfois avec un léger retard. Mettez en pause puis utilisez « Précédent » pour renvoyer le code précédent et confirmer.",
            12f, Ui.MUTED), Ui.lp(this, top = 14))

        index = startAfter
        running = true
        handler.postDelayed(tick, 600)
    }

    private fun sendAt(i: Int) {
        index = i
        val p = candidates[i]
        stepText.text = "Code ${i + 1} / ${candidates.size}  ·  ${p.type.icon} ${p.type.label}"
        brandText.text = p.brand
        progress.progress = i + 1
        val code = p.codes["POWER"]
        val err = if (code == null) "Pas de code Marche/Arrêt" else ir.send(code)
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
    }

    private fun togglePause() {
        if (running) stop()
        else {
            if (index + 1 >= candidates.size) index = -1
            running = true
            handler.postDelayed(tick, 300)
        }
        pauseBtn.text = if (running) "⏸ Pause" else "▶ Reprendre"
    }

    private fun manualStep(delta: Int) {
        stop()
        pauseBtn.text = "▶ Reprendre"
        val target = (if (index < 0) 0 else index + delta).coerceIn(0, candidates.size - 1)
        sendAt(target)
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun endOfCycle() {
        stop()
        root.removeAllViews()
        root.addView(Ui.title(this, "Aucune réaction ?"))
        root.addView(Ui.text(this,
            "Les ${candidates.size} codes ont été envoyés. Vérifiez la distance et l'orientation, puis recommencez. " +
            "Si l'appareil ne figure pas dans la base, ajoutez-le manuellement et saisissez ses codes (format Pronto, NEC…).",
            14f, Ui.MUTED), Ui.lp(this, top = 10))
        root.addView(Ui.button(this, "🔁  Recommencer le balayage", Ui.GREEN) { showScanning() }, Ui.lp(this, top = 18))
        root.addView(Ui.button(this, "Changer de filtre", Ui.GREY) { showPrepare() }, Ui.lp(this))
        root.addView(Ui.button(this, "Retour à l'accueil", Ui.CARD) { finish() }, Ui.lp(this))
    }

    // ---------------- Étape 3 : identification ----------------
    private fun onDetected() {
        stop()
        if (index < 0) { Toast.makeText(this, "Aucun code envoyé pour l'instant", Toast.LENGTH_SHORT).show(); return }
        val p = candidates[index]
        root.removeAllViews()
        root.addView(Ui.title(this, "Appareil trouvé"))

        val card = Ui.card(this)
        card.addView(Ui.text(this, "Profil correspondant", 13f, Ui.MUTED))
        card.addView(Ui.text(this, "${p.brand}", 22f, Ui.TEXT, true))
        card.addView(Ui.text(this, "${p.codes.size} touches connues · ${p.codes["POWER"]}", 12f, Ui.MUTED))
        root.addView(card, Ui.lp(this, top = 14))

        // Test de confirmation
        val testKey = listOf("VOL_UP", "MUTE", "CH_UP").firstOrNull { p.codes.containsKey(it) }
        if (testKey != null) {
            root.addView(Ui.button(this, "Tester : ${Keys.label(testKey)}", Ui.BLUE) {
                ir.send(p.codes.getValue(testKey))?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            }, Ui.lp(this, top = 12))
        }

        root.addView(Ui.text(this, "Type d'appareil", 15f, Ui.TEXT, true), Ui.lp(this, top = 20))
        val group = RadioGroup(this)
        val types = DeviceType.values()
        types.forEachIndexed { i, t ->
            group.addView(RadioButton(this).apply {
                id = 2000 + i; text = "${t.icon}  ${t.label}"
                setTextColor(Ui.TEXT); textSize = 15f
                isChecked = t == p.type
            })
        }
        root.addView(group, Ui.lp(this, top = 4))

        root.addView(Ui.text(this, "Nom", 15f, Ui.TEXT, true), Ui.lp(this, top = 16))
        val name = EditText(this).apply {
            setText("${p.type.label} ${p.brand}")
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            hint = "Ex : TV salon, Projecteur salle de réunion"
        }
        root.addView(name, Ui.lp(this, top = 4))

        root.addView(Ui.button(this, "💾  Enregistrer et contrôler", Ui.GREEN) {
            val type = types[(group.checkedRadioButtonId - 2000).coerceIn(0, types.size - 1)]
            val d = Device(store.newId(), name.text.toString().ifBlank { "${type.label} ${p.brand}" }, type, p.id, p.brand)
            store.upsert(d)
            startActivity(Intent(this, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, d.id))
            finish()
        }, Ui.lp(this, top = 18))

        root.addView(Ui.button(this, "Ce n'est pas le bon : reprendre le balayage", Ui.GREY) {
            showScanning(index)
        }, Ui.lp(this))
    }
}
