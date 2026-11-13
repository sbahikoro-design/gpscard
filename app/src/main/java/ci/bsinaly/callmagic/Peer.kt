package ci.bsinaly.callmagic

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Une connexion directe avec un autre téléphone (Wi-Fi ou Bluetooth). */
class Peer(
    val transport: String,
    val address: String,
    input: InputStream,
    output: OutputStream,
    private val closer: () -> Unit
) {
    @Volatile var deviceId = ""
    @Volatile var name = address
    @Volatile var alive = true
        private set

    private val din = DataInputStream(BufferedInputStream(input, 65536))
    private val out = DataOutputStream(BufferedOutputStream(output, 65536))
    private val lock = Any()

    fun start() = Thread({ loop() }, "peer-read").start()

    private fun loop() {
        try {
            while (alive) {
                val t = din.readUnsignedByte()
                val n = din.readInt()
                if (n < 0 || n > 8_000_000) throw IOException("trame invalide")
                val b = ByteArray(n)
                din.readFully(b)
                if (t == Proto.BYE) { close("a quitté", false); return }
                try { Hub.onFrame(this, t, b) } catch (e: IOException) { /* trame mal formée : ignorée */ }
            }
        } catch (e: Exception) {
            close("connexion interrompue", false)
        }
    }

    /** Envoi bloquant (hors du fil principal). */
    fun send(type: Int, p: ByteArray, off: Int = 0, len: Int = p.size): Boolean {
        if (!alive) return false
        return try {
            synchronized(lock) {
                out.writeByte(type); out.writeInt(len); out.write(p, off, len); out.flush()
            }
            true
        } catch (e: Exception) {
            close("connexion interrompue", false)
            false
        }
    }

    fun close(reason: String, bye: Boolean) {
        synchronized(this) {
            if (!alive) return
            alive = false
        }
        if (bye) try { synchronized(lock) { out.writeByte(Proto.BYE); out.writeInt(0); out.flush() } } catch (_: Exception) {}
        try { closer() } catch (_: Exception) {}
        Hub.onPeerClosed(this, reason)
    }
}
