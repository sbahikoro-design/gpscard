package ci.bsinaly.callmagic

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Utilitaires d'interface (construite en code, sans dépendance externe). */
object Ui {
    val BG = 0xFF0B1A12.toInt()
    val CARD = 0xFF15291D.toInt()
    val BUBBLE_ME = 0xFF1B5E20.toInt()
    val BUBBLE_THEM = 0xFF22382B.toInt()
    val TEXT = 0xFFF1F5F2.toInt()
    val MUTED = 0xFF9FB3A6.toInt()
    val GREEN = 0xFF2E7D32.toInt()
    val GOLD = 0xFFD4AF37.toInt()
    val BLUE = 0xFF1565C0.toInt()
    val RED = 0xFFC62828.toInt()
    val GREY = 0xFF2F4538.toInt()

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    /** Écran défilant standard ; renvoie le conteneur vertical. */
    fun screen(a: Activity): LinearLayout {
        val scroll = ScrollView(a).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = vertical(a).apply { setPadding(dp(a, 18), dp(a, 22), dp(a, 18), dp(a, 28)) }
        scroll.addView(root)
        a.setContentView(scroll)
        return root
    }

    fun vertical(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

    fun lp(c: Context, w: Int = MATCH, h: Int = WRAP, top: Int = 10) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(c, top) }

    fun text(c: Context, s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(c).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    fun title(c: Context, s: String) = text(c, s, 26f, GOLD, true)

    fun card(c: Context): LinearLayout = vertical(c).apply {
        background = rounded(CARD, dp(c, 14).toFloat())
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
    }

    fun button(c: Context, s: String, color: Int = GREEN, onClick: () -> Unit): Button =
        Button(c).apply {
            text = s; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(color, dp(c, 12).toFloat())
            setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
            minHeight = dp(c, 52)
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    /** Bouton carré à icône (en-tête, envoi…). */
    fun iconButton(c: Context, s: String, color: Int, onClick: () -> Unit): Button =
        Button(c).apply {
            text = s; isAllCaps = false; textSize = 20f
            setTextColor(Color.WHITE)
            background = rounded(color, dp(c, 24).toFloat())
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    fun center(v: TextView) = v.apply { gravity = Gravity.CENTER }

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

    fun show(v: View, visible: Boolean) { v.visibility = if (visible) View.VISIBLE else View.GONE }
}

/** Pseudo de l'utilisateur, affiché chez le correspondant. */
object Prefs {
    fun name(c: Context): String =
        c.getSharedPreferences("bscm", Context.MODE_PRIVATE).getString("name", null)
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    fun setName(c: Context, n: String) =
        c.getSharedPreferences("bscm", Context.MODE_PRIVATE).edit().putString("name", n.trim()).apply()
}
