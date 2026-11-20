package ci.bsinaly.callmagic

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.EditText
import android.widget.TextView
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Verrouillage de l'application par code (et empreinte si le téléphone le permet). */
object Lock {
    private const val DELAY = 60_000L          // se reverrouille après 1 min hors de l'application
    @Volatile private var locked = true
    @Volatile private var lastPause = 0L
    @Volatile var failures = 0
    @Volatile var blockedUntil = 0L

    fun enabled(c: Context) = (Prefs.raw(c).getString("lock_hash", "") ?: "").isNotEmpty()

    fun isLocked(c: Context): Boolean =
        enabled(c) && (locked || (lastPause > 0 && System.currentTimeMillis() - lastPause > DELAY))

    fun onPause() { lastPause = System.currentTimeMillis() }
    fun onResumeCheck(a: Activity) {
        if (isLocked(a)) { locked = true; a.startActivity(Intent(a, LockActivity::class.java)) }
        else lastPause = 0L
    }
    fun unlocked() { locked = false; lastPause = 0L; failures = 0 }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20000, 256)).encoded

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Prefs.raw(c).edit().putString("lock_hash",
            Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP)).apply()
        unlocked()
    }

    fun disable(c: Context) { Prefs.raw(c).edit().remove("lock_hash").apply(); unlocked() }

    fun check(c: Context, pin: String): Boolean {
        val v = Prefs.raw(c).getString("lock_hash", "") ?: return false
        val parts = v.split(':'); if (parts.size != 2) return false
        val ok = hash(pin, Base64.decode(parts[0], Base64.NO_WRAP)).contentEquals(Base64.decode(parts[1], Base64.NO_WRAP))
        if (ok) unlocked() else { failures++; if (failures >= 5) { blockedUntil = System.currentTimeMillis() + 30_000L * (failures - 4) } }
        return ok
    }

    fun biometricAvailable(c: Context): Boolean = Build.VERSION.SDK_INT >= 28 &&
        (c.packageManager.hasSystemFeature("android.hardware.fingerprint") ||
         (Build.VERSION.SDK_INT >= 29 && c.packageManager.hasSystemFeature("android.hardware.biometrics.face")))
}

/** Écran de déverrouillage. */
class LockActivity : Activity() {
    private lateinit var pin: EditText
    private lateinit var msg: TextView
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.screen(this)
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(Ui.center(Ui.text(this, "🔒", 64f)), Ui.lp(this, top = 60))
        root.addView(Ui.center(Ui.title(this, "BS CALLMagic")))
        msg = Ui.center(Ui.text(this, "Entrez votre code", 16f, Ui.TEXT))
        root.addView(msg, Ui.lp(this, top = 12))
        pin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 30f; gravity = Gravity.CENTER; setTextColor(Ui.TEXT)
            setOnEditorActionListener { _, _, _ -> tryPin(); true }
        }
        root.addView(pin, Ui.lp(this, top = 16))
        root.addView(Ui.button(this, "Déverrouiller", Ui.GREEN) { tryPin() }, Ui.lp(this, top = 12))
        if (Lock.biometricAvailable(this)) root.addView(Ui.button(this, "👆 Empreinte / visage", Ui.BLUE) { bio() }, Ui.lp(this, top = 10))
        if (Lock.biometricAvailable(this)) h.postDelayed({ bio() }, 400)
    }

    private fun tryPin() {
        val wait = Lock.blockedUntil - System.currentTimeMillis()
        if (wait > 0) { msg.text = "Trop d'essais. Réessayez dans ${wait / 1000 + 1} s."; return }
        if (Lock.check(this, pin.text.toString())) finish()
        else { pin.setText(""); msg.text = "Code incorrect" + if (Lock.failures >= 3) " (${Lock.failures} essais)" else "" }
    }

    private fun bio() {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            val p = android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("BS CALLMagic").setSubtitle("Déverrouiller")
                .setNegativeButton("Utiliser le code", mainExecutor) { _, _ -> }
                .build()
            p.authenticate(CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                    Lock.unlocked(); finish()
                }
            })
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { moveTaskToBack(true) }
}
