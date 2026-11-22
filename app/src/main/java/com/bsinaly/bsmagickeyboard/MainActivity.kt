package com.bsinaly.bsmagickeyboard

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity(), SensorEventListener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var statusTv: TextView
    private val tabButtons = mutableListOf<Button>()
    private var tab = 0

    private var btStateTv: TextView? = null
    private var wifiStateTv: TextView? = null

    private var sticky = 0
    private val stickyButtons = mutableMapOf<Int, Button>()

    private var sensitivity = 1.6f
    private var touchpad: TouchpadView? = null
    private var gyroOn = false
    private var gyroBtn: Button? = null
    private var gAccX = 0f
    private var gAccY = 0f

    private var askedEnable = false

    private val prefs by lazy { getSharedPreferences("bsmk", Context.MODE_PRIVATE) }
    private val linkListener: () -> Unit = { ui.post { refreshStatus() } }

    // ======================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.GREEN
        window.navigationBarColor = C.GREEN
        Link.azerty = prefs.getBoolean("azerty", true)
        sensitivity = prefs.getFloat("sens", 1.6f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.GREEN)
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        header.addView(tv("BS MAGIC KEYBOARD", 24f, C.GOLD, true))
        header.addView(tv("Votre téléphone devient clavier + souris de l'ordinateur", 12.5f, Color.WHITE))
        statusTv = tv("● Non connecté", 13f, Color.WHITE, true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(0x33FFFFFF, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        header.addView(statusTv)
        root.addView(header)

        val tabs = row().apply {
            setBackgroundColor(C.GREEN2)
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        listOf("Connexion", "Souris", "Clavier", "Raccourcis").forEachIndexed { i, label ->
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                stateListAnimator = null
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showTab(i) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(2), 0, dp(2), 0) }
            }
            tabButtons.add(b)
            tabs.addView(b)
        }
        root.addView(tabs)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(tv("© BSINALY – 0779687995", 11f, C.GREY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(3))
        })
        setContentView(root)

        Link.addListener(linkListener)
        refreshStatus()
        showTab(if (Link.connected()) 1 else 0)
        ensurePermissions()
    }

    override fun onDestroy() {
        Link.removeListener(linkListener)
        stopGyro()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        stopGyro()
    }

    // ============================================================ autorisations
    private fun missingPermissions(): List<String> {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_CONNECT
            need += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        return need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun ensurePermissions() {
        val miss = missingPermissions()
        if (miss.isEmpty()) startBt() else requestPermissions(miss.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Sans l'autorisation « Appareils à proximité », seul le mode Wi-Fi fonctionne.")
            startKeepAlive()
            return
        }
        startBt()
    }

    private fun startBt() {
        val a = BtHid.adapter(this)
        if (a == null) {
            btStateTv?.text = "Ce téléphone n'a pas de Bluetooth — utilisez le Wi-Fi."
            startKeepAlive()
            return
        }
        val enabled = try { a.isEnabled } catch (_: Exception) { false }
        if (!enabled) {
            if (!askedEnable) {
                askedEnable = true
                try {
                    startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2)
                } catch (_: Exception) { toast("Activez le Bluetooth dans les paramètres.") }
            }
            return
        }
        startKeepAlive()
        BtHid.start(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) startBt()
    }

    private fun startKeepAlive() {
        try { startForegroundService(Intent(this, KeepAliveService::class.java)) } catch (_: Exception) {}
    }

    private fun makeDiscoverable() {
        startBt()
        try {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180)
            )
        } catch (_: Exception) { toast("Impossible de rendre le téléphone visible.") }
    }

    // ================================================================ état
    private fun refreshStatus() {
        val on = Link.connected()
        statusTv.text = (if (on) "● " else "○ ") + Link.status()
        statusTv.setTextColor(if (on) C.GOLD else Color.WHITE)
        btStateTv?.text = BtHid.state
        wifiStateTv?.text = WifiLink.state
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun send(block: (Transport) -> Unit) {
        val t = Link.t()
        if (t == null) { toast("Non connecté — ouvrez l'onglet « Connexion ».") ; return }
        block(t)
    }

    // ================================================================ onglets
    private fun showTab(i: Int) {
        tab = i
        stopGyro()
        touchpad = null; gyroBtn = null; btStateTv = null; wifiStateTv = null
        stickyButtons.clear()
        tabButtons.forEachIndexed { idx, b ->
            val sel = idx == i
            b.background = rounded(if (sel) C.GOLD else C.GREEN2, 10)
            b.setTextColor(if (sel) C.GREEN else Color.WHITE)
        }
        hideKeyboard()
        content.removeAllViews()
        val v: View = when (i) {
            0 -> scroll(connexionView())
            1 -> sourisView()
            2 -> scroll(clavierView())
            else -> scroll(raccourcisView())
        }
        content.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        refreshStatus()
    }

    private fun scroll(v: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(v)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(16))
    }

    private fun title(t: String) = tv(t, 16f, C.GREEN, true).apply { setPadding(0, 0, 0, dp(6)) }

    /** Petit bouton pour les grilles. */
    private fun sbtn(text: String, bg: Int = C.GREEN, fg: Int = Color.WHITE, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            background = rounded(bg, 10)
            stateListAnimator = null
            minHeight = dp(46)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        }

    private fun grid(items: List<Pair<String, () -> Unit>>, cols: Int = 3, bg: Int = C.GREEN): LinearLayout {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(cols).forEach { chunk ->
            val r = row()
            chunk.forEach { (label, action) -> r.addView(sbtn(label, bg) { action() }) }
            repeat(cols - chunk.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            g.addView(r)
        }
        return g
    }

    private fun k(label: String, name: String, mods: Int = 0): Pair<String, () -> Unit> =
        label to { send { it.key(name, mods) } }

    private fun m(label: String, name: String): Pair<String, () -> Unit> =
        label to { send { it.media(name) } }

    private fun input(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setSingleLine(true)
            textSize = 15f
            setTextColor(C.TEXT)
            background = rounded(C.BG, 10, 1, C.TRACK)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    // ============================================================ Connexion
    private fun connexionView(): View {
        val col = column()

        // ---------- Bluetooth
        val bt = card()
        bt.addView(title("1. Bluetooth — rien à installer sur le PC"))
        bt.addView(tv(
            "• Touchez « Rendre visible ».\n" +
            "• Sur l'ordinateur : Paramètres › Bluetooth › Ajouter un appareil › Bluetooth, puis choisissez ce téléphone.\n" +
            "• Le PC est déjà associé ? Touchez simplement « Connecter » à côté de son nom.",
            13.5f, C.GREY))
        btStateTv = tv(BtHid.state, 14f, C.GREEN2, true).apply { setPadding(0, dp(8), 0, 0) }
        bt.addView(btStateTv)
        bt.addView(btn("Rendre le téléphone visible (3 min)") { makeDiscoverable() })
        bt.addView(btn("Redémarrer le clavier/souris Bluetooth", C.GREEN2) {
            BtHid.stop(); ui.postDelayed({ ensurePermissions() }, 400)
        })

        bt.addView(tv("Ordinateurs et appareils associés :", 13.5f, C.TEXT, true).apply { setPadding(0, dp(12), 0, dp(2)) })
        val devices = BtHid.bonded(this)
        if (devices.isEmpty()) {
            bt.addView(tv("Aucun pour l'instant — associez le PC avec « Rendre visible ».", 13f, C.GREY))
        }
        devices.forEach { d ->
            val r = row().apply { setPadding(0, dp(4), 0, dp(4)) }
            val label = (if (BtHid.isComputer(d)) "💻 " else "• ") + BtHid.name(d)
            r.addView(tv(label, 14f, C.TEXT), weight1())
            r.addView(Button(this).apply {
                text = "Connecter"; isAllCaps = false; textSize = 13f
                setTextColor(Color.WHITE); background = rounded(C.GREEN, 10); stateListAnimator = null
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { Link.current = BtHid; BtHid.connect(this@MainActivity, d) }
            })
            bt.addView(r)
        }
        bt.addView(btn("Déconnecter le Bluetooth", C.RED) { BtHid.disconnect() })
        col.addView(bt)

        // ---------- Disposition
        val lay = card()
        lay.addView(title("Clavier de l'ordinateur"))
        lay.addView(tv("Choisissez la disposition réglée sur le PC (pour que les lettres et accents soient exacts en Bluetooth).", 13f, C.GREY))
        val r = row()
        lateinit var bAz: Button
        lateinit var bUs: Button
        fun paint() {
            bAz.background = rounded(if (Link.azerty) C.GOLD else C.TRACK, 10)
            bUs.background = rounded(if (!Link.azerty) C.GOLD else C.TRACK, 10)
        }
        bAz = sbtn("AZERTY (Français)", C.TRACK, C.TEXT) {
            Link.azerty = true; prefs.edit().putBoolean("azerty", true).apply(); paint()
        }
        bUs = sbtn("QWERTY (Anglais)", C.TRACK, C.TEXT) {
            Link.azerty = false; prefs.edit().putBoolean("azerty", false).apply(); paint()
        }
        paint()
        r.addView(bAz); r.addView(bUs)
        lay.addView(r)
        col.addView(lay)

        // ---------- Wi-Fi
        val wf = card()
        wf.addView(title("2. Wi-Fi — même réseau que le PC"))
        wf.addView(tv(
            "• Lancez « BS_MAGIC_KEYBOARD_PC.exe » sur l'ordinateur (à télécharger avec l'APK). Autorisez-le dans le pare-feu.\n" +
            "• Le PC et le téléphone doivent être sur le même Wi-Fi (ou le PC branché sur le partage de connexion du téléphone).\n" +
            "• Saisissez le code à 4 chiffres affiché sur le PC.",
            13.5f, C.GREY))
        wifiStateTv = tv(WifiLink.state, 14f, C.GREEN2, true).apply { setPadding(0, dp(8), 0, 0) }
        wf.addView(wifiStateTv)
        val ipIn = input("Adresse IP du PC (ex. 192.168.1.20)", prefs.getString("ip", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val pinIn = input("Code affiché sur le PC", prefs.getString("pin", "") ?: "", InputType.TYPE_CLASS_NUMBER)
        val found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun connectWifi(ip: String, label: String) {
            if (ip.isBlank()) { toast("Saisissez l'adresse IP du PC."); return }
            if (pinIn.text.isBlank()) { toast("Saisissez le code affiché sur le PC."); pinIn.requestFocus(); return }
            wifiStateTv?.text = "Connexion à $label…"
            hideKeyboard()
            WifiLink.connect(this, ip, pinIn.text.toString(), label) { ok, msg ->
                ui.post {
                    toast(msg)
                    if (ok) showTab(1) else wifiStateTv?.text = msg
                }
            }
        }

        wf.addView(btn("Rechercher les ordinateurs") {
            found.removeAllViews()
            found.addView(tv("Recherche en cours…", 13f, C.GREY))
            WifiLink.discover { list ->
                ui.post {
                    found.removeAllViews()
                    if (list.isEmpty()) {
                        found.addView(tv("Aucun PC trouvé. Vérifiez que le programme PC est lancé, ou saisissez son adresse IP.", 13f, C.RED))
                    }
                    list.forEach { pc ->
                        val rr = row().apply { setPadding(0, dp(4), 0, dp(4)) }
                        rr.addView(tv("💻 ${pc.name}\n${pc.ip}", 14f, C.TEXT), weight1())
                        rr.addView(Button(this).apply {
                            text = "Choisir"; isAllCaps = false; textSize = 13f
                            setTextColor(Color.WHITE); background = rounded(C.GREEN, 10); stateListAnimator = null
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                            setOnClickListener { ipIn.setText(pc.ip); connectWifi(pc.ip, pc.name) }
                        })
                        found.addView(rr)
                    }
                }
            }
        })
        wf.addView(found)
        wf.addView(ipIn)
        wf.addView(pinIn)
        wf.addView(btn("Connecter en Wi-Fi") { connectWifi(ipIn.text.toString(), ipIn.text.toString().trim()) })
        wf.addView(btn("Déconnecter le Wi-Fi", C.RED) { WifiLink.disconnect() })
        col.addView(wf)

        val st = card()
        st.addView(btn("Arrêter complètement BS MAGIC KEYBOARD", C.GREY) {
            startService(Intent(this, KeepAliveService::class.java).setAction(KeepAliveService.ACTION_STOP))
            finish()
        })
        col.addView(st)
        return col
    }

    // ================================================================ Souris
    private fun sourisView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }

        val padRow = row()
        padRow.gravity = Gravity.FILL
        val pad = TouchpadView(this).apply { sensitivity = this@MainActivity.sensitivity }
        touchpad = pad
        padRow.addView(pad, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        padRow.addView(ScrollStrip(this), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(padRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Boutons physiques : maintenir = clic maintenu
        val bRow = row()
        listOf(Triple("Gauche", 1, 2f), Triple("Milieu", 4, 1f), Triple("Droit", 2, 2f)).forEach { (label, bit, w) ->
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE); background = rounded(C.GREEN2, 12); stateListAnimator = null
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            v.background = rounded(C.GOLD, 12); Link.press(bit, true)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.background = rounded(C.GREEN2, 12); Link.press(bit, false)
                        }
                    }
                    true
                }
            }
            bRow.addView(b, LinearLayout.LayoutParams(0, dp(54), w).apply { setMargins(dp(3), dp(6), dp(3), 0) })
        }
        col.addView(bRow)

        // Gyroscope + sensibilité
        val oRow = row().apply { setPadding(0, dp(4), 0, 0) }
        val gb = sbtn("Souris gyroscope : OFF", C.TRACK, C.TEXT) { toggleGyro() }
        gyroBtn = gb
        oRow.addView(gb)
        val sens = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sensTv = tv("Vitesse : ${"%.1f".format(sensitivity)}", 12f, C.GREY)
        sens.addView(sensTv)
        sens.addView(SeekBar(this).apply {
            max = 40
            progress = (sensitivity * 10).toInt().coerceIn(3, 40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sensitivity = p.coerceAtLeast(3) / 10f
                    touchpad?.sensitivity = sensitivity
                    sensTv.text = "Vitesse : ${"%.1f".format(sensitivity)}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putFloat("sens", sensitivity).apply() }
            })
        })
        oRow.addView(sens, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(oRow)

        // Saisie clavier directement sous la souris
        val kRow = row().apply { setPadding(0, dp(4), 0, 0) }
        val kc = KeyCapture(this).apply {
            hint = "⌨  Touchez ici pour écrire sur le PC"
            textSize = 15f
            background = rounded(C.GREEN, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        bindCapture(kc)
        kRow.addView(kc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        kRow.addView(Button(this).apply {
            text = "⌫"; textSize = 18f; setTextColor(Color.WHITE)
            background = rounded(C.GREEN2, 12); stateListAnimator = null
            setOnClickListener { send { it.key("backspace") } }
        }, LinearLayout.LayoutParams(dp(54), dp(48)).apply { leftMargin = dp(4) })
        kRow.addView(Button(this).apply {
            text = "↵"; textSize = 18f; setTextColor(C.GREEN)
            background = rounded(C.GOLD, 12); stateListAnimator = null
            setOnClickListener { send { it.key("enter") } }
        }, LinearLayout.LayoutParams(dp(54), dp(48)).apply { leftMargin = dp(4) })
        col.addView(kRow)
        return col
    }

    private fun bindCapture(kc: KeyCapture) {
        kc.onType = fun(backspaces: Int, inserted: String) {
            val t = Link.t() ?: run { toast("Non connecté — ouvrez l'onglet « Connexion »."); return }
            repeat(backspaces) { t.key("backspace") }
            if (inserted.isEmpty()) return
            if (sticky != 0 && inserted.length == 1) {
                t.key(inserted.lowercase(), sticky or (if (inserted[0].isUpperCase()) Mod.SHIFT else 0))
                clearSticky()
            } else {
                t.text(inserted)
            }
        }
        kc.onEnter = { send { it.key("enter", sticky) }; clearSticky() }
        kc.onBackspace = { send { it.key("backspace", sticky) }; clearSticky() }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (_: Exception) {}
    }

    // ============================================================ Gyroscope
    private fun toggleGyro() {
        if (gyroOn) { stopGyro(); return }
        val sm = getSystemService(SensorManager::class.java)
        val g = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (g == null) { toast("Ce téléphone n'a pas de gyroscope."); return }
        gyroOn = true
        gAccX = 0f; gAccY = 0f
        sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME)
        gyroBtn?.text = "Souris gyroscope : ON"
        gyroBtn?.background = rounded(C.GOLD, 10)
        toast("Tenez le téléphone comme une télécommande et pivotez-le pour bouger le pointeur.")
    }

    private fun stopGyro() {
        if (!gyroOn) return
        gyroOn = false
        try { getSystemService(SensorManager::class.java)?.unregisterListener(this) } catch (_: Exception) {}
        gyroBtn?.text = "Souris gyroscope : OFF"
        gyroBtn?.background = rounded(C.TRACK, 10)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!gyroOn) return
        val dead = 0.03f
        val gx = e.values[0]; val gz = e.values[2]
        val k = 22f * sensitivity
        if (abs(gz) > dead) gAccX += -gz * k
        if (abs(gx) > dead) gAccY += -gx * k
        val ix = gAccX.toInt(); val iy = gAccY.toInt()
        gAccX -= ix; gAccY -= iy
        if (ix != 0 || iy != 0) Link.t()?.move(ix, iy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ================================================================ Clavier
    private fun clavierView(): View {
        val col = column()

        val c1 = card()
        c1.addView(title("Saisie directe"))
        c1.addView(tv("Tout ce que vous tapez ici s'écrit aussitôt sur l'ordinateur.", 13f, C.GREY))
        val kc = KeyCapture(this).apply {
            hint = "⌨  Touchez ici et tapez…"
            textSize = 16f
            background = rounded(C.GREEN, 12)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        bindCapture(kc)
        c1.addView(kc)
        c1.addView(grid(listOf(
            k("Entrée ↵", "enter"), k("⌫ Effacer", "backspace"), k("Espace", "space"),
            k("Tab ⇥", "tab"), k("Échap", "esc"), k("Suppr", "delete")
        )))
        c1.addView(sbtn("Vider le champ (sans effacer sur le PC)", C.TRACK, C.TEXT) { kc.clearSilently() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
        })
        col.addView(c1)

        // Modificateurs « collants »
        val c2 = card()
        c2.addView(title("Touches de combinaison"))
        c2.addView(tv("Activez Ctrl, Maj, Alt ou Win, puis tapez une lettre ou une touche : elles seront combinées (ex. Ctrl + T).", 13f, C.GREY))
        val r = row()
        listOf("Ctrl" to Mod.CTRL, "Maj" to Mod.SHIFT, "Alt" to Mod.ALT, "Win" to Mod.WIN, "AltGr" to Mod.ALTGR).forEach { (label, bit) ->
            val b = sbtn(label, C.TRACK, C.TEXT) {
                sticky = sticky xor bit
                paintSticky()
            }
            stickyButtons[bit] = b
            r.addView(b)
        }
        c2.addView(r)
        paintSticky()
        col.addView(c2)

        // Navigation
        val c3 = card()
        c3.addView(title("Flèches et navigation"))
        val arrows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val a1 = row()
        a1.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        a1.addView(sbtn("▲") { stickyKey("up") })
        a1.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val a2 = row()
        a2.addView(sbtn("◀") { stickyKey("left") })
        a2.addView(sbtn("▼") { stickyKey("down") })
        a2.addView(sbtn("▶") { stickyKey("right") })
        arrows.addView(a1); arrows.addView(a2)
        c3.addView(arrows)
        c3.addView(grid(listOf(
            "Début" to { stickyKey("home") }, "Fin" to { stickyKey("end") }, "Inser" to { stickyKey("insert") },
            "Page ▲" to { stickyKey("pgup") }, "Page ▼" to { stickyKey("pgdn") }, "Menu ≡" to { stickyKey("menu") }
        ), bg = C.GREEN2))
        col.addView(c3)

        // Touches F
        val c4 = card()
        c4.addView(title("Touches de fonction"))
        c4.addView(grid((1..12).map { n -> "F$n" to { stickyKey("f$n") } }, cols = 4, bg = C.GREEN2))
        col.addView(c4)

        // Texte long
        val c5 = card()
        c5.addView(title("Envoyer un texte complet"))
        c5.addView(tv("Rédigez ou collez un texte (avec la correction automatique du téléphone), puis envoyez-le d'un coup.", 13f, C.GREY))
        val big = EditText(this).apply {
            hint = "Votre texte…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setTextColor(C.TEXT)
            background = rounded(C.BG, 10, 1, C.TRACK)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        c5.addView(big)
        val sr = row()
        sr.addView(sbtn("Envoyer au PC") {
            val s = big.text.toString()
            if (s.isNotEmpty()) send { it.text(s); big.setText("") }
        })
        sr.addView(sbtn("Envoyer + Entrée", C.GOLD, C.GREEN) {
            val s = big.text.toString()
            send { it.text(s); it.key("enter"); big.setText("") }
        })
        c5.addView(sr)
        col.addView(c5)
        return col
    }

    private fun stickyKey(name: String) {
        send { it.key(name, sticky) }
        clearSticky()
    }

    private fun clearSticky() {
        if (sticky == 0) return
        sticky = 0
        ui.post { paintSticky() }
    }

    private fun paintSticky() {
        stickyButtons.forEach { (bit, b) ->
            val on = sticky and bit != 0
            b.background = rounded(if (on) C.GOLD else C.TRACK, 10)
            b.setTextColor(if (on) C.GREEN else C.TEXT)
        }
    }

    // ============================================================ Raccourcis
    private fun raccourcisView(): View {
        val col = column()
        val C_ = Mod.CTRL; val S = Mod.SHIFT; val A = Mod.ALT; val W = Mod.WIN

        fun section(t: String, items: List<Pair<String, () -> Unit>>, cols: Int = 3, bg: Int = C.GREEN) {
            val c = card()
            c.addView(title(t))
            c.addView(grid(items, cols, bg))
            col.addView(c)
        }

        section("Multimédia", listOf(
            m("🔉 Vol −", "vol_down"), m("🔇 Muet", "mute"), m("🔊 Vol +", "vol_up"),
            m("⏮ Préc.", "prev"), m("⏯ Lecture", "play"), m("⏭ Suiv.", "next")
        ), bg = C.GREEN2)

        section("Présentation (PowerPoint)", listOf(
            k("▶ Lancer", "f5"), k("▶ Depuis ici", "f5", S), k("✖ Quitter", "esc"),
            k("◀ Précédente", "left"), k("Écran noir", "b"), k("Suivante ▶", "right")
        ))

        section("Édition", listOf(
            k("Copier", "c", C_), k("Coller", "v", C_), k("Couper", "x", C_),
            k("Annuler", "z", C_), k("Rétablir", "y", C_), k("Tout sélect.", "a", C_),
            k("Enregistrer", "s", C_), k("Rechercher", "f", C_), k("Imprimer", "p", C_)
        ))

        section("Windows", listOf(
            k("⊞ Démarrer", "win"), k("Bureau", "d", W), k("Alt + Tab", "tab", A),
            k("Fermer la fenêtre", "f4", A), k("🔒 Verrouiller", "l", W), k("Explorateur", "e", W),
            k("Capture", "s", W or S), k("Gest. tâches", "esc", C_ or S), k("Paramètres", "i", W)
        ), bg = C.GREEN2)

        section("Navigateur internet", listOf(
            k("Nouvel onglet", "t", C_), k("Fermer onglet", "w", C_), k("Onglet suivant", "tab", C_),
            k("⟳ Actualiser", "f5"), k("◀ Retour", "left", A), k("Plein écran", "f11"),
            k("Zoom +", "=", C_), k("Zoom −", "-", C_), k("Zoom 100 %", "num0", C_)
        ))
        return col
    }
}
