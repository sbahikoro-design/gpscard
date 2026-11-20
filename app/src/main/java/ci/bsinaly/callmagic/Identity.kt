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

    private fun pub(b: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b))

    private fun derive(p: PrivateKey, other: PublicKey, salt: ByteArray): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(p); ka.doPhase(other, true)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ka.generateSecret()); md.update(salt); md.update("BS-CALLMagic-seal-v4".toByteArray())
        return SecretKeySpec(md.digest(), "AES")
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
