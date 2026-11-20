package ci.bsinaly.callmagic

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Une liaison avec un autre téléphone (Wi-Fi, Bluetooth, Internet).
 * Sécurité : poignée de main où chacun prouve son identité (signature ECDSA de sa clé éphémère),
 * puis chaque trame est chiffrée et authentifiée (AES-256-GCM, compteur anti-rejeu).
 * Toute trame modifiée, rejouée ou injectée coupe la liaison.
 */
open class Peer(
    val transport: String,
    val address: String,
    val outgoing: Boolean,
    input: InputStream,
    output: OutputStream,
    private val closer: () -> Unit
) {
    @Volatile var deviceId = ""
    @Volatile var caps = 0
    @Volatile var version = 0
    /** Clé d'identité PROUVÉE pendant la poignée de main. */
    @Volatile var pubKey: ByteArray? = null
    /** Enveloppes que ce correspondant possède déjà (facteur). */
    val knownEnvelopes: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile var name = address
    @Volatile var alive = true
        private set

    private val din = DataInputStream(BufferedInputStream(input, 65536))
    private val out = DataOutputStream(BufferedOutputStream(output, 65536))
    private val lock = Any()
    private val ready = CountDownLatch(1)
    private var sendKey: SecretKeySpec? = null
    private var recvKey: SecretKeySpec? = null
    private var sendCtr = 0L
    private var recvCtr = 0L
    private val enc: Cipher by lazy { Cipher.getInstance("AES/GCM/NoPadding") }
    private val dec: Cipher by lazy { Cipher.getInstance("AES/GCM/NoPadding") }

    val secured: Boolean get() = ready.count == 0L && sendKey != null

    fun start() {
        Thread({ loop() }, "peer-read").start()
        // Sans poignée de main valide en 20 s, la liaison est coupée
        Thread({
            try { if (!ready.await(20, TimeUnit.SECONDS) && alive) close("délai de sécurité dépassé", false) } catch (_: Exception) {}
        }, "peer-watchdog").start()
    }

    private fun loop() {
        try {
            handshake()
            ready.countDown()
            Hub.onPeerSecured(this)
            while (alive) {
                val n = din.readInt()
                if (n < 17 || n > 8_000_100) throw IOException("trame invalide")
                val ct = ByteArray(n)
                din.readFully(ct)
                val pt = try {
                    dec.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, nonce(recvCtr++)))
                    dec.doFinal(ct)
                } catch (e: Exception) { throw IOException("trame falsifiée") }
                val t = pt[0].toInt() and 0xff
                val b = pt.copyOfRange(1, pt.size)
                if (t == Proto.BYE) { close("a quitté", false); return }
                try { Hub.onFrame(this, t, b) } catch (e: IOException) { /* trame mal formée : ignorée */ }
            }
        } catch (e: IncompatibleVersion) {
            close("version différente — mettez BS CALLMagic à jour sur les deux téléphones", false)
        } catch (e: SecurityException) {
            close(e.message ?: "identité invalide", false)
        } catch (e: Exception) {
            close("connexion interrompue", false)
        }
    }

    private class IncompatibleVersion : Exception()

    private fun handshake() {
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val ephPub = eph.public.encoded
        val idPub = Identity.publicKey
        val sig = Identity.sign(MAGIC + ephPub + idPub)
        synchronized(lock) {
            out.write(MAGIC); out.writeByte(Proto.VERSION)
            out.putBytes(ephPub); out.putBytes(idPub); out.putBytes(sig); out.flush()
        }
        val m = ByteArray(4)
        din.readFully(m)
        if (!m.contentEquals(MAGIC)) throw IncompatibleVersion()
        version = din.readUnsignedByte()
        val pe = din.getBytes(1000); val pi = din.getBytes(1000); val ps = din.getBytes(1000)
        if (!Identity.verify(pi, MAGIC + pe + pi, ps)) throw SecurityException("signature d'identité invalide")
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(eph.private); ka.doPhase(Identity.pub(pe), true)
        val secret = ka.generateSecret()
        val (a, b) = if (compare(ephPub, pe) < 0) ephPub to pe else pe to ephPub
        val k = sha(secret, a, b, "BSCM5-session".toByteArray())
        sendKey = SecretKeySpec(sha(k, ephPub), "AES")
        recvKey = SecretKeySpec(sha(k, pe), "AES")
        pubKey = pi
    }

    private fun sha(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256"); for (p in parts) md.update(p); return md.digest()
    }

    private fun compare(x: ByteArray, y: ByteArray): Int {
        for (i in 0 until minOf(x.size, y.size)) { val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff); if (c != 0) return c }
        return x.size - y.size
    }

    private fun nonce(c: Long): ByteArray = ByteBuffer.allocate(12).putInt(0x42534335).putLong(c).array()

    /** Envoi bloquant (hors du fil principal) : attend la fin de la poignée de main. */
    open fun send(type: Int, p: ByteArray, off: Int = 0, len: Int = p.size): Boolean {
        if (!alive) return false
        try { if (!ready.await(15, TimeUnit.SECONDS)) return false } catch (_: Exception) { return false }
        if (!secured) return false
        return try {
            synchronized(lock) { writeFrame(type, p, off, len) }
            true
        } catch (e: Exception) {
            close("connexion interrompue", false)
            false
        }
    }

    private fun writeFrame(type: Int, p: ByteArray, off: Int, len: Int) {
        val pt = ByteArray(1 + len)
        pt[0] = type.toByte()
        System.arraycopy(p, off, pt, 1, len)
        enc.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sendCtr++)))
        val ct = enc.doFinal(pt)
        out.writeInt(ct.size); out.write(ct); out.flush()
    }

    open fun close(reason: String, bye: Boolean) {
        synchronized(this) {
            if (!alive) return
            alive = false
        }
        if (bye && secured) try { synchronized(lock) { writeFrame(Proto.BYE, Proto.EMPTY, 0, 0) } } catch (_: Exception) {}
        try { closer() } catch (_: Exception) {}
        ready.countDown()
        Hub.onPeerClosed(this, reason)
    }

    companion object {
        private val MAGIC = byteArrayOf(0x42, 0x53, 0x43, 0x35)   // « BSC5 »
    }
}
