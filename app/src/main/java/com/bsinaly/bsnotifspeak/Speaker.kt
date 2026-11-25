package com.bsinaly.bsnotifspeak

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enveloppe autour de la synthèse vocale d'Android.
 * Les phrases sont mises en file d'attente : rien n'est coupé.
 */
class Speaker(private val ctx: Context, private val onReady: ((Speaker) -> Unit)? = null) {

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pending = AtomicInteger(0)
    private var tts: TextToSpeech? = null
    private var currentEngine: String = ""
    var ready = false
        private set
    private val waiting = ArrayList<Pair<String, Boolean>>()
    private val priorityIds = HashSet<String>()

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()

    init { start() }

    private fun start() {
        ready = false
        currentEngine = prefs.engine
        val listener = TextToSpeech.OnInitListener { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setAudioAttributes(attrs)
                    tts?.setOnUtteranceProgressListener(progress)
                    applySettings()
                    onReady?.invoke(this)
                    val w = ArrayList(waiting); waiting.clear()
                    for ((txt, prio) in w) if (prio) sayPriority(txt) else say(txt)
                } else if (currentEngine.isNotEmpty()) {
                    // Moteur choisi introuvable : on revient au moteur par défaut
                    prefs.engine = ""
                    restart()
                }
            }
        }
        tts = if (currentEngine.isEmpty()) TextToSpeech(ctx.applicationContext, listener)
        else TextToSpeech(ctx.applicationContext, listener, currentEngine)
    }

    fun restart() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        start()
    }

    /** Recharge le moteur si l'utilisateur en a choisi un autre, puis applique voix/vitesse/hauteur. */
    fun refresh() {
        if (prefs.engine != currentEngine) restart() else applySettings()
    }

    private fun applySettings() {
        val t = tts ?: return
        t.setSpeechRate(prefs.rate / 100f)
        t.setPitch(prefs.pitch / 100f)
        val wanted = prefs.voice
        val v = if (wanted.isNotEmpty()) voices().firstOrNull { it.name == wanted } else null
        val auto = if (v == null) voices()
            .filter { Voices.isFrench(it) && !it.isNetworkConnectionRequired }
            .sortedWith(compareBy<Voice>({ if (it.locale.country == "FR") 0 else 1 }, { -it.quality }))
            .firstOrNull() else null
        if (v != null) {
            t.setVoice(v)
        } else if (auto != null) {
            t.setVoice(auto)
        } else {
            // Par défaut : une voix française si possible
            val fr = Locale.FRANCE
            if (t.isLanguageAvailable(fr) >= TextToSpeech.LANG_AVAILABLE) t.setLanguage(fr)
        }
    }

    fun voices(): List<Voice> = try {
        Voices.sort(tts?.voices ?: emptySet())
    } catch (e: Exception) { emptyList() }

    fun engines(): List<TextToSpeech.EngineInfo> = try {
        tts?.engines ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun defaultEngine(): String = try { tts?.defaultEngine ?: "" } catch (e: Exception) { "" }

    fun currentVoice(): Voice? = try { tts?.voice } catch (e: Exception) { null }

    /** Fait parler le téléphone. [flush] = couper ce qui est en cours (bouton « Essayer »). */
    fun say(text: String, flush: Boolean = false) {
        if (text.isBlank()) return
        val t = tts
        if (t == null || !ready) { synchronized(waiting) { waiting.add(text to false) }; return }
        if (pending.getAndIncrement() == 0 || flush) audio.requestAudioFocus(focus)
        if (flush) pending.set(1)
        val params = Bundle()
        val id = "bs" + System.nanoTime()
        val res = t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
        if (res != TextToSpeech.SUCCESS) done()
    }

    /**
     * Annonce prioritaire (paiements) : passe devant les messages ordinaires en cours,
     * mais ne coupe jamais une autre annonce de paiement.
     */
    fun sayPriority(text: String) {
        if (text.isBlank()) return
        val t = tts
        if (t == null || !ready) { synchronized(waiting) { waiting.add(text to true) }; return }
        val mode = if (priorityIds.isEmpty()) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (mode == TextToSpeech.QUEUE_FLUSH) pending.set(0)
        if (pending.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        val id = "pay" + System.nanoTime()
        synchronized(priorityIds) { priorityIds.add(id) }
        if (t.speak(text, mode, Bundle(), id) != TextToSpeech.SUCCESS) { synchronized(priorityIds) { priorityIds.remove(id) }; done() }
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
        pending.set(0)
        audio.abandonAudioFocusRequest(focus)
    }

    private fun forget(id: String?) {
        if (id != null) synchronized(priorityIds) { priorityIds.remove(id) }
    }

    private fun done() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { forget(utteranceId); done() }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { forget(utteranceId); done() }
        override fun onError(utteranceId: String?, errorCode: Int) { forget(utteranceId); done() }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { forget(utteranceId) }
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}
