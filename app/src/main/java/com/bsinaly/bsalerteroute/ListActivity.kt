package com.bsinaly.bsalerteroute

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Liste des obstacles : modifier le type, le rayon, couper le son, voir sur la carte, supprimer, exporter / importer. */
class ListActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    private val RADII = intArrayOf(0, 50, 80, 120, 150, 200, 300, 500)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(28)) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(button("‹", C.CARD2, C.JAUNE, 22f) { finish() }, lp(dp(52)))
        head.addView(tv("Mes obstacles", 24f, C.JAUNE, true).apply { setPadding(dp(12), 0, 0, 0) }, lp(0, weight = 1f))
        root.addView(head)

        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        acts.addView(button("Exporter", C.CARD2, C.TEXT, 14f) { exportFile() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        acts.addView(button("Partager", C.CARD2, C.TEXT, 14f) { share() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        acts.addView(button("Importer", C.CARD2, C.TEXT, 14f) { importFile() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        acts.addView(button("Tout effacer", C.CARD2, C.ROUGE, 14f) { clearAll() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(acts) }, lp(top = dp(14)))

        root.addView(tv("Triés du plus proche au plus éloigné. Rayon « auto » = rayon conseillé selon le type.", 13f, C.GREY), lp(top = dp(10)))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, lp(top = dp(6)))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        listBox.removeAllViews()
        val me = Live.last
        val all = Store.all(this).sortedBy { o -> me?.distanceTo(o.loc()) ?: -o.created.toFloat() }
        if (all.isEmpty()) {
            listBox.addView(tv("Aucun obstacle pour l'instant.\nDémarrez la surveillance et pointez les obstacles pendant votre premier trajet.", 15f, C.GREY).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(40), dp(8), dp(40))
            })
            return
        }
        all.forEach { listBox.addView(row(it, me?.distanceTo(it.loc()) ?: -1f), lp(top = dp(10))) }
    }

    private fun row(o: Obstacle, d: Float): View {
        val t = Types.of(o.type)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = box(C.CARD, 16, 2, if (o.muted) C.LINE else t.color)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(View(this).apply { background = box(t.color, 7) }, LinearLayout.LayoutParams(dp(14), dp(14)))
        top.addView(tv(t.label + if (o.muted) " (muet)" else "", 17f, C.TEXT, true).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, weight = 1f))
        top.addView(tv(fmtDist(d), 16f, C.JAUNE, true))
        card.addView(top)
        val rad = if (o.radius > 0) "${o.radius} m" else "auto (${t.radius} m)"
        card.addView(tv(
            "Pointé le ${df.format(Date(o.created))} · ±${o.acc.toInt()} m\n" +
            "Rayon : $rad · confirmé ${o.confirms}× · ${o.alerts} alerte${if (o.alerts > 1) "s" else ""}",
            13f, C.GREY
        ), lp(top = dp(6)))

        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b.addView(button("Type", C.CARD2, C.TEXT, 13f) { pickType(o) }, lp(0, weight = 1f))
        b.addView(button("Rayon", C.CARD2, C.TEXT, 13f) { pickRadius(o) }, lp(0, weight = 1f).apply { marginStart = dp(6) })
        b.addView(button(if (o.muted) "Son" else "Muet", C.CARD2, C.TEXT, 13f) {
            Store.update(this, o.id) { it.muted = !it.muted }; render()
        }, lp(0, weight = 1f).apply { marginStart = dp(6) })
        b.addView(button("Carte", C.CARD2, C.TEXT, 13f) { map(o) }, lp(0, weight = 1f).apply { marginStart = dp(6) })
        b.addView(button("✕", C.CARD2, C.ROUGE, 15f) { delete(o) }, lp(dp(48)).apply { marginStart = dp(6) })
        card.addView(b, lp(top = dp(10)))
        return card
    }

    private fun pickType(o: Obstacle) {
        AlertDialog.Builder(this)
            .setTitle("Type d'obstacle")
            .setItems(Types.ALL.map { it.label }.toTypedArray()) { _, i -> Store.update(this, o.id) { it.type = Types.ALL[i].code }; render() }
            .show()
    }

    private fun pickRadius(o: Obstacle) {
        val labels = RADII.map { if (it == 0) "Auto (${Types.of(o.type).radius} m)" else "$it m" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Distance d'alerte")
            .setItems(labels) { _, i -> Store.update(this, o.id) { it.radius = RADII[i] }; render() }
            .show()
    }

    private fun map(o: Obstacle) {
        val label = Uri.encode(Types.of(o.type).label)
        val u = Uri.parse("geo:${o.lat},${o.lon}?q=${o.lat},${o.lon}($label)")
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${o.lat},${o.lon}")))
        }
    }

    private fun delete(o: Obstacle) {
        AlertDialog.Builder(this)
            .setMessage("Supprimer « ${Types.of(o.type).label} » ?")
            .setPositiveButton("Supprimer") { _, _ -> Store.remove(this, o.id); render() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun clearAll() {
        AlertDialog.Builder(this)
            .setTitle("Tout effacer ?")
            .setMessage("Tous les obstacles enregistrés seront supprimés. Pensez à exporter avant.")
            .setPositiveButton("Tout effacer") { _, _ -> Store.clear(this); render() }
            .setNegativeButton("Annuler", null).show()
    }

    // ---- export / import (fichier .json échangeable entre chauffeurs) ----

    private fun exportFile() {
        val name = "obstacles_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.FRANCE).format(Date()) + ".json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
        try { startActivityForResult(i, 11) } catch (_: Exception) { share() }
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Obstacles BS ALERTE ROUTE")
            .putExtra(Intent.EXTRA_TEXT, Store.exportJson(this))
        startActivity(Intent.createChooser(i, "Partager les obstacles"))
    }

    private fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, 12) } catch (_: Exception) {
            Toast.makeText(this, "Aucun gestionnaire de fichiers disponible", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == 11) {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(Store.exportJson(this).toByteArray()) }
                Toast.makeText(this, "Obstacles exportés", Toast.LENGTH_SHORT).show()
            } else if (requestCode == 12) {
                val txt = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val n = Store.importJson(this, txt)
                Toast.makeText(this, "$n obstacle(s) importé(s)", Toast.LENGTH_LONG).show()
                render()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Fichier illisible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
