package ci.bsinaly.callmagic

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Un élément de la conversation. */
data class Msg(
    val mine: Boolean,
    val text: String,
    val system: Boolean = false,
    val fileRef: String? = null,
    val fileName: String? = null,
    val fileSize: Long = -1,
    val time: Long = System.currentTimeMillis()
) {
    val hour: String get() = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))
}

/**
 * Liaison unique avec le correspondant, quel que soit le transport (Wi-Fi ou Bluetooth).
 * Protocole : trames [type: 1 octet][longueur: 4 octets][données].
 */
object Link {
    const val T_HELLO = 1
    const val T_TEXT = 2
    const val T_FILE_START = 3
    const val T_FILE_CHUNK = 4
    const val T_FILE_END = 5
    const val T_CALL_REQ = 6
    const val T_CALL_ACCEPT = 7
    const val T_CALL_REJECT = 8
    const val T_CALL_END = 9
    const val T_AUDIO = 10
    const val T_BYE = 11

    const val WIFI = "Wi-Fi"
    const val BT = "Bluetooth"

    enum class Call { IDLE, RINGING_IN, RINGING_OUT, ACTIVE }

    interface Listener {
        fun onUpdate()
        fun onProgress(text: String?)
        fun onDisconnected(reason: String)
    }

    @Volatile var connected = false; private set
    @Volatile var transport = ""; private set
    @Volatile var peerName = "Correspondant"; private set
    @Volatile var call = Call.IDLE; private set
    @Volatile var callStartedAt = 0L; private set
    var lastReason = ""; private set
    var progressText: String? = null; private set

    /** Historique : lu et modifié uniquement sur le fil principal. */
    val history = ArrayList<Msg>()
    var listener: Listener? = null

    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val writeLock = Any()
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var closer: (() -> Unit)? = null
    @Volatile private var generation = 0
    private val incoming = HashMap<Int, Incoming>()
    private var nextFileId = 1

    private class Incoming(val name: String, val size: Long, val target: FileStore.Target) {
        var received = 0L
        var lastPct = -1
    }

    val audioRate: Int get() = if (transport == WIFI) 16000 else 8000

    // ------------------------------------------------------------------ connexion

    fun attach(ctx: Context, input: InputStream, output: OutputStream, via: String, myName: String, close: () -> Unit) {
        app = ctx.applicationContext
        shutdown(sendBye = false)
        var gen = 0
        synchronized(this) {
            generation++
            gen = generation
            transport = via
            closer = close
            out = DataOutputStream(BufferedOutputStream(output, 65536))
            call = Call.IDLE
            peerName = "Correspondant"
            lastReason = ""
            connected = true
        }
        ui {
            history.clear()
            history.add(Msg(false, "🔗 Connecté par $via", system = true))
            listener?.onUpdate()
        }
        Thread({ readLoop(DataInputStream(BufferedInputStream(input, 65536)), gen) }, "link-read").start()
        sendAsync(T_HELLO, myName.toByteArray())
    }

    /** Fermeture volontaire (l'utilisateur quitte la conversation). */
    fun leave() = shutdown(sendBye = true)

    private fun shutdown(sendBye: Boolean) {
        var o: DataOutputStream? = null
        synchronized(this) {
            if (!connected) return
            connected = false
            generation++
            o = out
        }
        val os = o
        if (sendBye && os != null) {
            sender.execute {
                try { synchronized(writeLock) { os.writeByte(T_BYE); os.writeInt(0); os.flush() } } catch (_: Exception) {}
                try { Thread.sleep(150) } catch (_: Exception) {}
                cleanup()
            }
        } else cleanup()
    }

    private fun drop(reason: String, gen: Int) {
        synchronized(this) {
            if (gen != generation || !connected) return
            connected = false
            generation++
        }
        cleanup()
        lastReason = reason
        ui {
            history.add(Msg(false, "⚠ $reason", system = true))
            listener?.onUpdate()
            listener?.onDisconnected(reason)
        }
    }

