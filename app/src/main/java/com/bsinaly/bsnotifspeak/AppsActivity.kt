package com.bsinaly.bsnotifspeak

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.concurrent.thread

/** Choix des applications dont les notifications seront lues à haute voix. */
class AppsActivity : Activity() {

    private data class AppItem(val pkg: String, val name: String, val icon: Drawable?)

    private lateinit var prefs: Prefs
    private val selected = HashSet<String>()
    private var all: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private lateinit var adapter: Adapter
    private lateinit var count: TextView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        selected.addAll(prefs.apps)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.NUIT)
        }

        // Barre du haut
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF3B1A0C.toInt(), 0xFF7A2C12.toInt()))
        }
        val head = row()
        head.addView(tv("‹", 30f, C.AMBRE, true).apply {
            setPadding(0, 0, dp(14), dp(4))
            setOnClickListener { finish() }
        })
        head.addView(tv("Applications à lire", 21f, C.TEXT, true), weight1())
        top.addView(head)
        count = tv("", 13f, C.AMBRE)
        top.addView(count)
        val search = EditText(this).apply {
            hint = "🔍  Rechercher une application"
            setHintTextColor(C.GREY)
            setTextColor(C.TEXT)
            textSize = 15f
            isSingleLine = true
            background = rounded(C.NUIT, 30, 1, C.LINE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; filter() }
            })
        }
        top.addView(search, matchWrap(dp(12)))
        val quick = row()
        quick.addView(chip("Messageries", false) {
            val installed = all.map { it.pkg }.toSet()
            selected.addAll(Prefs.DEFAULT_APPS.filter { it in installed }); save()
        }, weight1())
        quick.addView(chip("Tout décocher", false) { selected.clear(); save() },
            weight1().apply { leftMargin = dp(8) })
        top.addView(quick, matchWrap(dp(10)))
        root.addView(top, matchWrap())

        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0)
            dividerHeight = dp(6)
            setPadding(dp(10), dp(6), dp(10), dp(20))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ ->
                val item = shown[pos]
                if (!selected.remove(item.pkg)) selected.add(item.pkg)
                save()
            }
        }
        adapter = Adapter()
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val loading = tv("Chargement des applications…", 14f, C.GREY).apply { gravity = Gravity.CENTER; setPadding(0, dp(30), 0, 0) }
        root.addView(loading, 1, matchWrap())
        setContentView(root)
        updateCount()

        thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .mapNotNull { pkg ->
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        AppItem(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
                    } catch (e: Exception) { null }
                }
                .sortedBy { it.name.lowercase() }
            runOnUiThread {
                all = items
                root.removeView(loading)
                filter()
            }
        }
    }

    private fun filter() {
        val q = query.trim().lowercase()
        shown = all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedWith(compareBy({ it.pkg !in selected }, { it.name.lowercase() }))
        adapter.notifyDataSetChanged()
    }

    private fun save() {
        prefs.apps = selected.toSet()
        updateCount()
        adapter.notifyDataSetChanged()
    }

    private fun updateCount() {
        count.text = "${selected.size} application(s) cochée(s) — touchez pour cocher / décocher"
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = shown[position]
            val on = item.pkg in selected
            val rowView = (convertView as? LinearLayout) ?: LinearLayout(this@AppsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(tv("", 15.5f, C.TEXT, true).apply { setPadding(dp(14), 0, dp(8), 0) }, weight1())
                addView(tv("", 13f, C.NUIT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(26), dp(26)))
            }
            rowView.layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
            rowView.background = if (on) rounded(C.CARD2, 16, 1, C.ORANGE) else rounded(C.CARD, 16, 1, C.LINE)
            (rowView.getChildAt(0) as ImageView).setImageDrawable(item.icon)
            (rowView.getChildAt(1) as TextView).text = item.name
            (rowView.getChildAt(2) as TextView).apply {
                text = if (on) "✓" else ""
                background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.ORANGE) }
                else GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), C.GREY) }
            }
            return rowView
        }
    }
}
