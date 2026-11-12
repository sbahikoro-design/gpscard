package ci.bsinaly.irscan

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Détecteur de signaux infrarouges par la caméra.
 * Les capteurs photo voient la lumière IR : chaque appui sur une télécommande
 * pointée vers l'objectif produit un éclair que l'application repère.
 * (La caméra ne peut pas décoder le signal, trop rapide, mais elle prouve qu'il existe.)
 */
@Suppress("DEPRECATION")
class IrCameraActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private var camera: Camera? = null
    private var camId = 0
    private var holder: SurfaceHolder? = null
    private var pw = 0
    private var ph = 0
    private var baseline = -1f
    private var hits = 0
    private var lastHit = 0L
    private var lastUi = 0L

    private lateinit var status: TextView
    private lateinit var meter: TextView
    private lateinit var counter: TextView
    private lateinit var ir: IrTransmitter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ir = IrTransmitter(this)

        val frame = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        frame.addView(sv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        val panel = Ui.vertical(this).apply {
            setBackgroundColor(0xDD0F172A.toInt())
            setPadding(Ui.dp(this@IrCameraActivity, 16), Ui.dp(this@IrCameraActivity, 12), Ui.dp(this@IrCameraActivity, 16), Ui.dp(this@IrCameraActivity, 18))
        }
        status = Ui.center(Ui.text(this, "Initialisation de la caméra…", 18f, Ui.TEXT, true))
        status.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10))
        meter = Ui.center(Ui.text(this, "", 14f, Ui.MUTED))
        counter = Ui.center(Ui.text(this, "Éclairs détectés : 0", 13f, Ui.MUTED))
        panel.addView(status)
        panel.addView(meter, Ui.lp(this, top = 4))
        panel.addView(counter, Ui.lp(this, top = 2))
        panel.addView(Ui.text(this,
            "Pointez une télécommande vers l'objectif (à 10–30 cm) et appuyez sur une touche. " +
            "Pour vérifier l'émetteur de CE téléphone, utilisez la caméra d'un autre téléphone. " +
            "Si rien n'est détecté, changez de caméra : la caméra avant filtre souvent moins l'infrarouge.",
            12f, Ui.MUTED), Ui.lp(this, top = 8))
        val row = Ui.horizontal(this)
        row.addView(Ui.button(this, "⇄ Changer de caméra", Ui.BLUE) { switchCamera() }, Ui.weighted(this))
        row.addView(Ui.button(this, "Remise à zéro", Ui.GREY) { hits = 0; baseline = -1f; counter.text = "Éclairs détectés : 0" }, Ui.weighted(this))
        panel.addView(row, Ui.lp(this, top = 8))
        panel.addView(Ui.button(this, "Fermer", Ui.CARD) { finish() }, Ui.lp(this, top = 4))

        frame.addView(panel, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        setContentView(frame)

        camId = frontCameraId() ?: 0
        if (!hasPermission() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    private fun hasPermission() =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermission()) openCamera()
        else status.text = "Autorisation caméra refusée"
    }

    private fun frontCameraId(): Int? {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i
        }
        return null
    }

    private fun switchCamera() {
        val n = Camera.getNumberOfCameras()
        if (n < 2) { status.text = "Une seule caméra disponible"; return }
        release()
        camId = (camId + 1) % n
        baseline = -1f
        openCamera()
    }

    override fun surfaceCreated(h: SurfaceHolder) { holder = h; openCamera() }
    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { }
    override fun surfaceDestroyed(h: SurfaceHolder) { holder = null; release() }

    override fun onPause() { super.onPause(); release() }

    private fun openCamera() {
        val h = holder ?: return
        if (!hasPermission() || camera != null) return
        try {
            val c = Camera.open(camId)
            val params = c.parameters
            val size = params.supportedPreviewSizes
                .filter { it.width >= 320 }
                .minByOrNull { it.width * it.height } ?: params.previewSize
            params.setPreviewSize(size.width, size.height)
            c.parameters = params
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(camId, info)
            val rot = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - info.orientation % 360) % 360 else info.orientation
            c.setDisplayOrientation(rot)
            c.setPreviewDisplay(h)
            c.setPreviewCallback(this)
            c.startPreview()
            camera = c
            pw = size.width; ph = size.height
            val which = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) "avant" else "arrière"
            status.text = "En attente d'un signal… (caméra $which)"
            status.setBackgroundColor(0)
        } catch (e: Exception) {
            status.text = "Caméra indisponible : ${e.message ?: "erreur"}"
            release()
        }
    }

    private fun release() {
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (_: Exception) { }
        camera = null
    }

    /** Analyse chaque image : un éclair IR fait bondir le nombre de pixels saturés. */
    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        if (data == null || pw == 0) return
        val n = minOf(pw * ph, data.size)
        var bright = 0
        var samples = 0
        var i = 0
        while (i < n) {
            if ((data[i].toInt() and 0xFF) >= 235) bright++
            samples++
            i += 3
        }
        if (samples == 0) return
        val score = bright * 1000f / samples // pour mille
        if (baseline < 0) baseline = score
        val delta = score - baseline
        val detected = delta > maxOf(0.5f, baseline * 0.5f)
        if (!detected) baseline = baseline * 0.92f + score * 0.08f

        val now = System.currentTimeMillis()
        if (detected) {
            if (now - lastHit > 300) {
                hits++
                ir.buzz(40)
            }
            lastHit = now
        }
        if (now - lastUi < 120 && !detected) return
        lastUi = now
        val active = now - lastHit < 700
        status.text = if (active) "📶  SIGNAL INFRAROUGE DÉTECTÉ" else "En attente d'un signal…"
        status.setBackgroundColor(if (active) Ui.GREEN else 0)
        val level = (delta.coerceAtLeast(0f) * 4).toInt().coerceIn(0, 20)
        meter.text = "Intensité : " + "█".repeat(level) + "░".repeat(20 - level)
        counter.text = "Éclairs détectés : $hits"
    }
}
