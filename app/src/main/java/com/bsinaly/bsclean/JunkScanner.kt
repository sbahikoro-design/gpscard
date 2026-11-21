package com.bsinaly.bsclean

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Analyse le stockage partagé (mémoire interne + carte SD) et classe les résidus.
 */
class JunkScanner(private val ctx: Context) {

    enum class Cat(val title: String, val desc: String, val byDefault: Boolean) {
        CACHE_DIRS("Dossiers de cache", "Caches laissés par les applis dans la mémoire partagée", true),
        TEMP("Fichiers temporaires et journaux", ".tmp, .log, .bak, rapports de plantage…", true),
        THUMBS("Miniatures en cache", "Aperçus d'images régénérés automatiquement", true),
        TRASH("Corbeilles et fichiers supprimés", "Fichiers déjà mis à la corbeille", true),
        RESIDUE("Résidus d'applis désinstallées", "Dossiers d'applis qui ne sont plus installées", true),
        APK("Fichiers d'installation APK", "Installateurs déjà utilisés (Téléchargements, WhatsApp…)", true),
        EMPTY("Dossiers vides", "Dossiers qui ne contiennent plus rien", true),
        BIG("Gros fichiers (plus de 100 Mo)", "À vérifier avant suppression : vidéos, archives…", false)
    }

    class Item(val file: File, val size: Long)

    class Result {
        val items = LinkedHashMap<Cat, MutableList<Item>>().apply {
            Cat.values().forEach { put(it, mutableListOf()) }
        }
        var filesSeen = 0
        fun add(c: Cat, f: File, s: Long) { items[c]!!.add(Item(f, s)) }
        fun size(c: Cat): Long = items[c]!!.sumOf { it.size }
        fun count(c: Cat): Int = items[c]!!.size
        fun total(cats: Collection<Cat>): Long = cats.sumOf { size(it) }
    }

    @Volatile var cancelled = false
    private var installed: Set<String> = emptySet()
    private var lastReport = 0L
    private var progress: (String, Int) -> Unit = { _, _ -> }

    companion object {
        private val CACHE_NAMES = setOf("cache", ".cache", "caches", "_cache", "tmp", ".tmp", "temp", ".temp", "temporary")
        private val LOG_DIRS = setOf("log", "logs", ".log", ".logs", "crash", "crashes", "crashlogs", "tombstones", "bugreports")
        private val THUMB_NAMES = setOf(".thumbnails", ".thumbs", "thumbnails", ".thumbcache", ".thumbdata", ".mediathumbs")
        private val TRASH_NAMES = setOf(".trash", ".trashes", ".trashbin", ".recycle", ".recyclebin", "recycle.bin", "\$recycle.bin", ".globaltrash")
        private val TEMP_EXT = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp", "crash", "trace", "stacktrace", "dump")
        private val APK_EXT = setOf("apk", "apks", "xapk", "apkm")
        private val PROTECTED = setOf(
            "android", "android/data", "android/media", "android/obb", "dcim", "download", "downloads",
            "pictures", "movies", "music", "documents", "alarms", "notifications", "ringtones",
            "podcasts", "audiobooks", "recordings", "dcim/camera", "dcim/screenshots", "pictures/screenshots"
        )
        const val BIG_LIMIT = 100L * 1024 * 1024
    }

    fun roots(): List<File> {
        val list = mutableListOf(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = ctx.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { v -> v.directory?.let { if (it !in list) list.add(it) } }
        }
        return list.filter { it.exists() && it.canRead() }
    }

    fun scan(onProgress: (String, Int) -> Unit): Result {
        progress = onProgress
        installed = try {
            ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
        } catch (e: Exception) { emptySet() }
        val res = Result()
        for (r in roots()) {
            if (cancelled) break
            walk(r, res, "", 0)
        }
        return res
    }

    private fun report(res: Result, path: String) {
        val now = System.currentTimeMillis()
        if (now - lastReport > 120) {
            lastReport = now
            progress(path, res.filesSeen)
        }
    }

    private fun walk(dir: File, res: Result, rel: String, depth: Int) {
        if (cancelled || depth > 40) return
        val children = dir.listFiles() ?: return
        if (children.isEmpty()) {
            if (rel.isNotEmpty() && rel !in PROTECTED) res.add(Cat.EMPTY, dir, 0)
            return
        }
        report(res, dir.absolutePath)
        for (f in children) {
            if (cancelled) return
            val lower = f.name.lowercase()
            val childRel = if (rel.isEmpty()) lower else "$rel/$lower"
            if (f.isDirectory) {
                if (childRel == "android/obb") continue
                if (childRel == "android/data" && Build.VERSION.SDK_INT >= 30) continue
                // Dossiers d'applis dans Android/data et Android/media
                if (rel == "android/data" || rel == "android/media") {
                    if (f.name.contains('.') && installed.isNotEmpty() && f.name !in installed && f.name != ctx.packageName) {
                        res.add(Cat.RESIDUE, f, dirSize(f, res))
                        continue
                    }
                    walk(f, res, childRel, depth + 1)
                    continue
                }
                val cat = when {
                    rel.isEmpty() && childRel in PROTECTED -> null
                    lower in THUMB_NAMES -> Cat.THUMBS
                    lower in TRASH_NAMES || lower.startsWith(".trash") -> Cat.TRASH
                    lower in CACHE_NAMES -> Cat.CACHE_DIRS
                    lower in LOG_DIRS -> Cat.TEMP
                    else -> null
                }
                if (cat != null) {
                    val s = dirSize(f, res)
                    if (s > 0 || cat == Cat.TRASH) res.add(cat, f, s) else res.add(Cat.EMPTY, f, 0)
                    continue
                }
                walk(f, res, childRel, depth + 1)
            } else {
                res.filesSeen++
                val len = f.length()
                val ext = lower.substringAfterLast('.', "")
                val cat = when {
                    lower.startsWith(".trashed-") || lower.startsWith(".pending-") && isOld(f) -> Cat.TRASH
                    ext in TEMP_EXT || lower.startsWith("~$") || lower == "thumbs.db" || lower == ".ds_store" -> Cat.TEMP
                    ext in APK_EXT -> Cat.APK
                    len >= BIG_LIMIT -> Cat.BIG
                    else -> null
                }
                if (cat != null) res.add(cat, f, len)
            }
        }
    }

    private fun isOld(f: File) = System.currentTimeMillis() - f.lastModified() > 2L * 24 * 3600 * 1000

    private fun dirSize(root: File, res: Result): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && !cancelled && guard < 500_000) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                guard++
                if (k.isDirectory) stack.add(k) else { total += k.length(); res.filesSeen++ }
            }
        }
        return total
    }

    /** Supprime les éléments des catégories choisies. Retourne (octets libérés, éléments supprimés). */
    fun delete(res: Result, cats: Collection<Cat>, onProgress: (String) -> Unit): Pair<Long, Int> {
        var freed = 0L
        var n = 0
        val scanned = mutableListOf<String>()
        for (c in cats) {
            for (item in res.items[c]!!) {
                if (cancelled) break
                onProgress(item.file.name)
                val ok = try { item.file.deleteRecursively() || !item.file.exists() } catch (e: Exception) { false }
                if (ok) {
                    freed += item.size; n++
                    if (scanned.size < 800) scanned.add(item.file.absolutePath)
                }
            }
            res.items[c]!!.clear()
        }
        if (scanned.isNotEmpty()) {
            try { MediaScannerConnection.scanFile(ctx, scanned.toTypedArray(), null, null) } catch (_: Exception) {}
        }
        return freed to n
    }
}
