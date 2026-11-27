package com.bsinaly.bsalerteroute

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Bips, voix et vibrations. */
class Alerter(private val ctx: Context) {
    private val prefs = Prefs(ctx)
    private val h = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var toneVol = -1
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val r = t.setLanguage(Locale.FRANCE)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.FRENCH)
                t.setSpeechRate(1.05f)
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                ttsReady = true
            }
        }
    }

    private fun gen(): ToneGenerator? {
        val v = prefs.beepVolume.coerceIn(10, 100)
        if (tone == null || toneVol != v) {
            try { tone?.release() } catch (_: Exception) {}
            tone = try { ToneGenerator(AudioManager.STREAM_ALARM, v) } catch (_: Exception) { null }
            toneVol = v
        }
        return tone
    }

    /** n bips, espacés de gapMs. */
    fun beep(n: Int = 3, gapMs: Long = 260, toneType: Int = ToneGenerator.TONE_PROP_BEEP2, durMs: Int = 180) {
        val g = gen() ?: return
        for (i in 0 until n) h.postDelayed({ try { g.startTone(toneType, durMs) } catch (_: Exception) {} }, i * gapMs)
    }

    fun vibrate(pattern: LongArray = longArrayOf(0, 350, 150, 350)) {
        if (!prefs.vibrate) return
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    fun say(text: String, delayMs: Long = 0) {
        if (!prefs.voice || !ttsReady) return
        h.postDelayed({ try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bsar") } catch (_: Exception) {} }, delayMs)
    }

    /** Première alerte : 3 bips + vibration + annonce vocale. */
    fun warn(o: Obstacle, dist: Float) {
        beep(3)
        vibrate()
        val d = ((dist / 10f).toInt() * 10).coerceAtLeast(10)
        say("Attention, ${Types.of(o.type).voice} à $d mètres", 900)
    }

    /** Obstacle tout proche : bips rapides et aigus. */
    fun close() {
        beep(5, 140, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 110)
        vibrate(longArrayOf(0, 120, 80, 120, 80, 120))
    }

    /** Confirmation d'un pointage. */
    fun marked() { beep(1, 0, ToneGenerator.TONE_PROP_ACK, 220); vibrate(longArrayOf(0, 80)) }

    fun release() {
        h.removeCallbacksAndMessages(null)
        try { tone?.release() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tone = null; tts = null
    }
}
