package com.bsinaly.bsmagickeyboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.Executors

/**
 * Le téléphone se présente au PC comme un vrai clavier + souris Bluetooth
 * (profil HID Device, Android 9+). Aucun logiciel n'est nécessaire côté PC.
 */
@SuppressLint("MissingPermission")
object BtHid : Transport {

    private const val ID_KB = 1
    private const val ID_MOUSE = 2
    private const val ID_CONS = 3

    /** Descripteur HID composite : clavier (1), souris (2), touches multimédia (3). */
    private val DESCRIPTOR: ByteArray = intArrayOf(
        // ---- Clavier ----
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KB,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02,                   // 8 modificateurs
        0x75, 0x08, 0x95, 0x01, 0x81, 0x01,                   // réservé
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x75, 0x01, 0x95, 0x05, 0x91, 0x02, // LED
        0x75, 0x03, 0x95, 0x01, 0x91, 0x01,
        0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, 0x15, 0x00, 0x26, 0xFF, 0x00,
        0x75, 0x08, 0x95, 0x06, 0x81, 0x00,                   // 6 touches
        0xC0,
        // ---- Souris ----
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE,
        0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01,
        0x95, 0x05, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
        // ---- Multimédia ----
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONS,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
        0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0
    ).map { it.toByte() }.toByteArray()

    @Volatile var hid: BluetoothHidDevice? = null
    @Volatile var host: BluetoothDevice? = null
    @Volatile var registered = false
    @Volatile var state = "Bluetooth non démarré"
    @Volatile private var starting = false
    private var appCtx: Context? = null
    private var adapter: BluetoothAdapter? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var mask = 0

    fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun prefs() = appCtx?.getSharedPreferences("bsmk", Context.MODE_PRIVATE)

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (hid != null) { if (!registered) register(); return }
        if (starting) return
        val a = adapter(ctx) ?: run { state = "Ce téléphone n'a pas de Bluetooth"; Link.changed(); return }
        adapter = a
        if (!a.isEnabled) { state = "Bluetooth désactivé"; Link.changed(); return }
        starting = true
        state = "Démarrage du clavier/souris Bluetooth…"
        Link.changed()
        val ok = try {
            a.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) { false }
        if (!ok) {
            starting = false
            state = "Ce téléphone ne permet pas le mode clavier/souris Bluetooth — utilisez le Wi-Fi"
            Link.changed()
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            starting = false
            hid = proxy as BluetoothHidDevice
            register()
        }
        override fun onServiceDisconnected(profile: Int) {
            starting = false
            hid = null; registered = false; host = null
            state = "Service Bluetooth interrompu"
            Link.changed()
        }
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BS MAGIC KEYBOARD", "Clavier et souris BSINALY", "BSINALY",
            BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
        )
        try {
            if (!h.registerApp(sdp, null, null, exec, callback)) {
                state = "Enregistrement refusé — redémarrez le Bluetooth puis réessayez"
                Link.changed()
            }
        } catch (e: Exception) {
            state = "Erreur Bluetooth : ${e.message}"
            Link.changed()
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BtHid.registered = registered
            state = if (registered) "Prêt — associez ou connectez le PC" else "Clavier/souris Bluetooth arrêté"
            Link.changed()
            if (registered && host == null) autoReconnect(pluggedDevice)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    Link.current = this@BtHid
                    state = "Connecté en Bluetooth à ${name(device)}"
                    prefs()?.edit()?.putString("last_bt", device?.address)?.apply()
                }
                BluetoothProfile.STATE_CONNECTING -> state = "Connexion à ${name(device)}…"
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == null || host?.address == device?.address) host = null
                    state = if (registered) "Déconnecté — prêt à se reconnecter" else "Déconnecté"
                }
            }
            Link.changed()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val size = when (id.toInt()) { ID_MOUSE -> 4; ID_CONS -> 2; else -> 8 }
            try { hid?.replyReport(device, type, id, ByteArray(size)) } catch (_: Exception) {}
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            try { hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) } catch (_: Exception) {}
        }
    }

    private fun autoReconnect(plugged: BluetoothDevice?) {
        val h = hid ?: return
        val dev = plugged ?: prefs()?.getString("last_bt", null)?.let {
            try { adapter?.getRemoteDevice(it) } catch (_: Exception) { null }
        } ?: return
        try { h.connect(dev) } catch (_: Exception) {}
    }

    fun name(d: BluetoothDevice?): String =
        try { d?.name ?: d?.address ?: "PC" } catch (_: Exception) { "PC" }

    fun isComputer(d: BluetoothDevice): Boolean = try {
        d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
    } catch (_: Exception) { false }

    fun bonded(ctx: Context): List<BluetoothDevice> = try {
        (adapter(ctx)?.bondedDevices ?: emptySet())
            .sortedWith(compareBy<BluetoothDevice>({ !isComputer(it) }, { name(it) }))
    } catch (_: Exception) { emptyList() }

    fun connect(ctx: Context, d: BluetoothDevice) {
        val h = hid
        if (h == null || !registered) {
            start(ctx)
            state = "Patientez, démarrage en cours… puis touchez à nouveau « Connecter »"
            Link.changed()
            return
        }
        state = "Connexion à ${name(d)}…"
        Link.changed()
        try {
            host?.let { if (it.address != d.address) h.disconnect(it) }
            h.connect(d)
        } catch (e: Exception) {
            state = "Échec : ${e.message}"; Link.changed()
        }
    }

    fun disconnect() {
        try { host?.let { hid?.disconnect(it) } } catch (_: Exception) {}
    }

    fun stop() {
        try { hid?.unregisterApp() } catch (_: Exception) {}
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Exception) {}
        hid = null; registered = false; host = null
        state = "Bluetooth arrêté"
        Link.changed()
    }

    // ------------------------------------------------------------------ envoi
    override fun isReady() = registered && host != null && hid != null

    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        try { h.sendReport(d, id, data) } catch (_: Exception) {}
    }

    private fun tap(mod: Int, code: Int) {
        send(ID_KB, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
        Thread.sleep(8)
        send(ID_KB, ByteArray(8))
        Thread.sleep(8)
    }

    override fun move(dx: Int, dy: Int) {
        exec.execute {
            var x = dx; var y = dy
            do {
                val sx = x.coerceIn(-127, 127); val sy = y.coerceIn(-127, 127)
                send(ID_MOUSE, byteArrayOf(mask.toByte(), sx.toByte(), sy.toByte(), 0))
                x -= sx; y -= sy
            } while (x != 0 || y != 0)
        }
    }

    override fun buttons(mask: Int) {
        this.mask = mask
        exec.execute { send(ID_MOUSE, byteArrayOf(mask.toByte(), 0, 0, 0)) }
    }

    override fun scroll(v: Int) {
        exec.execute {
            var r = v
            while (r != 0) {
                val s = r.coerceIn(-127, 127)
                send(ID_MOUSE, byteArrayOf(mask.toByte(), 0, 0, s.toByte()))
                r -= s
            }
        }
    }

    override fun text(s: String) {
        val az = Link.azerty
        exec.execute { for (st in Keys.strokes(s, az)) tap(st.mod, st.code) }
    }

    override fun key(name: String, mods: Int) {
        val az = Link.azerty
        exec.execute {
            if (name == "win") { tap(mods or Mod.WIN, 0); return@execute }
            val st = Keys.stroke(name, az) ?: return@execute
            tap(st.mod or mods, st.code)
        }
    }

    override fun media(name: String) {
        val code = Keys.consumer[name] ?: return
        exec.execute {
            send(ID_CONS, byteArrayOf((code and 0xFF).toByte(), (code shr 8).toByte()))
            Thread.sleep(30)
            send(ID_CONS, byteArrayOf(0, 0))
        }
    }
}
