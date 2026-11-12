package ci.bsinaly.irscan

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pilote l'émetteur infrarouge du téléphone (ou le simule s'il est absent).
 * Les émissions passent par un fil dédié : l'écran ne se fige jamais.
 */
class IrTransmitter(context: Context) {

    enum class Power(val label: String, val extraRepeats: Int, val multiFrequency: Boolean, val detail: String) {
        NORMALE("Normale", 0, false, "1 salve standard"),
        FORTE("Forte", 2, false, "salves répétées"),
        MAXIMALE("Maximale", 4, true, "salves répétées sur 3 fréquences")
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private val pending = AtomicInteger(0)
        private var rc5Toggle = false
    }

    private val app = context.applicationContext
    private val ir: ConsumerIrManager? = app.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val prefs = app.getSharedPreferences("bs_ir_scan", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /** Appelé (sur l'écran) si une émission échoue. */
    var onError: ((String) -> Unit)? = null

    val hasEmitter: Boolean = try { ir?.hasIrEmitter() == true } catch (e: Exception) { false }

    var power: Power
        get() = Power.values().firstOrNull { it.name == prefs.getString("power", null) } ?: Power.FORTE
        set(v) { prefs.edit().putString("power", v.name).apply() }

    private val ranges: List<IntRange> = try {
        (ir?.carrierFrequencies ?: emptyArray()).map { it.minFrequency..it.maxFrequency }
    } catch (e: Exception) { emptyList() }

    fun frequencyInfo(): String =
        if (ranges.isEmpty()) "non communiquées"
        else ranges.joinToString(", ") { "${it.first / 1000}–${it.last / 1000} kHz" }

    /** Ramène une fréquence dans une plage acceptée par le matériel. */
    private fun clamp(f: Int): Int {
        if (ranges.isEmpty() || ranges.any { f in it }) return f
        return ranges.map { r -> if (f < r.first) r.first else r.last }.minByOrNull { kotlin.math.abs(it - f) } ?: f
    }

    val busy: Boolean get() = pending.get() > 0

    /**
     * Prépare et émet le code. Retourne un message si le code est invalide, sinon null.
     * [done] est appelé sur l'écran quand l'émission est terminée.
     * [dropIfBusy] : ignore l'appui si plusieurs émissions sont déjà en attente.
     */
    fun send(spec: String, dropIfBusy: Boolean = false, done: (() -> Unit)? = null): String? {
        val p = power
        val signal = try {
            if (spec.trim().uppercase().startsWith("RC5")) rc5Toggle = !rc5Toggle
            val frame = IrEncoder.frame(spec, rc5Toggle)
            IrEncoder.build(frame, frame.minCount + p.extraRepeats)
        } catch (e: Exception) {
            return e.message ?: "Code invalide"
        }
        if (dropIfBusy && pending.get() >= 2) return null
        buzz()
        pending.incrementAndGet()
        executor.execute {
            var error: String? = null
            try {
                if (hasEmitter) {
                    val base = signal.frequency
                    val freqs = if (p.multiFrequency) listOf(base, base - 2000, base + 2000) else listOf(base)
                    for (f in freqs.map { clamp(it) }.distinct()) {
                        ir!!.transmit(f, signal.pattern)
                        Thread.sleep(40)
                    }
                } else {
                    // Simulation : même durée qu'une vraie émission
                    Thread.sleep((signal.durationUs / 1000).coerceIn(50, 2000))
                }
            } catch (e: Exception) {
                error = "Échec d'émission : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                pending.decrementAndGet()
            }
            main.post {
                error?.let { onError?.invoke(it) }
                done?.invoke()
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun buzz(ms: Long = 25) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Exception) { }
    }
}
