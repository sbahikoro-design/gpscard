package ci.bsinaly.callmagic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Vidéo : caméra -> images JPEG compressées -> correspondant ; réception et affichage. */
@Suppress("DEPRECATION")
object VideoEngine {
    private var cam: Camera? = null
    var front = true
        private set
    @Volatile private var pending: ByteArray? = null
    @Volatile private var senderOn = false
    private var lastCapture = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Rotation supplémentaire choisie par l'utilisateur pour l'image reçue. */
    @Volatile var extraRotation = 0
    var onRemoteFrame: ((Bitmap) -> Unit)? = null

    val running: Boolean get() = cam != null

    fun start(texture: SurfaceTexture): Boolean {
        stopCamera()
        val n = Camera.getNumberOfCameras()
        if (n == 0) return false
        val info = Camera.CameraInfo()
        var id = 0
        for (i in 0 until n) {
            Camera.getCameraInfo(i, info)
            if ((info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) == front) { id = i; break }
        }
        Camera.getCameraInfo(id, info)
        val c = try { Camera.open(id) } catch (e: Exception) { return false }
        return try {
            val p = c.parameters
            val size = p.supportedPreviewSizes?.minByOrNull { abs(it.width - 352) + abs(it.height - 288) } ?: p.previewSize
            p.setPreviewSize(size.width, size.height)
            p.previewFormat = ImageFormat.NV21
            if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true)
                p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            c.parameters = p
            val isFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            c.setDisplayOrientation(if (isFront) (360 - info.orientation % 360) % 360 else info.orientation % 360)
            c.setPreviewTexture(texture)
            val w = size.width
            val h = size.height
            val rot = info.orientation
            val slow = Hub.callPeer?.transport == Proto.BT
            val interval = if (slow) 300L else 80L
            val quality = if (slow) 35 else 55
            c.setPreviewCallback { data, _ ->
                val now = SystemClock.elapsedRealtime()
                if (data != null && pending == null && now - lastCapture >= interval) {
                    lastCapture = now
                    try {
                        val bos = ByteArrayOutputStream(16000)
                        bos.write(rot shr 8); bos.write(rot and 0xff)
                        YuvImage(data, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, bos)
                        pending = bos.toByteArray()
                        ensureSender()
                    } catch (_: Exception) {}
                }
            }
            c.startPreview()
            cam = c
            true
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            false
        }
    }

    private fun ensureSender() {
        if (senderOn) return
        senderOn = true
        Thread({
            try {
                while (cam != null && Hub.call == Hub.Call.ACTIVE) {
                    val f = pending
                    if (f == null) { Thread.sleep(10); continue }
                    val p = Hub.callPeer ?: break
                    p.send(Proto.VIDEO, f)
                    pending = null
                }
            } catch (_: Exception) {
            } finally {
                pending = null
                senderOn = false
            }
        }, "video-send").start()
    }

    fun switchCamera(texture: SurfaceTexture): Boolean {
        front = !front
        return start(texture)
    }

    fun stopCamera() {
        val c = cam ?: return
        cam = null
        val release = {
            try { c.setPreviewCallback(null); c.stopPreview() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release() else main.post { release() }
        pending = null
    }

    fun onRemote(b: ByteArray) {
        if (b.size < 3) return
        val rot = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
        val bmp = BitmapFactory.decodeByteArray(b, 2, b.size - 2) ?: return
        val total = (rot + extraRotation) % 360
        val out = if (total != 0)
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(total.toFloat()) }, true)
        else bmp
        main.post { onRemoteFrame?.invoke(out) }
    }
}
