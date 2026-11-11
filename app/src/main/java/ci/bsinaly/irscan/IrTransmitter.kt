package ci.bsinaly.irscan

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Pilote l'émetteur infrarouge du téléphone (ou le simule s'il est absent). */
class IrTransmitter(context: Context) {

    private val ir: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val vibrator: Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var rc5Toggle = false

    val hasEmitter: Boolean = try { ir?.hasIrEmitter() == true } catch (e: Exception) { false }

    /** Plages de fréquences prises en charge, ex. « 30–60 kHz ». */
    fun frequencyInfo(): String = try {
        val ranges = ir?.carrierFrequencies ?: emptyArray()
        if (ranges.isEmpty()) "non communiquées"
        else ranges.joinToString(", ") { "${it.minFrequency / 1000}–${it.maxFrequency / 1000} kHz" }
    } catch (e: Exception) { "non communiquées" }

    /**
     * Émet le code. Retourne null si OK, sinon un message d'erreur.
     * En l'absence d'émetteur, le signal est seulement simulé (vibration).
     */
    fun send(spec: String): String? {
        val signal = try {
            if (spec.trim().uppercase().startsWith("RC5")) rc5Toggle = !rc5Toggle
            IrEncoder.encode(spec, rc5Toggle)
        } catch (e: Exception) {
            return e.message ?: "Code invalide"
        }
        buzz()
        if (!hasEmitter) return null
        return try {
            ir!!.transmit(signal.frequency, signal.pattern)
            null
        } catch (e: Exception) {
            "Échec d'émission : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(25)
        } catch (_: Exception) { }
    }
}
