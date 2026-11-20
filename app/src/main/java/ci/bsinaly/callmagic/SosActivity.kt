package ci.bsinaly.callmagic

import android.app.AlertDialog
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sirène d'alerte : sonne fort même en mode silencieux (flux « alarme »). */
object SosAlarm {
    private var ring: Ringtone? = null
    private var oldVol = -1

    @Suppress("DEPRECATION")
    fun start(c: Context) {
        if (ring?.isPlaying == true) return
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            oldVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ring = RingtoneManager.getRingtone(c, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (_: Exception) {}
        try { (c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(longArrayOf(0, 600, 300, 600, 300, 1200), 0) } catch (_: Exception) {}
        Interphone.stopAll()
    }

    fun stop() {
        try { ring?.stop() } catch (_: Exception) {}
        ring = null
        val c = Hub.app
        try { (c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() } catch (_: Exception) {}
        if (oldVol >= 0) try {
            (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, oldVol, 0)
        } catch (_: Exception) {}
        oldVol = -1
    }
}

/** Écran rouge des alertes reçues : qui, quoi, où (distance et direction), répondre. */
class SosActivity : BaseActivity() {

    private lateinit var list: LinearLayout
    private val observer: () -> Unit = { render() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Hub.init(this)
        val root = Ui.screen(this)
        (root.parent as? android.view.View)?.setBackgroundColor(0xFF3A0A0A.toInt())
        root.addView(Ui.title(this, "🆘 ALERTES"))
        root.addView(Ui.text(this, "Ces alertes ont voyagé de téléphone en téléphone jusqu'à vous.", 13f, Ui.TEXT))
        root.addView(Ui.button(this, "🔕  Arrêter la sirène", Ui.GREY) { SosAlarm.stop() }, Ui.lp(this, top = 14))
        list = Ui.vertical(this)
        root.addView(list)
        if (Sos.alerts.any { !it.cancelled && System.currentTimeMillis() - it.time < 3600_000 }) SosAlarm.start(this)
    }

    override fun onResume() {
        super.onResume()
        Hub.observe(observer)
        Loc.current(this, 10_000) { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        Hub.unobserve(observer)
    }

    private fun render() {
        list.removeAllViews()
        if (Sos.alerts.isEmpty()) {
            list.addView(Ui.text(this, "Aucune alerte reçue.", 15f, Ui.MUTED), Ui.lp(this, top = 16)); return
        }
        val here = Loc.last
        for (a in Sos.alerts) {
            val card = Ui.card(this).apply { background = Ui.rounded(if (a.cancelled) Ui.CARD else 0xFF5C1010.toInt(), Ui.dp(this@SosActivity, 14).toFloat(), 3, Ui.RED) }
            card.addView(Ui.text(this, (if (a.cancelled) "✅ Annulée — " else "") + a.name, 22f, Ui.TEXT, true))
            card.addView(Ui.text(this, a.type + (if (a.note.isNotBlank()) "\n« ${a.note} »" else ""), 18f, Ui.GOLD, true), Ui.lp(this, top = 4))
            card.addView(Ui.text(this, "Envoyée à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(a.time)), 13f, Ui.MUTED), Ui.lp(this, top = 4))
            val hasPos = !a.lat.isNaN()
            if (hasPos && here != null) {
                val dist = Loc.distance(here.latitude, here.longitude, a.lat, a.lon)
                val dir = Loc.direction(Loc.bearing(here.latitude, here.longitude, a.lat, a.lon))
                card.addView(Ui.text(this, "📍 À ${Loc.fmtDistance(dist)}, $dir", 20f, Ui.TEXT, true), Ui.lp(this, top = 8))
            } else if (hasPos) card.addView(Ui.text(this, "📍 Position connue (précision ${a.acc.toInt()} m)", 15f, Ui.TEXT), Ui.lp(this, top = 8))
            else card.addView(Ui.text(this, "Position inconnue", 15f, Ui.MUTED), Ui.lp(this, top = 8))
            for (r in a.replies) card.addView(Ui.text(this, "🏃 $r", 14f, Ui.TEXT), Ui.lp(this, top = 4))
            if (hasPos) card.addView(Ui.button(this, "🗺  Voir sur la carte", Ui.BLUE) {
                try { startActivity(Loc.mapIntent(a.lat, a.lon, "SOS ${a.name}")) } catch (_: Exception) { Ui.toast(this, "Aucune application de carte installée.") }
            }, Ui.lp(this, top = 10))
            if (!a.cancelled) {
                card.addView(Ui.button(this, "🏃  J'arrive !", Ui.GREEN) {
                    Sos.reply(a, "J'arrive !"); a.replies.add("Vous : J'arrive !"); SosAlarm.stop(); render()
                }, Ui.lp(this, top = 8))
                card.addView(Ui.button(this, "✍  Répondre autre chose", Ui.GREY) { customReply(a) }, Ui.lp(this, top = 8))
                val ct = Contacts.byId(a.origin)
                if (ct != null) card.addView(Ui.button(this, "📞  Appeler ${a.name}", Ui.GREEN) { SosAlarm.stop(); People.call(this, ct, false) }, Ui.lp(this, top = 8))
            }
            list.addView(card, Ui.lp(this, top = 14))
        }
    }

    private fun customReply(a: Sos.Alert) {
        val e = EditText(this).apply { hint = "ex. J'appelle le médecin" }
        AlertDialog.Builder(this).setTitle("Répondre à ${a.name}").setView(e)
            .setPositiveButton("Envoyer") { _, _ ->
                val t = e.text.toString().trim()
                if (t.isNotEmpty()) { Sos.reply(a, t); a.replies.add("Vous : $t"); render() }
            }.setNegativeButton("Annuler", null).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        SosAlarm.stop()
    }

    @Suppress("unused")
    private val g = Gravity.CENTER
}
