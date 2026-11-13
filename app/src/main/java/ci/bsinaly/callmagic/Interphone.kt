package ci.bsinaly.callmagic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Mode interphone : lit à voix haute les messages texte reçus et joue automatiquement les vocaux,
 * l'un après l'autre. Sert aussi à écouter un vocal en touchant sa bulle.
 */
object Interphone : TextToSpeech.OnInitListener {
    private sealed class Item {
        class Speak(val text: String) : Item()
        class Play(val path: String) : Item()
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayDeque<Item>()
    private var busy = false
    private var player: MediaPlayer? = null
    private var counter = 0

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val r = t.setLanguage(Locale.FRANCE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.FRENCH)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { busy = false; next() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { busy = false; next() } }
        })
        main.post { ready = true; if (!busy) next() }
    }

    fun speak(text: String) = main.post { queue.add(Item.Speak(text)); if (!busy) next() }

    fun play(path: String) = main.post { queue.add(Item.Play(path)); if (!busy) next() }

    /** Écoute immédiate (bulle touchée) : interrompt ce qui est en cours. */
    fun playNow(path: String) = main.post { stopNow(); queue.addFirst(Item.Play(path)); next() }

    fun stopAll() = main.post { stopNow() }

    private fun stopNow() {
        queue.clear()
        try { tts?.stop() } catch (_: Exception) {}
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        busy = false
    }

    private fun next() {
        if (busy) return
        val item = queue.peekFirst() ?: return
        if (item is Item.Speak && !ready) {
            if (tts == null) queue.pollFirst()
            return // attend l'initialisation du moteur vocal
        }
        queue.pollFirst()
        busy = true
        when (item) {
            is Item.Speak -> {
                val ok = tts?.speak(item.text, TextToSpeech.QUEUE_FLUSH, Bundle(), "bscm${counter++}")
                if (ok != TextToSpeech.SUCCESS) { busy = false; next() }
            }
            is Item.Play -> {
                try {
                    val mp = MediaPlayer()
                    player = mp
                    mp.setDataSource(item.path)
                    mp.setOnCompletionListener { it.release(); if (player === it) player = null; busy = false; next() }
                    mp.setOnErrorListener { m, _, _ -> m.release(); if (player === m) player = null; busy = false; next(); true }
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    player?.release(); player = null
                    busy = false; next()
                }
            }
        }
    }

    fun shutdown() {
        stopNow()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    /** Annonce d'un message reçu, si le mode interphone est actif. */
    fun announce(ctx: Context, m: Msg) {
        if (!Prefs.interphone(ctx) || Hub.call != Hub.Call.IDLE || VoiceRecorder.recording) return
        val priv = m.to.isNotEmpty()
        when (m.kind) {
            Msg.K_TEXT -> speak(if (priv) "Message privé de ${m.origin} : ${m.text}" else "${m.origin} dit : ${m.text}")
            Msg.K_VOICE -> m.fileRef?.let { speak((if (priv) "Vocal privé de " else "Message vocal de ") + m.origin); play(it) }
            Msg.K_FILE -> speak("${m.origin} vous a envoyé un fichier : ${m.fileName}")
        }
    }
}

/** Messages vocaux « appuyer pour parler » (maximum 60 secondes). */
object VoiceRecorder {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    var recording = false
        private set
    /** Appelé quand la durée maximale est atteinte (l'appelant envoie le vocal). */
    var onMax: (() -> Unit)? = null

    fun canRecord(c: Context) = Build.VERSION.SDK_INT < 23 ||
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(c: Context): Boolean {
        if (recording) return true
        if (!canRecord(c)) { Ui.toast(c, "Autorisez le micro dans BS CALLMagic pour envoyer des vocaux."); return false }
        if (Hub.call != Hub.Call.IDLE) { Ui.toast(c, "Impossible pendant un appel."); return false }
        Interphone.stopAll()
        val f = File(c.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setAudioChannels(1)
            r.setMaxDuration(60000)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMax?.invoke()
            }
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = SystemClock.elapsedRealtime(); recording = true
            true
        } catch (e: Exception) {
            f.delete()
            Ui.toast(c, "Micro indisponible : ${e.message}")
            false
        }
    }

    /** Résultat d'un enregistrement valable (fichier m4a, durée en ms). */
    class Recorded(val file: File, val durationMs: Int)

    /** Arrête l'enregistrement ; renvoie null s'il est trop court ou raté. */
    fun finish(c: Context): Recorded? {
        if (!recording) return null
        recording = false
        val r = rec; val f = file
        rec = null; file = null
        val dur = (SystemClock.elapsedRealtime() - startedAt).toInt()
        var ok = true
        try { r?.stop() } catch (e: Exception) { ok = false }
        try { r?.release() } catch (_: Exception) {}
        if (f == null) return null
        if (!ok || dur < 700) {
            f.delete()
            Ui.toast(c, "Trop court : maintenez le bouton pendant que vous parlez.")
            return null
        }
        return Recorded(f, dur)
    }

    fun cancel() {
        if (!recording) return
        recording = false
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        file?.delete()
        rec = null; file = null
    }
}
