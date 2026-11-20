package ci.bsinaly.callmagic

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Identité cryptographique du téléphone (clé EC P-256 permanente).
 * Sert à sceller les enveloppes du facteur : seul le destinataire peut les ouvrir,
 * les téléphones qui les transportent ne voient qu'un bloc illisible.
 */
object Identity {
    private var priv: PrivateKey? = null
    var publicKey: ByteArray = ByteArray(0)
        private set
    private val rnd = SecureRandom()

    fun init(c: Context) {
        if (priv != null) return
        val sp = Prefs.raw(c)
        val pv = sp.getString("id_priv", null)
        val pb = sp.getString("id_pub", null)
        if (pv != null && pb != null) {
            try {
                val kf = KeyFactory.getInstance("EC")
                priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pv, Base64.NO_WRAP)))
                publicKey = Base64.decode(pb, Base64.NO_WRAP)
                return
            } catch (_: Exception) {}
        }
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        priv = kp.private
        publicKey = kp.public.encoded
        sp.edit()
            .putString("id_priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("id_pub", Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .apply()
    }

    fun pub(b: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b))

    private fun derive(p: PrivateKey, other: PublicKey, salt: ByteArray): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(p); ka.doPhase(other, true)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ka.generateSecret()); md.update(salt); md.update("BS-CALLMagic-seal-v4".toByteArray())
        return SecretKeySpec(md.digest(), "AES")
    }

    /** Identifiant d'un téléphone = empreinte de sa clé : impossible à usurper sans la clé privée. */
    fun idOf(pubKey: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(pubKey)
        return h.take(16).joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    }

    /** Emballe [body] avec ma clé publique et ma signature (liée au contexte [ctx]). */
    fun signedPack(body: ByteArray, ctx: ByteArray): ByteArray =
        pack { putBytes(body); putBytes(publicKey); putBytes(sign(body + ctx)) }

    /** Ouvre un paquet signé : renvoie le contenu si la signature est valide et vient de [expectedId]. */
    fun openSigned(b: ByteArray, ctx: ByteArray, expectedId: String?): Pair<ByteArray, ByteArray>? = try {
        val d = DataInputStream(ByteArrayInputStream(b))
        val body = d.getBytes(); val pk = d.getBytes(1000); val sig = d.getBytes(1000)
        if (!verify(pk, body + ctx, sig)) null
        else if (expectedId != null && idOf(pk) != expectedId) null
        else body to pk
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ signatures

    /** Signe [data] avec la clé d'identité de ce téléphone. */
    fun sign(data: ByteArray): ByteArray {
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initSign(priv!!); s.update(data)
        return s.sign()
    }

    /** Vérifie qu'une signature vient bien du propriétaire de [pubKey]. */
    fun verify(pubKey: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub(pubKey)); s.update(data); s.verify(sig)
    } catch (e: Exception) { false }

    private val shared = java.util.concurrent.ConcurrentHashMap<String, SecretKeySpec>()

    /** Clé secrète partagée entre moi et le propriétaire de [other] (ECDH statique), mise en cache. */
    fun sharedKey(other: ByteArray, label: String): SecretKeySpec {
        val k = Base64.encodeToString(other, Base64.NO_WRAP) + "|" + label
        return shared.getOrPut(k) { derive(priv!!, pub(other), label.toByteArray()) }
    }

    /** Code de sécurité à comparer de vive voix : identique sur les deux téléphones. */
    fun safetyCode(a: ByteArray, b: ByteArray): String {
        val (x, y) = if (Base64.encodeToString(a, Base64.NO_WRAP) < Base64.encodeToString(b, Base64.NO_WRAP)) a to b else b to a
        val h = MessageDigest.getInstance("SHA-256").apply { update(x); update(y); update("BSCM-safety".toByteArray()) }.digest()
        val sb = StringBuilder()
        for (i in 0 until 4) {
            val v = ((h[i * 3].toInt() and 0xff) shl 16) or ((h[i * 3 + 1].toInt() and 0xff) shl 8) or (h[i * 3 + 2].toInt() and 0xff)
            if (i > 0) sb.append(' ')
            sb.append(String.format("%04d", v % 10000))
        }
        return sb.toString()
    }

    /** Scelle [plain] pour le propriétaire de [recipientPub]. */
    fun seal(recipientPub: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val key = derive(eph.private, pub(recipientPub), nonce)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return pack { putBytes(eph.public.encoded); write(nonce); putBytes(ct) }
    }

    /** Ouvre une enveloppe scellée pour moi ; null si elle n'est pas pour moi ou a été altérée. */
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray? = try {
        val d = DataInputStream(ByteArrayInputStream(sealed))
        val ephPub = d.getBytes(1000)
        val nonce = ByteArray(12); d.readFully(nonce)
        val ct = d.getBytes()
        val key = derive(priv!!, pub(ephPub), nonce)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        c.doFinal(ct)
    } catch (e: Exception) { null }
}
