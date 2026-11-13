package ci.bsinaly.callmagic

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Enregistrement des fichiers reçus dans Téléchargements/BS CALLMagic. */
object FileStore {
    const val FOLDER = "BS CALLMagic"

    class Target(val out: OutputStream, val ref: String, val pendingUri: Uri?)

    fun create(ctx: Context, rawName: String): Target {
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").ifBlank { "fichier" }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("stockage indisponible")
            val o = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("écriture impossible")
            return Target(BufferedOutputStream(o, 65536), uri.toString(), uri)
        }
        @Suppress("DEPRECATION")
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        return try {
            if (!publicDir.isDirectory && !publicDir.mkdirs()) throw IOException("dossier")
            open(unique(publicDir, name))
        } catch (e: Exception) {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            open(unique(dir, name))
        }
    }

    private fun open(f: File) = Target(BufferedOutputStream(FileOutputStream(f), 65536), Uri.fromFile(f).toString(), null)

    private fun unique(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var f = File(dir, name)
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    fun finish(ctx: Context, t: Target) {
        try { t.out.close() } catch (_: Exception) {}
        val uri = t.pendingUri ?: return
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, v, null, null)
            } catch (_: Exception) {}
        }
    }

    fun abort(ctx: Context, t: Target) {
        try { t.out.close() } catch (_: Exception) {}
        t.pendingUri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
    }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun open(a: Activity, ref: String, name: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(ref), mime(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Ui.toast(a, "Aucune application pour ouvrir ce fichier. Il se trouve dans Téléchargements/$FOLDER.")
        } catch (e: Exception) {
            Ui.toast(a, "Ouverture impossible. Le fichier se trouve dans Téléchargements/$FOLDER.")
        }
    }

    fun size(n: Long): String = when {
        n < 0 -> "taille inconnue"
        n < 1024 -> "$n o"
        n < 1024 * 1024 -> String.format("%.1f Ko", n / 1024.0)
        n < 1024L * 1024 * 1024 -> String.format("%.1f Mo", n / 1048576.0)
        else -> String.format("%.2f Go", n / 1073741824.0)
    }
}
