package ci.bsinaly.irscan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

/**
 * Balayage :
 *  1. Préparer : mode (rapide / profond / exhaustif), vitesse, puissance d'émission
 *  2. Balayer : le téléphone émet tour à tour les codes « Marche/Arrêt »
 *  3. Affiner : renvoyer un à un les derniers codes pour isoler le bon
 *  4. Identifier : type + nom, puis télécommande
 */
class ScanActivity : Activity() {

    private enum class Speed(val label: String, val pauseMs: Long) {
        LENT("Lente (2,5 s)", 2500), NORMALE("Normale (1,5 s)", 1500), RAPIDE("Rapide (0,6 s)", 600)
    }

    private lateinit var ir: IrTransmitter
    private lateinit var store: DeviceStore
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var candidates: List<Candidate> = emptyList()
    private var mode = ScanMode.MARQUES
    private var speed = Speed.NORMALE
    private var index = -1
    private var running = false
    private var generation = 0
    private val history = ArrayList<Int>() // indices envoyés, le plus récent en premier

    private var stepText: TextView? = null
    private var brandText: TextView? = null
    private var etaText: TextView? = null
    private var progress: ProgressBar? = null
    private var pauseBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ir = IrTransmitter(this)
        ir.onError = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        store = DeviceStore(this)
        root = Ui.screen(this)
        showPrepare()
    }

    override fun onPause() {
        super.onPause()
        stop()
        pauseBtn?.text = "▶ Reprendre"
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
        ir.onError = null
    }

    // ================= Étape 1 : préparation =================
    private fun showPrepare() {
        stop()
        clearRefs()
        root.removeAllViews()
        root.addView(Ui.title(this, "🔍 Balayage de la zone"))

        val tips = Ui.card(this)
        tips.addView(Ui.text(this, "Pour une détection maximale", 16f, Ui.TEXT, true))
        tips.addView(Ui.text(this,
            "• Allumez l'appareil et pointez le HAUT du téléphone (la diode IR) droit vers sa façade.\n" +
            "• Restez à 1–3 m, sans obstacle ; évitez le plein soleil et les lampes vives sur l'appareil.\n" +
            "• Dès que l'appareil s'éteint ou réagit, appuyez sur « L'appareil a réagi ».",
            14f, Ui.MUTED), Ui.lp(this, top = 6))
        root.addView(tips, Ui.lp(this, top = 14))

        // Mode
        root.addView(Ui.text(this, "Étendue du balayage", 15f, Ui.TEXT, true), Ui.lp(this, top = 18))
        val modes = ScanMode.values()
        val modeGroup = radioGroup(modes.map {
            "${it.label}  (${CodeDatabase.candidates(it).size} codes)\n${it.detail}"
        }, modes.indexOf(mode), 3000)
        root.addView(modeGroup, Ui.lp(this, top = 4))

        // Vitesse
        root.addView(Ui.text(this, "Pause entre deux codes", 15f, Ui.TEXT, true), Ui.lp(this, top = 14))
        val speeds = Speed.values()
        val speedGroup = radioGroup(speeds.map { it.label }, speeds.indexOf(speed), 4000)
        root.addView(speedGroup, Ui.lp(this, top = 4))

        // Puissance
        root.addView(Ui.text(this, "Puissance d'émission", 15f, Ui.TEXT, true), Ui.lp(this, top = 14))
        val powers = IrTransmitter.Power.values()
        val powerGroup = radioGroup(powers.map { "${it.label} — ${it.detail}" }, powers.indexOf(ir.power), 5000)
        root.addView(powerGroup, Ui.lp(this, top = 4))

        root.addView(Ui.button(this, "▶  Démarrer le balayage", Ui.GREEN) {
            mode = modes[(modeGroup.checkedRadioButtonId - 3000).coerceIn(0, modes.size - 1)]
            speed = speeds[(speedGroup.checkedRadioButtonId - 4000).coerceIn(0, speeds.size - 1)]
            ir.power = powers[(powerGroup.checkedRadioButtonId - 5000).coerceIn(0, powers.size - 1)]
            candidates = CodeDatabase.candidates(mode)
            history.clear()
            showScanning(-1)
        }, Ui.lp(this, top = 18))

        if (!ir.hasEmitter) root.addView(Ui.text(this,
            "⚠ Pas d'émetteur IR sur ce téléphone : balayage simulé.", 13f, Ui.AMBER), Ui.lp(this, top = 12))
    }

    private fun radioGroup(labels: List<String>, checked: Int, baseId: Int): RadioGroup {
        val g = RadioGroup(this)
        labels.forEachIndexed { i, l ->
            g.addView(RadioButton(this).apply {
                id = baseId + i
                text = l; setTextColor(Ui.TEXT); textSize = 14f
                setPadding(paddingLeft, Ui.dp(this@ScanActivity, 4), paddingRight, Ui.dp(this@ScanActivity, 4))
            })
        }
        g.check(baseId + checked.coerceAtLeast(0))
        return g
    }

    // ================= Étape 2 : balayage =================
    private fun showScanning(startAfter: Int) {
        stop()
        root.removeAllViews()
        root.addView(Ui.title(this, "Balayage en cours…"))
        root.addView(Ui.text(this, "${mode.label} · puissance ${ir.power.label.lowercase()}", 13f, Ui.MUTED))

        val card = Ui.card(this)
        val st = Ui.center(Ui.text(this, "", 14f, Ui.MUTED))
        val br = Ui.center(Ui.text(this, "", 21f, Ui.TEXT, true))
        val eta = Ui.center(Ui.text(this, "", 12f, Ui.MUTED))
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = candidates.size }
        card.addView(st); card.addView(br, Ui.lp(this, top = 6))
        card.addView(pb, Ui.lp(this, top = 12)); card.addView(eta, Ui.lp(this, top = 4))
        root.addView(card, Ui.lp(this, top = 14))
        stepText = st; brandText = br; etaText = eta; progress = pb

        root.addView(Ui.button(this, "✅  L'appareil a réagi", Ui.GREEN) { showRefine() },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 80)).apply { topMargin = Ui.dp(this@ScanActivity, 16) })

        val row = Ui.horizontal(this)
        row.addView(Ui.button(this, "◀ Préc.", Ui.GREY) { manualStep(-1) }, Ui.weighted(this))
        val pause = Ui.button(this, "⏸ Pause", Ui.AMBER) { togglePause() }
        pauseBtn = pause
        row.addView(pause, Ui.weighted(this))
        row.addView(Ui.button(this, "Suiv. ▶", Ui.GREY) { manualStep(+1) }, Ui.weighted(this))
        root.addView(row, Ui.lp(this, top = 8))

        root.addView(Ui.button(this, "Changer les réglages", Ui.CARD) { showPrepare() }, Ui.lp(this, top = 8))
        root.addView(Ui.text(this,
            "Pas d'inquiétude si vous appuyez un peu tard : l'écran suivant permet de renvoyer un à un les derniers codes pour trouver le bon.",
            12f, Ui.MUTED), Ui.lp(this, top = 12))

        index = startAfter
        if (index >= 0) showCandidate(index)
        start(400)
    }

    private fun start(delay: Long) {
        running = true
        val g = ++generation
        handler.postDelayed({ step(g) }, delay)
    }

    /** Envoie le code suivant puis programme le prochain une fois l'émission terminée. */
    private fun step(g: Int) {
        if (!running || g != generation) return
        if (index + 1 >= candidates.size) { endOfCycle(); return }
        sendAt(index + 1) {
            if (running && g == generation) handler.postDelayed({ step(g) }, speed.pauseMs)
        }
    }

    private fun sendAt(i: Int, done: (() -> Unit)? = null) {
        index = i
        showCandidate(i)
        history.remove(i); history.add(0, i)
        if (history.size > 12) history.removeAt(history.size - 1)
        val err = ir.send(candidates[i].spec, done = done)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            done?.invoke()
        }
    }

    private fun showCandidate(i: Int) {
        val c = candidates[i]
        stepText?.text = "Code ${i + 1} / ${candidates.size}" + (c.type?.let { "  ·  ${it.icon} ${it.label}" } ?: "")
        brandText?.text = c.label
        progress?.progress = i + 1
        val left = candidates.size - i - 1
        val secs = left * (speed.pauseMs + 350) / 1000
        etaText?.text = if (left > 0) "Reste environ ${secs / 60} min ${secs % 60} s" else "Dernier code"
    }

    private fun togglePause() {
        if (running) stop()
        else {
            if (index + 1 >= candidates.size) index = -1
            start(300)
        }
        pauseBtn?.text = if (running) "⏸ Pause" else "▶ Reprendre"
    }

    private fun manualStep(delta: Int) {
        stop()
        pauseBtn?.text = "▶ Reprendre"
        if (candidates.isEmpty()) return
        sendAt((if (index < 0) 0 else index + delta).coerceIn(0, candidates.size - 1))
    }

    private fun stop() {
        running = false
        generation++
        handler.removeCallbacksAndMessages(null)
    }

    private fun clearRefs() {
        stepText = null; brandText = null; etaText = null; progress = null; pauseBtn = null
    }

    private fun endOfCycle() {
        stop()
        clearRefs()
        root.removeAllViews()
        root.addView(Ui.title(this, "Aucune réaction ?"))
        val next = when (mode) {
            ScanMode.MARQUES -> "Essayez le mode « Profond » : il couvre les décodeurs et appareils génériques."
            ScanMode.PROFOND -> "Essayez le mode « Exhaustif » avec la puissance « Maximale »."
            ScanMode.EXHAUSTIF -> "L'appareil utilise sans doute un protocole propre (ex. climatiseur). Ajoutez-le manuellement et saisissez ses codes au format Pronto."
        }
        root.addView(Ui.text(this,
            "Les ${candidates.size} codes ont été envoyés.\n\n• Rapprochez-vous (1 m) et visez bien la façade.\n• Vérifiez avec le détecteur caméra que l'émetteur fonctionne (à l'aide d'un 2ᵉ téléphone).\n• $next",
            14f, Ui.MUTED), Ui.lp(this, top = 10))
        if (history.isNotEmpty()) root.addView(Ui.button(this, "Revoir les derniers codes envoyés", Ui.BLUE) { showRefine() }, Ui.lp(this, top = 16))
        root.addView(Ui.button(this, "Changer les réglages et recommencer", Ui.GREEN) { showPrepare() }, Ui.lp(this, top = 10))
        root.addView(Ui.button(this, "Retour à l'accueil", Ui.CARD) { finish() }, Ui.lp(this))
    }

    // ================= Étape 3 : affinage =================
    private fun showRefine() {
        stop()
        clearRefs()
        if (history.isEmpty()) { Toast.makeText(this, "Aucun code envoyé pour l'instant", Toast.LENGTH_SHORT).show(); showScanning(index); return }
        root.removeAllViews()
        root.addView(Ui.title(this, "Quel code a fonctionné ?"))
        root.addView(Ui.text(this,
            "Remettez l'appareil dans son état initial si besoin, puis touchez « ↻ » sur chaque ligne, du plus récent au plus ancien. Quand l'appareil réagit, touchez « C'est lui ».",
            14f, Ui.MUTED), Ui.lp(this, top = 8))

        history.forEachIndexed { pos, i ->
            val c = candidates[i]
            val line = Ui.horizontal(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.CARD, Ui.dp(this@ScanActivity, 12).toFloat())
                setPadding(Ui.dp(this@ScanActivity, 12), Ui.dp(this@ScanActivity, 6), Ui.dp(this@ScanActivity, 6), Ui.dp(this@ScanActivity, 6))
            }
            val info = Ui.vertical(this)
            info.addView(Ui.text(this, c.label, 15f, Ui.TEXT, true))
            info.addView(Ui.text(this, (if (pos == 0) "dernier envoyé · " else "") + (c.type?.label ?: "appareil générique"), 12f, Ui.MUTED))
            line.addView(info, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            line.addView(Ui.button(this, "↻", Ui.BLUE) {
                ir.send(c.spec)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 50)).apply { rightMargin = Ui.dp(this@ScanActivity, 6) })
            line.addView(Ui.button(this, "C'est lui", Ui.GREEN) { showIdentify(c) },
                LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 50)))
            root.addView(line, Ui.lp(this, top = 8))
        }

        root.addView(Ui.button(this, "Aucun : reprendre le balayage", Ui.GREY) { showScanning(index) }, Ui.lp(this, top = 16))
    }

    // ================= Étape 4 : identification =================
    private fun showIdentify(c: Candidate) {
        stop()
        root.removeAllViews()
        root.addView(Ui.title(this, "Appareil trouvé ✔"))

        val profile = CodeDatabase.byId(c.profileId)
        val card = Ui.card(this)
        card.addView(Ui.text(this, if (profile != null) "Marque reconnue" else "Code générique", 13f, Ui.MUTED))
        card.addView(Ui.text(this, c.label, 21f, Ui.TEXT, true))
        card.addView(Ui.text(this,
            if (profile != null) "${profile.codes.size} touches déjà connues"
            else "Seule la touche Marche/Arrêt est connue : l'explorateur de touches trouvera les autres.",
            12f, Ui.MUTED))
        root.addView(card, Ui.lp(this, top = 12))

        val testKey = listOf("VOL_UP", "MUTE", "CH_UP").firstOrNull { profile?.codes?.containsKey(it) == true }
        if (testKey != null && profile != null) {
            root.addView(Ui.button(this, "Confirmer avec : ${Keys.label(testKey)}", Ui.BLUE) {
                ir.send(profile.codes.getValue(testKey))
            }, Ui.lp(this, top = 10))
        }

        root.addView(Ui.text(this, "Type d'appareil", 15f, Ui.TEXT, true), Ui.lp(this, top = 18))
        val types = DeviceType.values()
        val group = radioGroup(types.map { "${it.icon}  ${it.label}" }, types.indexOf(c.type ?: DeviceType.TV), 2000)
        root.addView(group, Ui.lp(this, top = 4))

        root.addView(Ui.text(this, "Nom", 15f, Ui.TEXT, true), Ui.lp(this, top = 14))
        val name = EditText(this).apply {
            setText(if (profile != null) "${(c.type ?: DeviceType.TV).label} ${profile.brand}" else "")
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            hint = "Ex : TV salon, Décodeur bureau, Ventilateur"
        }
        root.addView(name, Ui.lp(this, top = 4))

        root.addView(Ui.button(this, "💾  Enregistrer et contrôler", Ui.GREEN) {
            val type = types[(group.checkedRadioButtonId - 2000).coerceIn(0, types.size - 1)]
            val brand = profile?.brand ?: c.label
            val d = Device(store.newId(), name.text.toString().ifBlank { "${type.label} ${profile?.brand ?: ""}".trim() },
                type, c.profileId, brand)
            if (profile == null) d.custom["POWER"] = c.spec
            store.upsert(d)
            startActivity(Intent(this, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, d.id))
            finish()
        }, Ui.lp(this, top = 18))
        root.addView(Ui.button(this, "Retour à la liste des codes", Ui.GREY) { showRefine() }, Ui.lp(this))
    }
}
