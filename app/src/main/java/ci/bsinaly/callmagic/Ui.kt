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

    private val FACE_COLORS = intArrayOf(0xFF1B5E20.toInt(), 0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(),
        0xFFE65100.toInt(), 0xFF00838F.toInt(), 0xFF4E342E.toInt(), 0xFF283593.toInt())

    /** Visage d'un contact : sa photo si elle existe, sinon ses initiales sur une couleur. */
    fun avatar(c: Context, name: String, photo: String, sizeDp: Int): View {
        val px = dp(c, sizeDp)
        val f = if (photo.isNotEmpty()) java.io.File(photo) else null
        if (f != null && f.exists()) {
            val bmp = try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
            if (bmp != null) return android.widget.ImageView(c).apply {
                setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD) }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(px, px)
            }
        }
        val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
        return TextView(c).apply {
            text = initials; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            textSize = sizeDp / 3.2f; setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(FACE_COLORS[Math.abs(name.hashCode()) % FACE_COLORS.size]); setStroke(dp(c, 3), GOLD)
            }
            layoutParams = LinearLayout.LayoutParams(px, px)
        }
    }
}

/** Réglages de l'application. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("bscm", Context.MODE_PRIVATE)

    fun name(c: Context): String =
        sp(c).getString("name", null)?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    fun setName(c: Context, n: String) = sp(c).edit().putString("name", n.trim()).apply()

    fun deviceId(c: Context): String =
        sp(c).getString("id", null) ?: java.util.UUID.randomUUID().toString().also { sp(c).edit().putString("id", it).apply() }

    fun interphone(c: Context) = sp(c).getBoolean("interphone", true)
    fun setInterphone(c: Context, v: Boolean) = sp(c).edit().putBoolean("interphone", v).apply()

    fun bubble(c: Context) = sp(c).getBoolean("bubble", true)
    fun setBubble(c: Context, v: Boolean) = sp(c).edit().putBoolean("bubble", v).apply()

    fun autoJoin(c: Context) = sp(c).getBoolean("autojoin", true)
    fun setAutoJoin(c: Context, v: Boolean) = sp(c).edit().putBoolean("autojoin", v).apply()

    /** Partager son Internet : les messages des autres peuvent partir par ma connexion. */
    fun shareInternet(c: Context) = sp(c).getBoolean("share_net", true)
    fun setShareInternet(c: Context, v: Boolean) = sp(c).edit().putBoolean("share_net", v).apply()

    /** Pont SMS : ce téléphone envoie de vrais SMS pour les autres. */
    fun smsBridge(c: Context) = sp(c).getBoolean("sms_bridge", false)
    fun setSmsBridge(c: Context, v: Boolean) = sp(c).edit().putBoolean("sms_bridge", v).apply()
    fun smsQuota(c: Context) = sp(c).getInt("sms_quota", 20)
    fun setSmsQuota(c: Context, v: Int) = sp(c).edit().putInt("sms_quota", v).apply()
    fun lastBridge(c: Context) = sp(c).getString("last_bridge", "") ?: ""
    fun setLastBridge(c: Context, id: String) = sp(c).edit().putString("last_bridge", id).apply()

    /** Numéros prévenus par SMS en cas d'alerte SOS. */
    fun sosNumbers(c: Context) = sp(c).getString("sos_numbers", "") ?: ""
    fun setSosNumbers(c: Context, v: String) = sp(c).edit().putString("sos_numbers", v.trim()).apply()

    /** Mode sans lecture (icônes, visages et voix). */
    fun simpleMode(c: Context) = sp(c).getBoolean("simple", false)
    fun setSimpleMode(c: Context, v: Boolean) = sp(c).edit().putBoolean("simple", v).apply()

    fun raw(c: Context) = sp(c)
}
