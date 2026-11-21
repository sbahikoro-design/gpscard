package com.bsinaly.bsclean

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service d'accessibilité : ouvre la fiche de chaque appli sélectionnée,
 * entre dans « Stockage » puis appuie sur « Vider le cache ».
 * Sécurité : il n'appuie JAMAIS sur un bouton contenant « stockage / données / storage / data ».
 */
class CleanerService : AccessibilityService() {

    companion object {
        var instance: CleanerService? = null
        val queue = ArrayDeque<String>()
        @Volatile var running = false
        var done = 0
        var total = 0
        var onProgress: ((Int, Int, String) -> Unit)? = null

        fun start(pkgs: List<String>): Boolean {
            val s = instance ?: return false
            queue.clear(); queue.addAll(pkgs)
            total = pkgs.size; done = 0; running = true
            s.next()
            return true
        }

        fun stop() {
            running = false
            queue.clear()
            instance?.cancelAll()
        }

        private val STORAGE_LABELS = listOf(
            "Stockage et cache", "Stockage & cache", "Espace de stockage", "Mémoire de stockage", "Stockage",
            "Storage & cache", "Storage and cache", "Storage usage", "Storage",
            "Almacenamiento", "Armazenamento", "Speicher"
        )
        private val CLEAR_LABELS = listOf(
            "Vider le cache", "Effacer le cache", "Vider la mémoire cache", "Effacer la mémoire cache",
            "Supprimer le cache", "Nettoyer le cache", "Clear cache", "Borrar caché", "Vaciar caché",
            "Limpar cache", "Cache leeren"
        )
        private val FORBIDDEN = listOf("stockage", "storage", "données", "donnees", "data", "datos", "dados", "daten", "désinstaller", "uninstall", "forcer", "force")
        private val NOT_STORAGE = listOf("vider", "effacer", "clear", "supprimer", "borrar", "limpar", "löschen")
    }

    private enum class St { STORAGE, CLEAR, WAIT }

    private val h = Handler(Looper.getMainLooper())
    private var st = St.STORAGE
    private var current: String? = null
    private var attempts = 0
    private val tick = Runnable { act() }
    private val timeout = Runnable { current?.let { finishCurrent(it, false) } }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; running = false; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; running = false; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running || current == null || st == St.WAIT) return
        h.removeCallbacks(tick)
        h.postDelayed(tick, 350)
    }

    fun cancelAll() {
        h.removeCallbacksAndMessages(null)
        current = null
    }

    fun next() {
        h.removeCallbacks(timeout)
        h.removeCallbacks(tick)
        if (!running) return
        val p = queue.removeFirstOrNull()
        if (p == null) { finishAll(); return }
        current = p; st = St.STORAGE; attempts = 0
        onProgress?.invoke(done, total, p)
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            startActivity(i)
        } catch (e: Exception) {
            finishCurrent(p, false); return
        }
        h.postDelayed(tick, 1200)
        h.postDelayed(timeout, 9000)
    }

    private fun act() {
        val pkg = current ?: return
        if (!running) return
        val root = rootInActiveWindow
        if (root == null) { retry(); return }
        when (st) {
            St.STORAGE -> {
                // Certaines surcouches affichent « Vider le cache » directement sur la fiche
                val direct = findClear(root)
                if (direct != null) { pressClear(pkg, direct); return }
                val s = findStorage(root)
                if (s != null && click(s)) {
                    st = St.CLEAR; attempts = 0
                    h.removeCallbacks(tick); h.postDelayed(tick, 900)
                } else retry(root)
            }
            St.CLEAR -> {
                val c = findClear(root)
                if (c != null) pressClear(pkg, c) else retry(root)
            }
            St.WAIT -> {}
        }
    }

    private fun pressClear(pkg: String, node: AccessibilityNodeInfo) {
        st = St.WAIT
        if (!node.isEnabled) { finishCurrent(pkg, true); return } // cache déjà vide
        click(node)
        h.postDelayed({ finishCurrent(pkg, true) }, 900)
    }

    private fun retry(root: AccessibilityNodeInfo? = null) {
        attempts++
        if (attempts > 6) { current?.let { finishCurrent(it, false) }; return }
        if (root != null && attempts >= 2) scrollForward(root)
        h.removeCallbacks(tick)
        h.postDelayed(tick, 600)
    }

    private fun finishCurrent(pkg: String, ok: Boolean) {
        if (current != pkg) return
        if (ok) done++
        current = null
        h.removeCallbacks(tick); h.removeCallbacks(timeout)
        h.postDelayed({ next() }, 350)
    }

    private fun finishAll() {
        running = false
        current = null
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            i.putExtra("autoDone", done)
            i.putExtra("autoTotal", total)
            startActivity(i)
        } catch (_: Exception) {}
    }

    // ---------- Recherche dans l'écran ----------
    private fun label(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription ?: "").toString().lowercase()

    private fun findStorage(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (l in STORAGE_LABELS) {
            val found = root.findAccessibilityNodeInfosByText(l) ?: continue
            for (n in found) {
                val t = label(n)
                if (t.isEmpty() || NOT_STORAGE.any { t.contains(it) }) continue
                return n
            }
        }
        return null
    }

    private fun findClear(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (l in CLEAR_LABELS) {
            val found = root.findAccessibilityNodeInfosByText(l) ?: continue
            for (n in found) {
                val t = label(n)
                if (!t.contains("cache") && !t.contains("caché")) continue
                if (FORBIDDEN.any { t.contains(it) }) continue
                return n
            }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun scrollForward(root: AccessibilityNodeInfo) {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var guard = 0
        while (q.isNotEmpty() && guard < 400) {
            guard++
            val n = q.removeFirst()
            if (n.isScrollable) { n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); return }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
    }
}