    private fun cleanup() {
        if (call != Call.IDLE) { call = Call.IDLE; app?.let { CallEngine.stop(it) } }
        synchronized(incoming) {
            for (f in incoming.values) app?.let { FileStore.abort(it, f.target) }
            incoming.clear()
        }
        try { closer?.invoke() } catch (_: Exception) {}
        closer = null
        out = null
        progress(null)
    }

    // ------------------------------------------------------------------ réception

    private fun readLoop(din: DataInputStream, gen: Int) {
        try {
            while (true) {
                val type = din.readUnsignedByte()
                val len = din.readInt()
                if (len < 0 || len > 4_000_000) throw IOException("trame invalide")
                val buf = ByteArray(len)
                din.readFully(buf)
                if (gen != generation) return
                if (type == T_BYE) { drop("Le correspondant a quitté la conversation.", gen); return }
                handle(type, buf)
            }
        } catch (e: Exception) {
            drop("Connexion interrompue.", gen)
        }
    }

    private fun handle(type: Int, b: ByteArray) {
        val ctx = app ?: return
        when (type) {
            T_HELLO -> { peerName = String(b).ifBlank { "Correspondant" }; ui { listener?.onUpdate() } }
            T_TEXT -> add(Msg(false, String(b)))
            T_FILE_START -> {
                val p = String(b).split('\n', limit = 3)
                if (p.size < 3) return
                val id = p[0].toIntOrNull() ?: return
                val size = p[1].toLongOrNull() ?: -1L
                val name = p[2]
                try {
                    val t = FileStore.create(ctx, name)
                    synchronized(incoming) { incoming[id] = Incoming(name, size, t) }
                    progress("⬇ Réception de « $name »…")
                } catch (e: Exception) {
                    addSystem("Réception impossible de « $name » : ${e.message}")
                }
            }
            T_FILE_CHUNK -> {
                if (b.size < 4) return
                val id = ByteBuffer.wrap(b, 0, 4).int
                val f = synchronized(incoming) { incoming[id] } ?: return
                try {
                    f.target.out.write(b, 4, b.size - 4)
                } catch (e: Exception) {
                    synchronized(incoming) { incoming.remove(id) }
                    FileStore.abort(ctx, f.target)
                    addSystem("Erreur d'écriture de « ${f.name} » : ${e.message}")
                    progress(null)
                    return
                }
                f.received += b.size - 4
                if (f.size > 0) {
                    val pct = (f.received * 100 / f.size).toInt()
                    if (pct != f.lastPct) { f.lastPct = pct; progress("⬇ Réception de « ${f.name} » : $pct %") }
                } else progress("⬇ Réception de « ${f.name} » : ${FileStore.size(f.received)}")
            }
            T_FILE_END -> {
                if (b.size < 4) return
                val id = ByteBuffer.wrap(b).int
                val f = synchronized(incoming) { incoming.remove(id) } ?: return
                FileStore.finish(ctx, f.target)
                progress(null)
                add(Msg(false, f.name, fileRef = f.target.ref, fileName = f.name, fileSize = f.received))
            }
            T_CALL_REQ -> {
                if (call != Call.IDLE) { sendAsync(T_CALL_REJECT); return }
                call = Call.RINGING_IN
                ui { listener?.onUpdate() }
            }
            T_CALL_ACCEPT -> if (call == Call.RINGING_OUT) beginCall()
            T_CALL_REJECT -> if (call == Call.RINGING_OUT) {
                call = Call.IDLE
                addSystem("📵 $peerName n'a pas décroché (appel refusé ou occupé).")
            }
            T_CALL_END -> endCall()
            T_AUDIO -> CallEngine.play(b)
        }
    }

    // ------------------------------------------------------------------ envoi

    /** Envoi bloquant d'une trame (à appeler hors du fil principal). */
    fun send(type: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): Boolean {
        val o = out ?: return false
        val gen = generation
        return try {
            synchronized(writeLock) {
                o.writeByte(type); o.writeInt(len); o.write(payload, off, len); o.flush()
            }
            true
        } catch (e: IOException) {
            drop("Connexion interrompue.", gen)
            false
        }
    }

