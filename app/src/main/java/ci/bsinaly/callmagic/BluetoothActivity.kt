package ci.bsinaly.callmagic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/** Mode Bluetooth : appareils associés ou à proximité ; le service attend aussi les connexions entrantes. */
@SuppressLint("MissingPermission")
class BluetoothActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_ENABLE = 2
    }

    private var adapter: BluetoothAdapter? = null
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private var scanning = false
    private var receiverOn = false
    private val observer: () -> Unit = { render() }

    private lateinit var status: TextView
    private lateinit var paired: LinearLayout
    private lateinit var nearby: LinearLayout

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) { found[d.address] = d; render() }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { scanning = false; render() }
                BluetoothAdapter.ACTION_STATE_CHANGED -> { Hub.startBtServer(); render() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        HubService.start(this)
        adapter = Hub.btAdapter()

        val root = Ui.screen(this)
        root.addView(Ui.title(this, "🔵 Bluetooth"))
        root.addView(Ui.text(this, "Aucun réseau nécessaire. Portée d'une dizaine de mètres. Plusieurs téléphones peuvent se connecter à celui-ci pour former un salon.", 13f, Ui.MUTED))

        val card = Ui.card(this)
        status = Ui.text(this, "", 14f, Ui.TEXT)
        card.addView(status)
        root.addView(card, Ui.lp(this, top = 16))

        if (adapter == null) {
            status.text = "⚠ Ce téléphone n'a pas de Bluetooth."
            return
        }

        val row = Ui.horizontal(this)
        val p1 = LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.dp(this@BluetoothActivity, 5) }
        val p2 = LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.dp(this@BluetoothActivity, 5) }
        row.addView(Ui.button(this, "🔍 Rechercher", Ui.GREEN) { scan() }, p1)
        row.addView(Ui.button(this, "👁 Me rendre visible", Ui.GREY) { visible() }, p2)
        root.addView(row, Ui.lp(this, top = 14))

        root.addView(Ui.text(this, "Appareils associés", 18f, Ui.TEXT, true), Ui.lp(this, top = 22))
        paired = Ui.vertical(this)
        root.addView(paired)
        root.addView(Ui.text(this, "Appareils à proximité", 18f, Ui.TEXT, true), Ui.lp(this, top = 22))
        nearby = Ui.vertical(this)
        root.addView(nearby)
        root.addView(Ui.button(this, "💬  Ouvrir le salon", Ui.GREEN) {
            startActivity(Intent(this, ChatActivity::class.java))
        }, Ui.lp(this, top = 18))
        root.addView(Ui.text(this, "Astuce : si l'autre téléphone n'apparaît pas, touchez « Me rendre visible » sur lui, puis « Rechercher » ici. Acceptez la demande d'association si elle s'affiche.", 12f, Ui.MUTED), Ui.lp(this, top = 12))

        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, f)
        receiverOn = true

        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (adapter == null) return
        Hub.observe(observer)
        if (ready()) Hub.startBtServer()
        render()
    }

    override fun onPause() {
        super.onPause()
        Hub.unobserve(observer)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (ready()) adapter?.cancelDiscovery() } catch (_: Exception) {}
        if (receiverOn) try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ autorisations

    private fun neededPerms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE
        )
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    private fun ready() = Hub.hasBtPerms(this) && adapter?.isEnabled == true

    private fun askPermissions() {
        if (!Hub.hasBtPerms(this) && Build.VERSION.SDK_INT >= 23) requestPermissions(neededPerms(), REQ_PERMS)
        else ensureEnabled()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (Hub.hasBtPerms(this)) ensureEnabled()
            else status.text = "⚠ Autorisations Bluetooth refusées. Accordez-les dans les paramètres de l'application."
        }
    }

    private fun ensureEnabled() {
        val a = adapter ?: return
        if (!a.isEnabled) {
            try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE) } catch (_: Exception) {}
        } else { Hub.startBtServer(); render() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE) { Hub.startBtServer(); render() }
    }

    // ------------------------------------------------------------------ actions

    private fun connect(d: BluetoothDevice) {
        if (!ready()) { askPermissions(); return }
        scanning = false
        val label = d.name ?: d.address
        status.text = "⏳ Connexion à $label…"
        Hub.connectBt(d) { ok ->
            render()
            if (ok) startActivity(Intent(this, ChatActivity::class.java))
            else Ui.toast(this, "Connexion impossible à $label. Vérifiez que BS CALLMagic est ouvert sur l'autre téléphone.")
        }
    }

    private fun scan() {
        if (!ready()) { askPermissions(); return }
        val a = adapter ?: return
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            found.clear()
            scanning = a.startDiscovery()
            if (!scanning) Ui.toast(this, "Recherche impossible. Sur certains téléphones, la localisation doit être activée.")
        } catch (e: Exception) { Ui.toast(this, "Recherche impossible : ${e.message}") }
        render()
    }

    private fun visible() {
        try {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        } catch (e: Exception) { Ui.toast(this, "Impossible : ${e.message}") }
    }

    private fun render() {
        val a = adapter ?: return
        if (!Hub.hasBtPerms(this)) { status.text = "⚠ Autorisations Bluetooth nécessaires."; return }
        if (!a.isEnabled) {
            status.text = "⚠ Bluetooth désactivé."
            paired.removeAllViews(); nearby.removeAllViews()
            paired.addView(Ui.button(this, "Activer le Bluetooth", Ui.BLUE) { ensureEnabled() }, Ui.lp(this, top = 8))
            return
        }
        val me = try { a.name } catch (_: Exception) { null } ?: Prefs.name(this)
        val connected = Hub.readyPeers().filter { it.transport == Proto.BT }
        status.text = "🟢 En attente de connexion — ce téléphone : $me" +
            (if (connected.isNotEmpty()) "\n✅ Connecté : " + connected.joinToString(", ") { it.name } else "") +
            (if (scanning) "\n🔍 Recherche des appareils…" else "")

        paired.removeAllViews()
        val bonded = try { a.bondedDevices?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        if (bonded.isEmpty()) paired.addView(Ui.text(this, "Aucun appareil associé.", 14f, Ui.MUTED), Ui.lp(this, top = 6))
        for (d in bonded) paired.addView(deviceButton(d), Ui.lp(this, top = 8))

        nearby.removeAllViews()
        val others = found.values.filter { f -> bonded.none { it.address == f.address } }
        if (others.isEmpty()) nearby.addView(Ui.text(this,
            if (scanning) "Recherche en cours…" else "Touchez « Rechercher » pour trouver les téléphones proches.", 14f, Ui.MUTED), Ui.lp(this, top = 6))
        for (d in others) nearby.addView(deviceButton(d), Ui.lp(this, top = 8))
    }

    private fun deviceButton(d: BluetoothDevice) =
        Ui.button(this, "📱  ${d.name ?: "Appareil sans nom"}   ·   ${d.address}", Ui.CARD) { connect(d) }
}
