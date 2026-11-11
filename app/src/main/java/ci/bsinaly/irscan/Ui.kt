package ci.bsinaly.irscan

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

/** Petits utilitaires d'interface (construite en code, sans dépendance externe). */
object Ui {
    val BG = 0xFF0F172A.toInt()
    val CARD = 0xFF1E293B.toInt()
    val TEXT = 0xFFF1F5F9.toInt()
    val MUTED = 0xFF94A3B8.toInt()
    val GREEN = 0xFF16A34A.toInt()
    val BLUE = 0xFF2563EB.toInt()
    val RED = 0xFFDC2626.toInt()
    val AMBER = 0xFFD97706.toInt()
    val GREY = 0xFF334155.toInt()
    val DIM = 0xFF1F2937.toInt()

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

    fun weighted(c: Context, margin: Int = 4) =
        LinearLayout.LayoutParams(0, dp(c, 56), 1f).apply { setMargins(dp(c, margin), dp(c, margin), dp(c, margin), dp(c, margin)) }

    fun text(c: Context, s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(c).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    fun title(c: Context, s: String) = text(c, s, 24f, TEXT, true)

    fun card(c: Context): LinearLayout = vertical(c).apply {
        background = rounded(CARD, dp(c, 14).toFloat())
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
    }

    fun button(c: Context, s: String, color: Int = BLUE, onClick: () -> Unit): Button =
        Button(c).apply {
            text = s; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(color, dp(c, 12).toFloat())
            setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6))
            minHeight = dp(c, 52)
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    fun spacer(c: Context, h: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(c, h)) }

    fun center(v: TextView) = v.apply { gravity = Gravity.CENTER }
}
