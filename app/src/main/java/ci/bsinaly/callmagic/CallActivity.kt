package ci.bsinaly.callmagic

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Écran d'appel audio / vidéo (entrant ou sortant). */
class CallActivity : Activity() {

    companion object {
        const val EXTRA_ACCEPT = "accept"
        private const val REQ_PERMS = 21
    }

    private lateinit var remote: ImageView
    private lateinit var local: TextureView
    private lateinit var avatar: TextView
    private lateinit var nameText: TextView
    private lateinit var stateText: TextView
    private lateinit var incomingRow: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var videoRow: LinearLayout
    private lateinit var bMute: Button
    private lateinit var bSpeaker: Button
    private lateinit var bCam: Button

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var camOn = true
    private var closing = false
    private val observer: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            if (Hub.call == Hub.Call.ACTIVE) {
                val tr = Hub.callPeer?.transport ?: ""
                stateText.text = "${Hub.duration()}  ·  ${Proto.icon(tr)} $tr"
                handler.postDelayed(this, 1000)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Hub.init(this)
        val d = { v: Int -> Ui.dp(this, v) }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF06120B.toInt()) }
        setContentView(root)

        remote = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        root.addView(remote, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        val center = Ui.vertical(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, d(70), 0, 0) }
        avatar = TextView(this).apply {
            textSize = 46f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = Ui.rounded(Ui.GREEN, d(60).toFloat(), d(3), Ui.GOLD)
        }
        center.addView(avatar, LinearLayout.LayoutParams(d(120), d(120)))
        nameText = Ui.text(this, "", 26f, Ui.TEXT, true).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 2f, Color.BLACK) }
        center.addView(nameText, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = d(16) })
        stateText = Ui.text(this, "", 16f, Ui.GOLD).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 2f, Color.BLACK) }
        center.addView(stateText, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = d(6) })
        root.addView(center, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))

        local = TextureView(this)
        root.addView(local, FrameLayout.LayoutParams(d(110), d(150), Gravity.TOP or Gravity.END).apply { setMargins(0, d(16), d(16), 0) })
        local.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { maybeStartCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { VideoEngine.stopCamera(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        val bottom = Ui.vertical(this).apply { setPadding(d(14), 0, d(14), d(30)) }

        incomingRow = Ui.horizontal(this)
        incomingRow.addView(Ui.button(this, "✖  Refuser", Ui.RED) { Hub.reject() },
            LinearLayout.LayoutParams(0, d(64), 1f).apply { rightMargin = d(8) })
        incomingRow.addView(Ui.button(this, "📞  Décrocher", Ui.GREEN) { accept() },
            LinearLayout.LayoutParams(0, d(64), 1f).apply { leftMargin = d(8) })
        bottom.addView(incomingRow)

        controls = Ui.vertical(this)
        videoRow = Ui.horizontal(this)
        bCam = Ui.button(this, "", Ui.GREY) { toggleCam() }
        videoRow.addView(bCam, weight())
        videoRow.addView(Ui.button(this, "🔄 Caméra", Ui.GREY) { switchCam() }, weight())
        videoRow.addView(Ui.button(this, "⟳ Pivoter", Ui.GREY) {
            VideoEngine.extraRotation = (VideoEngine.extraRotation + 90) % 360
        }, weight())
        controls.addView(videoRow)
        val audioRow = Ui.horizontal(this)
        bMute = Ui.button(this, "", Ui.GREY) { CallEngine.muted = !CallEngine.muted; render() }
        bSpeaker = Ui.button(this, "", Ui.GREY) { CallEngine.setSpeaker(this, !CallEngine.speaker); render() }
        audioRow.addView(bMute, weight())
        audioRow.addView(bSpeaker, weight())
        controls.addView(audioRow, Ui.lp(this, top = 8))
        controls.addView(Ui.button(this, "⛔  Raccrocher", Ui.RED) { Hub.hangUp() }, Ui.lp(this, top = 10).apply { height = d(60) })
        bottom.addView(controls)
        root.addView(bottom, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))

        VideoEngine.onRemoteFrame = { bmp ->
            remote.setImageBitmap(bmp)
            Ui.show(avatar, false)
        }
        if (intent?.getBooleanExtra(EXTRA_ACCEPT, false) == true) handler.post { accept() }
    }

    private fun weight() = LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { setMargins(Ui.dp(this@CallActivity, 3), 0, Ui.dp(this@CallActivity, 3), 0) }

    override fun onResume() {
        super.onResume()
        Hub.observe(observer)
        render()
    }

    override fun onPause() {
        super.onPause()
        Hub.unobserve(observer)
        VideoEngine.stopCamera()
        stopTone()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (VideoEngine.onRemoteFrame != null) VideoEngine.onRemoteFrame = null
    }

    private fun render() {
        val c = Hub.call
        if (c == Hub.Call.IDLE) {
            stopTone()
            VideoEngine.stopCamera()
            stateText.text = "Appel terminé"
            Ui.show(incomingRow, false); Ui.show(controls, false); Ui.show(local, false)
            if (!closing) { closing = true; handler.postDelayed({ finish() }, 1200) }
            return
        }
        val p = Hub.callPeer
        val name = p?.name ?: "Correspondant"
        nameText.text = name
        avatar.text = name.trim().take(1).uppercase()
        val video = Hub.callVideo
        Ui.show(incomingRow, c == Hub.Call.RINGING_IN)
        Ui.show(controls, c != Hub.Call.RINGING_IN)
        Ui.show(videoRow, video && c == Hub.Call.ACTIVE)
        Ui.show(local, video && c == Hub.Call.ACTIVE && camOn)
        when (c) {
            Hub.Call.RINGING_IN -> stateText.text = (if (video) "🎥 Appel vidéo entrant" else "📞 Appel vocal entrant") + " · ${p?.transport ?: ""}"
            Hub.Call.RINGING_OUT -> { stateText.text = "Ça sonne…  ·  ${Proto.icon(p?.transport ?: "")} ${p?.transport ?: ""}"; startTone() }
            Hub.Call.ACTIVE -> {
                stopTone()
                handler.removeCallbacks(tick); handler.post(tick)
                maybeStartCamera()
            }
            else -> {}
        }
        bMute.text = if (CallEngine.muted) "🔇 Micro coupé" else "🎤 Micro"
        bSpeaker.text = if (CallEngine.speaker) "🔊 Haut-parleur" else "🔈 Écouteur"
        bCam.text = if (camOn) "📷 Caméra ON" else "📷 Caméra OFF"
        if (!video) Ui.show(avatar, true)
    }

    private fun hasPerm(p: String) = Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun accept() {
        if (Hub.call != Hub.Call.RINGING_IN) return
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Hub.callVideo) wanted.add(Manifest.permission.CAMERA)
        val missing = wanted.filter { !hasPerm(it) }
        if (missing.isEmpty() || Build.VERSION.SDK_INT < 23) Hub.accept()
        else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        HubService.start(this, HubService.ACTION_REFRESH)
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) Ui.toast(this, "Micro refusé : votre correspondant ne vous entendra pas.")
        Hub.accept()
    }

    private fun maybeStartCamera() {
        if (!Hub.callVideo || Hub.call != Hub.Call.ACTIVE || !camOn || VideoEngine.running) return
        if (!hasPerm(Manifest.permission.CAMERA)) return
        val st = local.surfaceTexture ?: return
        if (!VideoEngine.start(st)) Ui.toast(this, "Caméra indisponible.")
    }

    private fun toggleCam() {
        camOn = !camOn
        if (!camOn) VideoEngine.stopCamera()
        render()
    }

    private fun switchCam() {
        val st = local.surfaceTexture ?: return
        if (camOn) VideoEngine.switchCamera(st)
    }

    private fun startTone() {
        if (tone != null) return
        try { tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) } } catch (_: Exception) {}
    }

    private fun stopTone() {
        try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}
        tone = null
    }
}
