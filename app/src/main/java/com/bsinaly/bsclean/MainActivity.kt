package com.bsinaly.bsclean

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val tabButtons = mutableListOf<Button>()
    private var tab = 0

    private var scanner: JunkScanner? = null
    private var scanResult: JunkScanner.Result? = null
    private val selectedCats = mutableSetOf<JunkScanner.Cat>()
    private var appCaches: List<AppTools.AppCache>? = null
    private val selectedApps = mutableSetOf<String>()
    @Volatile private var busy = false

    private val prefs by lazy { getSharedPreferences("bsclean", android.content.Context.MODE_PRIVATE) }

    // ======================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.GREEN
        window.navigationBarColor = C.GREEN

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }

        // En-tête
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.GREEN)
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        header.addView(tv("BS CLEAN", 26f, C.GOLD, true))
        header.addView(tv("Nettoyage en profondeur du téléphone", 13f, Color.WHITE))
        root.addView(header)

        // Onglets
        val tabsRow = row().apply {
            setBackgroundColor(C.GREEN2)
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        listOf("Accueil", "Analyse", "Applis", "RAM").forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name; isAllCaps = false; textSize = 14f
                stateListAnimator = null
                setOnClickListener { if (!busy) showTab(i) }
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
            }
            tabButtons.add(b); tabsRow.addView(b)
        }
        root.addView(tabsRow)

        scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(16))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(tv("© BSINALY • 0779687995 • BS CLEAN v1.0", 11f, C.GREY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
        })
        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null || !i.hasExtra("autoDone")) return
        val d = i.getIntExtra("autoDone", 0)
        val t = i.getIntExtra("autoTotal", 0)
        i.removeExtra("autoDone")
        appCaches = null
        tab = 2
        AlertDialog.Builder(this)
            .setTitle("Cache des applis vidé")
            .setMessage("$d application(s) sur $t traitée(s).\nLa liste va être actualisée.")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (CleanerService.running) {
            // L'utilisateur est revenu dans l'appli : on arrête l'automatisme
            CleanerService.stop()
            appCaches = null
            tab = 2
            Toast.makeText(this, "Vidage automatique interrompu", Toast.LENGTH_SHORT).show()
        }
        if (!busy) showTab(tab)
    }

    // ======================================================================
    private fun showTab(i: Int) {
        tab = i
        tabButtons.forEachIndexed { k, b ->
            val on = k == i
            b.background = rounded(if (on) C.GOLD else C.GREEN2, 10)
            b.setTextColor(if (on) C.GREEN else Color.WHITE)
            b.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        content.removeAllViews()
        when (i) {
            0 -> renderHome()
            1 -> renderScan()
            2 -> renderApps()
            3 -> renderRam()
        }
        scroll.scrollTo(0, 0)
    }

    // ============================ ACCUEIL =================================
    private fun renderHome() {
        val (total, free) = AppTools.storage()
        val used = total - free
        val c1 = card()
        c1.addView(tv("Mémoire de stockage", 16f, C.GREEN, true))
        val g = GaugeView(this).apply {
            fraction = if (total > 0) used.toFloat() / total else 0f
            center = "${(fraction * 100).toInt()} %"
            sub = "occupé"
        }
        c1.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(g) })
        c1.addView(tv("Utilisé : ${size(used)}  /  ${size(total)}\nLibre : ${size(free)}", 14f).apply { gravity = Gravity.CENTER })
        content.addView(c1)

        val (rt, ra) = AppTools.ram(this)
        val c2 = card()
        c2.addView(tv("Mémoire vive (RAM)", 16f, C.GREEN, true))
        c2.addView(bar(if (rt > 0) ((rt - ra) * 100 / rt).toInt() else 0))
        c2.addView(tv("Disponible : ${size(ra)} sur ${size(rt)}", 14f))
        content.addView(c2)

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(btn("⚡  NETTOYAGE COMPLET", C.GOLD, C.GREEN) { fullClean() }.apply {
                textSize = 18f
                setPadding(dp(14), dp(18), dp(14), dp(18))
            })
        })

        val c3 = card()
        c3.addView(tv("Autorisations", 16f, C.GREEN, true))
        c3.addView(permRow("Accès à tous les fichiers", "Indispensable pour trouver et supprimer les résidus", AppTools.hasStorage(this)) { askStorage() })
        if (Build.VERSION.SDK_INT >= 26)
            c3.addView(permRow("Accès aux statistiques d'utilisation", "Pour mesurer le cache de chaque appli", AppTools.hasUsage(this)) { askUsage() })
        c3.addView(permRow("Service d'accessibilité BS CLEAN", "Pour vider le cache de toutes les applis automatiquement", AppTools.hasAccessibility(this)) { askAccessibility() })
        content.addView(c3)

        val c4 = card()
        val last = prefs.getLong("last", 0L)
        val sum = prefs.getLong("sum", 0L)
        c4.addView(tv("Bilan", 16f, C.GREEN, true))
        c4.addView(tv(
            if (sum == 0L) "Aucun nettoyage effectué pour l'instant."
            else "Dernier nettoyage : ${size(last)} libérés\nTotal libéré avec BS CLEAN : ${size(sum)}", 14f
        ))
        content.addView(c4)
    }

    private fun bar(pct: Int): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = pct
            progressTintList = android.content.res.ColorStateList.valueOf(if (pct > 85) C.RED else C.GREEN2)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)).apply {
                topMargin = dp(8); bottomMargin = dp(6)
            }
        }

    private fun permRow(title: String, desc: String, ok: Boolean, onFix: () -> Unit): View {
        val r = row().apply { setPadding(0, dp(10), 0, 0) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv((if (ok) "✅ " else "⚠️ ") + title, 14f, C.TEXT, true))
        col.addView(tv(desc, 12f, C.GREY))
        r.addView(col, weight1())
        if (!ok) r.addView(Button(this).apply {
            text = "Activer"; isAllCaps = false; textSize = 13f
            setTextColor(Color.WHITE); background = rounded(C.GREEN, 10); stateListAnimator = null
            setOnClickListener { onFix() }
        })
        return r
    }

    // ---------- Demandes d'autorisation ----------
    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!busy) showTab(tab)
    }

    private fun askUsage() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        toast("Cherchez BS CLEAN dans la liste et autorisez-le")
    }

    private fun askAccessibility() {
        val extra = if (Build.VERSION.SDK_INT >= 33)
            "\n\nSi l'option est grisée (« paramètre restreint ») : ouvrez Paramètres › Applications › BS CLEAN, touchez ⋮ en haut à droite puis « Autoriser les paramètres restreints », et revenez ici."
        else ""
        AlertDialog.Builder(this)
            .setTitle("Activer le vidage automatique")
            .setMessage("Dans l'écran suivant, ouvrez « Applications installées » (ou « Services téléchargés »), touchez « BS CLEAN – vidage automatique du cache » et activez-le.\n\nLe service sert uniquement à appuyer sur « Vider le cache » à votre place. Il ne touche jamais à vos données.$extra")
            .setPositiveButton("Ouvrir") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("Infos de l'appli") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun needStorage(): Boolean {
        if (AppTools.hasStorage(this)) return false
        AlertDialog.Builder(this)
            .setTitle("Autorisation nécessaire")
            .setMessage("BS CLEAN a besoin de l'« accès à tous les fichiers » pour analyser et supprimer les résidus.")
            .setPositiveButton("Autoriser") { _, _ -> askStorage() }
            .setNegativeButton("Annuler", null)
            .show()
        return true
    }

    // ======================== NETTOYAGE COMPLET ============================
    private fun fullClean() {
        if (needStorage()) return
        val (dlg, label) = progressDialog("Analyse en cours…")
        val sc = JunkScanner(this); scanner = sc
        busy = true
        Thread {
            val res = sc.scan { path, n -> ui.post { label.text = "$n fichiers examinés\n${short(path)}" } }
            ui.post {
                dlg.dismiss(); busy = false
                if (sc.cancelled) return@post
                scanResult = res
                selectedCats.clear()
                selectedCats.addAll(JunkScanner.Cat.values().filter { it.byDefault })
                val total = res.total(selectedCats)
                val lines = selectedCats.filter { res.count(it) > 0 }
                    .joinToString("\n") { "• ${it.title} : ${size(res.size(it))}" }
                val big = res.count(JunkScanner.Cat.BIG)
                AlertDialog.Builder(this)
                    .setTitle("Résidus trouvés : ${size(total)}")
                    .setMessage(
                        (if (lines.isEmpty()) "Aucun résidu dans la mémoire partagée." else lines) +
                            "\n\nLa RAM sera aussi libérée." +
                            (if (big > 0) "\n\n$big gros fichier(s) non inclus — à vérifier dans l'onglet Analyse." else "")
                    )
                    .setPositiveButton("Nettoyer") { _, _ -> runDelete(true) }
                    .setNeutralButton("Voir le détail") { _, _ -> showTab(1) }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }.start()
    }

    private fun runDelete(alsoRamAndApps: Boolean) {
        val res = scanResult ?: return
        val sc = scanner ?: JunkScanner(this)
        sc.cancelled = false
        val cats = selectedCats.toList()
        val (dlg, label) = progressDialog("Nettoyage en cours…")
        busy = true
        Thread {
            val (freed, n) = sc.delete(res, cats) { name -> ui.post { label.text = "Suppression : $name" } }
            var extra = AppTools.clearOwnCache(this)
            var ramMsg = ""
            if (alsoRamAndApps) {
                ui.post { label.text = "Libération de la RAM…" }
                val before = AppTools.ram(this).second
                val k = AppTools.killBackground(this)
                Thread.sleep(900)
                val after = AppTools.ram(this).second
                ramMsg = "\nRAM : ${size(maxOf(0L, after - before))} récupérés ($k applis en arrière-plan arrêtées)"
            }
            val totalFreed = freed + extra
            prefs.edit().putLong("last", totalFreed).putLong("sum", prefs.getLong("sum", 0L) + totalFreed).apply()
            ui.post {
                dlg.dismiss(); busy = false
                val b = AlertDialog.Builder(this)
                    .setTitle("✅ ${size(totalFreed)} libérés")
                    .setMessage("$n élément(s) supprimé(s).$ramMsg" +
                        if (alsoRamAndApps) "\n\nPour aller encore plus loin, videz maintenant le cache interne des applications." else "")
                    .setPositiveButton("OK") { _, _ -> showTab(tab) }
                if (alsoRamAndApps && Build.VERSION.SDK_INT >= 26)
                    b.setNeutralButton("Vider le cache des applis") { _, _ -> appCaches = null; showTab(2) }
                b.show()
            }
        }.start()
    }

    // ============================ ANALYSE =================================
    private fun renderScan() {
        val res = scanResult
        if (res == null) {
            val c = card()
            c.addView(tv("Analyse approfondie", 16f, C.GREEN, true))
            c.addView(tv("BS CLEAN parcourt toute la mémoire interne et la carte SD à la recherche de caches, fichiers temporaires, journaux, miniatures, corbeilles, APK déjà installés, dossiers vides, résidus d'applis désinstallées et gros fichiers.", 14f, C.GREY))
            c.addView(btn("🔍  Lancer l'analyse") { startScan() })
            content.addView(c)
            return
        }
        val c = card()
        val sel = res.total(selectedCats)
        c.addView(tv("Résidus sélectionnés : ${size(sel)}", 18f, C.GREEN, true))
        c.addView(tv("${res.filesSeen} fichiers examinés. Touchez une catégorie pour voir son contenu.", 12f, C.GREY))
        content.addView(c)

        for (cat in JunkScanner.Cat.values()) {
            val n = res.count(cat)
            val cc = card()
            val r = row()
            val cb = CheckBox(this).apply {
                isChecked = cat in selectedCats && n > 0
                isEnabled = n > 0
                buttonTintList = android.content.res.ColorStateList.valueOf(C.GREEN)
                setOnCheckedChangeListener { _, on -> if (on) selectedCats.add(cat) else selectedCats.remove(cat); showTab(1) }
            }
            r.addView(cb)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(cat.title, 15f, if (cat == JunkScanner.Cat.BIG) C.RED else C.TEXT, true))
            col.addView(tv("${cat.desc}\n$n élément(s)", 12f, C.GREY))
            r.addView(col, weight1())
            r.addView(tv(size(res.size(cat)), 15f, C.GREEN, true))
            cc.addView(r)
            if (n > 0) cc.setOnClickListener { showDetail(cat, res) }
            content.addView(cc)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        box.addView(btn("🧹  Nettoyer la sélection (${size(sel)})", C.GOLD, C.GREEN) {
            if (selectedCats.isEmpty() || sel == 0L && selectedCats.none { res.count(it) > 0 }) { toast("Rien à nettoyer"); return@btn }
            val warnBig = JunkScanner.Cat.BIG in selectedCats
            AlertDialog.Builder(this)
                .setTitle("Confirmer le nettoyage")
                .setMessage("Supprimer définitivement ${size(sel)} ?" + if (warnBig) "\n\n⚠️ Les gros fichiers sont inclus : ils seront perdus." else "")
                .setPositiveButton("Supprimer") { _, _ -> runDelete(false) }
                .setNegativeButton("Annuler", null).show()
        })
        box.addView(btn("Relancer l'analyse", C.GREEN) { startScan() })
        content.addView(box)
    }

    private fun startScan() {
        if (needStorage()) return
        val (dlg, label) = progressDialog("Analyse en cours…")
        val sc = JunkScanner(this); scanner = sc
        busy = true
        Thread {
            val res = sc.scan { path, n -> ui.post { label.text = "$n fichiers examinés\n${short(path)}" } }
            ui.post {
                dlg.dismiss(); busy = false
                if (!sc.cancelled) {
                    scanResult = res
                    selectedCats.clear()
                    selectedCats.addAll(JunkScanner.Cat.values().filter { it.byDefault && res.count(it) > 0 })
                }
                showTab(1)
            }
        }.start()
    }

    private fun showDetail(cat: JunkScanner.Cat, res: JunkScanner.Result) {
        val items = res.items[cat]!!.sortedByDescending { it.size }
        val sb = StringBuilder()
        items.take(300).forEach { sb.append(size(it.size)).append("  ").append(short(it.file.absolutePath)).append("\n\n") }
        if (items.size > 300) sb.append("… et ${items.size - 300} autre(s)")
        val t = tv(sb.toString(), 12f).apply { setPadding(dp(20), dp(10), dp(20), dp(10)); setTextIsSelectable(true) }
        AlertDialog.Builder(this)
            .setTitle("${cat.title} (${items.size})")
            .setView(ScrollView(this).apply { addView(t) })
            .setPositiveButton("Fermer", null)
            .show()
    }

    // ============================ APPLIS ==================================
    private fun renderApps() {
        if (Build.VERSION.SDK_INT < 26) {
            val c = card()
            c.addView(tv("Votre version d'Android ne permet pas de mesurer le cache des applis. Utilisez l'onglet Analyse et le nettoyage complet.", 14f))
            content.addView(c); return
        }
        if (!AppTools.hasUsage(this)) {
            val c = card()
            c.addView(tv("Cache interne des applications", 16f, C.GREEN, true))
            c.addView(tv("Pour mesurer le cache de chaque appli (WhatsApp, Facebook, Chrome, TikTok…), autorisez l'accès aux statistiques d'utilisation.", 14f, C.GREY))
            c.addView(btn("Autoriser") { askUsage() })
            content.addView(c); return
        }
        val list = appCaches
        if (list == null) {
            val c = card()
            val pb = ProgressBar(this)
            c.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(pb) })
            c.addView(tv("Mesure du cache des applications…", 14f).apply { gravity = Gravity.CENTER })
            content.addView(c)
            Thread {
                val l = AppTools.loadAppCaches(this)
                ui.post {
                    appCaches = l
                    selectedApps.clear()
                    selectedApps.addAll(l.filter { it.pkg != packageName }.map { it.pkg })
                    if (tab == 2 && !busy) showTab(2)
                }
            }.start()
            return
        }
        val total = list.sumOf { it.cache }
        val sel = list.filter { it.pkg in selectedApps }.sumOf { it.cache }
        val c = card()
        c.addView(tv("Cache des applis : ${size(total)}", 18f, C.GREEN, true))
        c.addView(tv("${list.size} applis concernées • sélection : ${size(sel)}", 13f, C.GREY))
        val acc = AppTools.hasAccessibility(this)
        c.addView(btn("🧹  Vider le cache sélectionné (auto)", C.GOLD, C.GREEN) {
            if (!acc) { askAccessibility(); return@btn }
            val pkgs = list.filter { it.pkg in selectedApps && it.pkg != packageName }.map { it.pkg }
            if (pkgs.isEmpty()) { toast("Aucune appli sélectionnée"); return@btn }
            AlertDialog.Builder(this)
                .setTitle("Vidage automatique")
                .setMessage("BS CLEAN va ouvrir les paramètres de ${pkgs.size} appli(s) et appuyer sur « Vider le cache ». Ne touchez pas l'écran pendant l'opération.\n\nVos comptes, messages et photos ne sont PAS supprimés.\n\nPour arrêter : revenez dans BS CLEAN.")
                .setPositiveButton("Démarrer") { _, _ ->
                    AppTools.clearOwnCache(this)
                    if (!CleanerService.start(pkgs)) askAccessibility()
                }
                .setNegativeButton("Annuler", null).show()
        })
        val r2 = row()
        r2.addView(btn("Tout cocher", C.GREEN2) { selectedApps.addAll(list.map { it.pkg }); showTab(2) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(8); rightMargin = dp(4) }
        })
        r2.addView(btn("Tout décocher", C.GREY) { selectedApps.clear(); showTab(2) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(8); leftMargin = dp(4) }
        })
        c.addView(r2)
        c.addView(btn("Actualiser", C.GREEN) { appCaches = null; showTab(2) })
        if (!acc) c.addView(tv("ℹ️ Sans le service d'accessibilité, touchez une appli pour ouvrir sa fiche puis « Stockage › Vider le cache ».", 12f, C.GREY).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(c)

        val lc = card()
        for (a in list) {
            val r = row().apply { setPadding(0, dp(4), 0, dp(4)) }
            r.addView(CheckBox(this).apply {
                isChecked = a.pkg in selectedApps
                buttonTintList = android.content.res.ColorStateList.valueOf(C.GREEN)
                setOnCheckedChangeListener { _, on -> if (on) selectedApps.add(a.pkg) else selectedApps.remove(a.pkg) }
            })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(a.label, 14f, C.TEXT, true))
            col.addView(tv(if (a.system) "Système • ${a.pkg}" else a.pkg, 11f, C.GREY))
            r.addView(col, weight1())
            r.addView(tv(size(a.cache), 14f, C.GREEN, true))
            r.setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
            }
            lc.addView(r)
        }
        content.addView(lc)
    }

    // ============================== RAM ===================================
    private fun renderRam() {
        val (rt, ra) = AppTools.ram(this)
        val c = card()
        c.addView(tv("Mémoire vive", 16f, C.GREEN, true))
        val g = GaugeView(this).apply {
            fraction = if (rt > 0) (rt - ra).toFloat() / rt else 0f
            center = "${(fraction * 100).toInt()} %"
            sub = "utilisée"
        }
        c.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(g) })
        c.addView(tv("Disponible : ${size(ra)} sur ${size(rt)}", 14f).apply { gravity = Gravity.CENTER })
        c.addView(btn("🚀  Libérer la mémoire vive", C.GOLD, C.GREEN) {
            val (dlg, label) = progressDialog("Arrêt des processus en arrière-plan…")
            busy = true
            Thread {
                val before = AppTools.ram(this).second
                val k = AppTools.killBackground(this)
                System.gc()
                Thread.sleep(1000)
                val after = AppTools.ram(this).second
                ui.post {
                    dlg.dismiss(); busy = false
                    AlertDialog.Builder(this)
                        .setTitle("RAM libérée")
                        .setMessage("${size(maxOf(0L, after - before))} récupérés.\n$k applis en arrière-plan traitées.")
                        .setPositiveButton("OK") { _, _ -> showTab(3) }.show()
                }
            }.start()
        })
        c.addView(tv("Android relance certaines applis (messageries, services Google) dès qu'elles en ont besoin : c'est normal.", 12f, C.GREY).apply { setPadding(0, dp(10), 0, 0) })
        content.addView(c)
    }

    // ============================ OUTILS ==================================
    private fun progressDialog(title: String): Pair<AlertDialog, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(ProgressBar(this))
        val label = tv("Préparation…", 13f, C.GREY).apply { setPadding(0, dp(10), 0, 0); maxLines = 4 }
        box.addView(label)
        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Arrêter") { _, _ -> scanner?.cancelled = true; busy = false }
            .create()
        d.show()
        return d to label
    }

    private fun short(p: String): String =
        p.replace("/storage/emulated/0", "Mémoire interne").let { if (it.length > 90) "…" + it.takeLast(88) else it }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
