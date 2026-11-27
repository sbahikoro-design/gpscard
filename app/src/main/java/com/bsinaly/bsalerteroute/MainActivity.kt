package com.bsinaly.bsalerteroute

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var alerter: Alerter
    private val h = Handler(Looper.getMainLooper())

    private lateinit var gpsChip: TextView
    private lateinit var speedTv: TextView
    private lateinit var countTv: TextView
    private lateinit var banner: TextView
    private lateinit var radar: RadarView
    private lateinit var nextTv: TextView
    private lateinit var runBtn: TextView
    private lateinit var markBtn: TextView
    private lateinit var lastTv: TextView
    private lateinit var undoBtn: TextView
    private lateinit var listBtn: TextView
    private val chips = mutableListOf<TextView>()

    private var lastMarkedId = -1L
    private var lastMarkedNew = false
    private var seenVersion = -1
    private var cache: List<Obstacle> = emptyList()
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        alerter = Alerter(this)
        setContentView(build())
    }

    // ------------------------------------------------------------------ écran

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }

        // En-tête
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(this).apply { background = getDrawable(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        ht.addView(tv("BS ALERTE ROUTE", 24f, C.JAUNE, true))
        ht.addView(tv("Pointez les obstacles une fois, l'appli vous prévient à chaque passage.", 13f, C.GREY))
        head.addView(ht, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 1f))
        root.addView(head)

        // Ligne d'état
        val stat = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gpsChip = chipTv(); speedTv = chipTv(); countTv = chipTv()
        stat.addView(gpsChip, lp(0, weight = 1.2f))
        stat.addView(speedTv, lp(0, weight = 1f).apply { marginStart = dp(8) })
        stat.addView(countTv, lp(0, weight = 1f).apply { marginStart = dp(8) })
        root.addView(stat, lp(top = dp(16)))

        // Bandeau d'alerte
        banner = tv("", 22f, android.graphics.Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = box(C.ROUGE, 16); setPadding(dp(12), dp(14), dp(12), dp(14))
            visibility = View.GONE
        }
        root.addView(banner, lp(top = dp(12)))

        // Radar
        radar = RadarView(this)
        root.addView(radar, lp(h = dp(250), top = dp(12)))
        nextTv = tv("", 15f, C.TEXT, true).apply { gravity = Gravity.CENTER }
        root.addView(nextTv, lp(top = dp(8)))

        // Démarrer / arrêter
        runBtn = button("DÉMARRER LA SURVEILLANCE", C.OK, C.BG, 18f) { toggleRun() }
        root.addView(runBtn, lp(top = dp(14)))

        // Type d'obstacle
        root.addView(tv("Type d'obstacle à pointer", 14f, C.GREY), lp(top = dp(20)))
        var row: LinearLayout? = null
        Types.ALL.forEachIndexed { i, t ->
            if (i % 3 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; root.addView(row, lp(top = dp(8))) }
            val c = tv(t.label, 13f, C.TEXT, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(4), dp(10)); minHeight = dp(46)
                setOnClickListener { prefs.lastType = t.code; paintChips(); markBtn.text = markLabel() }
            }
            chips.add(c)
            row!!.addView(c, lp(0, weight = 1f).apply { if (i % 3 != 0) marginStart = dp(8) })
        }
        paintChips()

        // Bouton géant de pointage
        markBtn = button(markLabel(), C.JAUNE, C.BG, 24f) { mark() }.apply {
            minHeight = dp(118); background = box(C.JAUNE, 22, 4, C.ROUGE)
        }
        root.addView(markBtn, lp(top = dp(14)))

        val undoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lastTv = tv("Astuce : appuyez sur le bouton volume – du téléphone pour pointer sans regarder l'écran.", 13f, C.GREY)
        undoRow.addView(lastTv, lp(0, weight = 1f))
        undoBtn = button("Annuler", C.CARD2, C.TEXT, 14f) { undo() }.apply { visibility = View.GONE }
        undoRow.addView(undoBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        root.addView(undoRow, lp(top = dp(10)))

        listBtn = button("MES OBSTACLES", C.CARD2, C.JAUNE, 16f) { startActivity(Intent(this, ListActivity::class.java)) }
        root.addView(listBtn, lp(top = dp(16)))

        root.addView(settingsCard(), lp(top = dp(20)))
        root.addView(helpCard(), lp(top = dp(16)))
        root.addView(tv("BS ALERTE ROUTE v1.0 · BSINALY · 0779687995", 12f, C.GREY).apply { gravity = Gravity.CENTER }, lp(top = dp(20)))

        return ScrollView(this).apply { isFillViewport = true; addView(root) }
    }

    private fun chipTv() = tv("", 13f, C.TEXT, true).apply {
        gravity = Gravity.CENTER; background = box(C.CARD, 12); setPadding(dp(6), dp(10), dp(6), dp(10))
    }

    private fun markLabel() = "⚠ OBSTACLE ICI\n${Types.of(prefs.lastType).label}"

    private fun paintChips() {
        chips.forEachIndexed { i, c ->
            val t = Types.ALL[i]; val sel = t.code == prefs.lastType
            c.background = if (sel) box(t.color, 12) else box(C.CARD, 12, 2, t.color)
            c.setTextColor(if (sel) C.BG else C.TEXT)
        }
    }

    private fun settingsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = box(C.CARD, 18); setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        card.addView(tv("Réglages", 18f, C.JAUNE, true))
        card.addView(sw("Annonce vocale (« Attention, dos d'âne à 150 mètres »)", prefs.voice) { prefs.voice = it })
        card.addView(sw("Vibration", prefs.vibrate) { prefs.vibrate = it })
        card.addView(sw("Sens de circulation intelligent (pas d'alerte pour un obstacle déjà dépassé)", prefs.smartDir) { prefs.smartDir = it })
        card.addView(sw("Pointer avec le bouton volume –", prefs.volumeKey) { prefs.volumeKey = it })
        card.addView(sw("Garder l'écran allumé pendant le trajet", prefs.keepScreen) { prefs.keepScreen = it; applyScreen() })

        val antTv = tv("", 14f, C.TEXT)
        fun antTxt() { antTv.text = "Anticipation à grande vitesse : ${prefs.anticipation} s (à 80 km/h ≈ ${(prefs.anticipation * 80 / 3.6).toInt()} m)" }
        antTxt(); card.addView(antTv, lp(top = dp(14)))
        card.addView(seek(4, 15, prefs.anticipation) { prefs.anticipation = it; antTxt() })

        val volTv = tv("", 14f, C.TEXT)
        fun volTxt() { volTv.text = "Volume du bip : ${prefs.beepVolume} %" }
        volTxt(); card.addView(volTv, lp(top = dp(10)))
        card.addView(seek(10, 100, prefs.beepVolume) { prefs.beepVolume = it; volTxt() })

        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(button("Tester l'alerte", C.CARD2, C.TEXT, 14f) {
            val fake = Obstacle(0, 0.0, 0.0, prefs.lastType, 0, 0, 0f, -1f, 1, 0, false)
            alerter.warn(fake, 150f); h.postDelayed({ alerter.close() }, 4200)
        }, lp(0, weight = 1f))
        r.addView(button("Batterie illimitée", C.CARD2, C.TEXT, 14f) { askBattery(true) }, lp(0, weight = 1f).apply { marginStart = dp(8) })
        card.addView(r, lp(top = dp(14)))
        return card
    }

    private fun helpCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = box(C.CARD, 18, 1, C.LINE); setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        card.addView(tv("Comment ça marche", 18f, C.JAUNE, true))
        card.addView(tv(
            "1. Premier trajet : touchez DÉMARRER, choisissez le type puis touchez OBSTACLE ICI en passant sur chaque obstacle " +
            "(ou appuyez sur volume –, ou sur « Obstacle ici » dans la notification).\n" +
            "2. Trajets suivants : touchez simplement DÉMARRER. En approchant d'un obstacle : 3 bips, vibration et annonce vocale, " +
            "puis bips rapides à moins de 35 m.\n" +
            "3. Plus vous roulez vite, plus l'alerte arrive tôt. Un obstacle déjà dépassé ne sonne pas.\n" +
            "4. Pointer deux fois le même obstacle affine sa position au lieu de créer un doublon.\n" +
            "5. Le téléphone peut être verrouillé : la surveillance continue en arrière-plan.\n" +
            "Sécurité : faites pointer par un passager ou utilisez le bouton volume — gardez les yeux sur la route.",
            14f, C.TEXT
        ), lp(top = dp(8)))
        return card
    }

    private fun sw(label: String, on: Boolean, f: (Boolean) -> Unit) = Switch(this).apply {
        text = label; isChecked = on; textSize = 14f; setTextColor(C.TEXT)
        setPadding(0, dp(10), 0, dp(4)); minHeight = dp(48)
        setOnCheckedChangeListener { _, v -> f(v) }
    }

    private fun seek(min: Int, max: Int, v: Int, f: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max - min; progress = v - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) f(p + min) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    // ------------------------------------------------------------------ actions

    private fun toggleRun() {
        if (Live.running) { GuardService.stop(this); h.postDelayed({ refresh() }, 300); return }
        startGuard()
    }

    private fun startGuard() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) { pendingStart = true; requestPermissions(need.toTypedArray(), 7); return }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Activez la localisation (GPS) puis revenez", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
            return
        }
        GuardService.start(this)
        askBattery(false)
        h.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && pendingStart) {
            pendingStart = false
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGuard()
            else Toast.makeText(this, "La localisation précise est indispensable pour détecter les obstacles", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBattery(force: Boolean) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            if (force) Toast.makeText(this, "Déjà activé : la batterie ne limite pas l'appli", Toast.LENGTH_SHORT).show()
            return
        }
        val sp = getSharedPreferences("bsar", MODE_PRIVATE)
        if (!force && sp.getBoolean("batAsked", false)) return
        sp.edit().putBoolean("batAsked", true).apply()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun mark() {
        if (!Live.running) {
            startGuard()
            Toast.makeText(this, "Surveillance démarrée : attendez le signal GPS puis pointez", Toast.LENGTH_LONG).show()
            return
        }
        val loc = Live.last
        if (loc == null || System.currentTimeMillis() - Live.gpsFixAt > 15000) {
            alerter.beep(2, 200, android.media.ToneGenerator.TONE_PROP_NACK, 200)
            Toast.makeText(this, "Pas encore de signal GPS — patientez à ciel ouvert", Toast.LENGTH_LONG).show()
            return
        }
        val (o, merged) = Store.add(this, loc, prefs.lastType)
        Live.justMarked.add(o.id)
        alerter.marked()
        lastMarkedId = o.id; lastMarkedNew = !merged
        val t = Types.of(o.type)
        lastTv.text = if (merged) "${t.label} confirmé (${o.confirms}×) — position affinée"
        else "${t.label} enregistré · précision ±${loc.accuracy.toInt()} m" +
            if (loc.accuracy > 30) " (signal faible, pointez à nouveau au prochain passage)" else ""
        lastTv.setTextColor(t.color)
        undoBtn.visibility = if (merged) View.GONE else View.VISIBLE
        refresh()
    }

    private fun undo() {
        if (lastMarkedId >= 0 && lastMarkedNew) Store.remove(this, lastMarkedId)
        lastMarkedId = -1; undoBtn.visibility = View.GONE
        lastTv.text = "Dernier pointage annulé"; lastTv.setTextColor(C.GREY)
        refresh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && prefs.volumeKey && Live.running) {
            if (event?.repeatCount == 0) mark()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ rafraîchissement

    private val loop = object : Runnable {
        override fun run() { refresh(); radar.tick(); h.postDelayed(this, 120) }
    }
    private var lastSlow = 0L

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (Store.version != seenVersion || seenVersion < 0) { cache = Store.all(this); seenVersion = Store.version }
        radar.obstacles = cache; radar.loc = Live.last; radar.nearestId = Live.nearestId
        if (now - lastSlow < 450) return
        lastSlow = now

        val run = Live.running
        val loc = Live.last
        val fresh = loc != null && now - Live.gpsFixAt < 8000
        gpsChip.text = when {
            !run -> "GPS en pause"
            !fresh -> "Recherche GPS…"
            else -> "GPS ±${loc!!.accuracy.toInt()} m"
        }
        gpsChip.setTextColor(when { !run -> C.GREY; !fresh -> C.JAUNE; loc!!.accuracy <= 20 -> C.OK; else -> C.JAUNE })
        speedTv.text = if (run && fresh && loc!!.hasSpeed()) "${(loc.speed * 3.6f).toInt()} km/h" else "— km/h"
        countTv.text = "${cache.size} obstacle${if (cache.size > 1) "s" else ""}"
        listBtn.text = "MES OBSTACLES (${cache.size})"

        runBtn.text = if (run) "ARRÊTER LA SURVEILLANCE" else "DÉMARRER LA SURVEILLANCE"
        runBtn.background = box(if (run) C.ROUGE else C.OK, 14)
        runBtn.setTextColor(if (run) android.graphics.Color.WHITE else C.BG)

        nextTv.text = when {
            !run -> if (cache.isEmpty()) "Démarrez puis pointez les obstacles de votre trajet" else "Touchez DÉMARRER avant de prendre la route"
            Live.nearestDist >= 0 -> cache.firstOrNull { it.id == Live.nearestId }
                ?.let { "Prochain : ${Types.of(it.type).label} à ${fmtDist(Live.nearestDist)}" } ?: ""
            else -> "Aucun obstacle devant vous"
        }

        val showBanner = run && now - Live.alertAt < 8000 && Live.alertText.isNotEmpty()
        banner.visibility = if (showBanner) View.VISIBLE else View.GONE
        if (showBanner) banner.text = "⚠ ${Live.alertText}"
        applyScreen()
    }

    private fun applyScreen() {
        if (prefs.keepScreen && Live.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() { super.onResume(); seenVersion = -1; h.post(loop) }
    override fun onPause() { super.onPause(); h.removeCallbacks(loop) }
    override fun onDestroy() { alerter.release(); super.onDestroy() }
}