    fun sendAsync(type: Int, payload: ByteArray = ByteArray(0)) {
        if (!connected) return
        sender.execute { send(type, payload) }
    }

    fun sendText(t: String) {
        if (!connected || t.isBlank()) return
        sendAsync(T_TEXT, t.toByteArray())
        add(Msg(true, t))
    }

    fun sendFile(ctx: Context, uri: Uri) {
        if (!connected) return
        val cr = ctx.contentResolver
        Thread({ doSendFile(cr, uri) }, "file-send").start()
    }

    private fun doSendFile(cr: android.content.ContentResolver, uri: Uri) {
        var name = "fichier"
        var size = -1L
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        val id = synchronized(this) { nextFileId++ }
        try {
            val ins = cr.openInputStream(uri) ?: throw IOException("lecture impossible")
            var sent = 0L
            ins.use {
                if (!send(T_FILE_START, "$id\n$size\n$name".toByteArray())) return
                val chunk = if (transport == BT) 8192 else 32768
                val buf = ByteArray(4 + chunk)
                ByteBuffer.wrap(buf).putInt(id)
                var last = -1
                while (true) {
                    val n = it.read(buf, 4, chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    if (!send(T_FILE_CHUNK, buf, 0, 4 + n)) return
                    sent += n
                    if (size > 0) {
                        val pct = (sent * 100 / size).toInt()
                        if (pct != last) { last = pct; progress("⬆ Envoi de « $name » : $pct %") }
                    } else progress("⬆ Envoi de « $name » : ${FileStore.size(sent)}")
                }
                send(T_FILE_END, ByteBuffer.allocate(4).putInt(id).array())
            }
            progress(null)
            add(Msg(true, name, fileRef = uri.toString(), fileName = name, fileSize = sent))
        } catch (e: Exception) {
            progress(null)
            addSystem("Échec de l'envoi de « $name » : ${e.message}")
        }
    }

    // ------------------------------------------------------------------ appels

    fun startOutgoingCall() {
        if (!connected || call != Call.IDLE) return
        call = Call.RINGING_OUT
        sendAsync(T_CALL_REQ)
        ui { listener?.onUpdate() }
    }

    fun acceptCall() {
        if (call != Call.RINGING_IN) return
        sendAsync(T_CALL_ACCEPT)
        beginCall()
    }

    fun rejectCall() {
        if (call != Call.RINGING_IN) return
        call = Call.IDLE
        sendAsync(T_CALL_REJECT)
        addSystem("📵 Appel de $peerName refusé.")
    }

    fun hangUp() {
        if (call == Call.IDLE) return
        sendAsync(T_CALL_END)
        endCall()
    }

    private fun beginCall() {
        call = Call.ACTIVE
        callStartedAt = System.currentTimeMillis()
        app?.let { CallEngine.start(it, audioRate) }
        addSystem("📞 Appel vocal démarré")
    }

    private fun endCall() {
        val was = call
        if (was == Call.IDLE) return
        call = Call.IDLE
        app?.let { CallEngine.stop(it) }
        when (was) {
            Call.ACTIVE -> addSystem("📞 Appel terminé (${duration()})")
            Call.RINGING_IN -> addSystem("📵 Appel manqué de $peerName")
            Call.RINGING_OUT -> addSystem("📵 Appel annulé")
            else -> {}
        }
    }

    fun duration(): String {
        val s = ((System.currentTimeMillis() - callStartedAt) / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    // ------------------------------------------------------------------ utilitaires

    private fun ui(f: () -> Unit) { main.post(f) }

    private fun add(m: Msg) = ui { history.add(m); listener?.onUpdate() }

    private fun addSystem(t: String) = add(Msg(false, t, system = true))

    private fun progress(t: String?) = ui { progressText = t; listener?.onProgress(t) }
}
