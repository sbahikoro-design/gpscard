package ci.bsinaly.callmagic

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import java.util.Arrays

/** Transport de la voix : micro -> trames AUDIO (PCM 16 bits mono) -> haut-parleur du correspondant. */
object CallEngine {
    @Volatile private var running = false
    @Volatile private var track: AudioTrack? = null
    @Volatile var muted = false
    @Volatile var speaker = false
        private set

    @Synchronized
    fun start(ctx: Context, rate: Int, frameMs: Int = 20) {
        if (running) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speaker
        } catch (_: Exception) {}

        val outMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("DEPRECATION")
        val t = AudioTrack(
            AudioManager.STREAM_VOICE_CALL, rate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(outMin, rate / 1000 * maxOf(250, frameMs * 6) * 2), AudioTrack.MODE_STREAM
        )
        try { t.play() } catch (_: Exception) {}
        track = t
        running = true

        val inMin = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec: AudioRecord? = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, rate / 5 * 2)
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) { null }
        if (rec == null) return // pas de micro autorisé : écoute seule

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val effects = ArrayList<AudioEffect>()
            try {
                if (AcousticEchoCanceler.isAvailable())
                    AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects.add(it) }
                if (NoiseSuppressor.isAvailable())
                    NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects.add(it) }
            } catch (_: Exception) {}
            try {
                rec.startRecording()
                val frame = ByteArray(rate / 1000 * frameMs * 2)
                while (running) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    if (muted) Arrays.fill(frame, 0, n, 0.toByte())
                    val peer = Hub.callPeer ?: break
                    if (!peer.send(Proto.AUDIO, frame, 0, n)) break
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                effects.forEach { try { it.release() } catch (_: Exception) {} }
            }
        }, "call-mic").start()
    }

    fun play(b: ByteArray) {
        if (!running) return
        val t = track ?: return
        try {
            if (Build.VERSION.SDK_INT >= 23) t.write(b, 0, b.size, AudioTrack.WRITE_NON_BLOCKING)
            else t.write(b, 0, b.size)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stop(ctx: Context) {
        running = false
        val t = track
        track = null
        if (t != null) {
            try { t.pause(); t.flush() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
        muted = false
        speaker = false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        speaker = on
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        } catch (_: Exception) {}
    }
}
