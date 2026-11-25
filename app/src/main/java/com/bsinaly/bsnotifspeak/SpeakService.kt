package com.bsinaly.bsnotifspeak

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Service d'écoute des notifications.
 * Une fois l'accès autorisé, Android le démarre tout seul à chaque allumage du téléphone.
 */
class SpeakService : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private var speaker: Speaker? = null
    private val recent = LinkedHashMap<String, Long>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "engine", "voice", "rate", "pitch" -> speaker?.refresh()
            "enabled" -> if (!prefs.enabled) speaker?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.initDefaultApps(packageManager)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        running = true
        if (speaker == null) speaker = Speaker(this)
        prefs.registerListener(prefListener)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        running = false
        try { prefs.unregisterListener(prefListener) } catch (_: Exception) {}
        speaker?.shutdown()
        speaker = null
        // On demande à Android de nous reconnecter
        try { requestRebind(ComponentName(this, SpeakService::class.java)) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running = false
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try { handle(sbn) } catch (_: Exception) { }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (!prefs.enabled) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (pkg !in prefs.apps) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (n.category?.let { it in SKIP_CATEGORIES } == true) return

        if (!canSpeakNow()) return

        val msg = extract(n) ?: return
        val app = appName(pkg)

        // Anti-répétition : même message dans les 2 dernières minutes = ignoré
        val sig = "$pkg|${msg.sender}|${msg.text}"
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > 120_000 }
        if (recent.containsKey(sig)) return
        recent[sig] = now

        val text = clean(msg.text)
        val who = msg.sender
        val where = msg.group
        val phrase = when (prefs.readMode) {
            1 -> listOfNotNull(who?.let { if (where != null) "$it, dans $where" else it }, text)
                .joinToString(" : ")
            2 -> buildString {
                append("Nouveau message ").append(app)
                if (who != null) append(" de ").append(who)
                if (where != null) append(", dans ").append(where)
            }
            else -> buildString {
                append(app).append(". ")
                if (who != null) {
                    append(who)
                    if (where != null) append(", dans ").append(where)
                    append(" dit : ")
                }
                append(text)
            }
        }
        if (phrase.isBlank()) return

        val sp = speaker ?: Speaker(this).also { speaker = it }
        if (sp.ready) sp.say(phrase)
        else {
            // Le moteur se prépare encore (juste après l'allumage) : on réessaie un peu plus tard
            android.os.Handler(mainLooper).postDelayed({ speaker?.say(phrase) }, 2500)
        }

        val hour = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
        prefs.addHistory("$hour · $app · ${who ?: ""}${if (who != null) " : " else ""}${text.take(90)}")
    }

    private fun canSpeakNow(): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (prefs.skipDuringCall &&
            (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
                audio.mode == AudioManager.MODE_RINGTONE)
        ) return false
        if (prefs.respectSilent) {
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
            val filter = try { currentInterruptionFilter } catch (e: Exception) { INTERRUPTION_FILTER_ALL }
            if (filter > INTERRUPTION_FILTER_ALL) return false
        }
        if (prefs.onlyScreenOff) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return false
        }
        if (prefs.quietEnabled) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val from = prefs.quietFrom
            val to = prefs.quietTo
            val inQuiet = if (from <= to) h in from until to else (h >= from || h < to)
            if (inQuiet) return false
        }
        return true
    }

    private data class Msg(val sender: String?, val text: String, val group: String?)

    private fun extract(n: Notification): Msg? {
        val ex: Bundle = n.extras ?: return null
        var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        var body = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        var group: String? = null

        // Messageries modernes (WhatsApp, SMS, Telegram…) : on prend le dernier message reçu
        @Suppress("DEPRECATION")
        val messages: Array<Parcelable>? = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val last = messages.last() as? Bundle
            val t = last?.getCharSequence("text")?.toString()
            if (!t.isNullOrBlank()) {
                body = t
                val sender = senderOf(last)
                if (!sender.isNullOrBlank()) title = sender
            }
            val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val isGroup = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            if (isGroup && !conv.isNullOrBlank()) group = stripCount(conv)
        }

        if (body.isNullOrBlank()) {
            body = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        }
        if (body.isNullOrBlank()) {
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null && lines.isNotEmpty()) body = lines.last().toString()
        }
        if (body.isNullOrBlank()) body = n.tickerText?.toString()
        if (body.isNullOrBlank() && title.isNullOrBlank()) return null

        var sender = title?.let { stripCount(it) }?.takeIf { it.isNotBlank() }
        // WhatsApp : « Famille: Moussa » → groupe Famille, expéditeur Moussa
        if (group == null && sender != null && sender.contains(": ")) {
            val parts = sender.split(": ", limit = 2)
            group = parts[0].trim()
            sender = parts[1].trim()
        }
        if (group != null && group.equals(sender, ignoreCase = true)) group = null
        return Msg(sender, body ?: "", group)
    }

    private fun senderOf(b: Bundle): String? {
        b.getCharSequence("sender")?.toString()?.let { if (it.isNotBlank()) return it }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                @Suppress("DEPRECATION")
                val p = b.getParcelable<android.app.Person>("sender_person")
                p?.name?.toString()?.let { if (it.isNotBlank()) return it }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun stripCount(s: String): String =
        s.replace(Regex("\\s*\\(\\d+\\s*(nouveaux\\s+)?messages?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(\\d+\\)$"), "")
            .trim()

    /** Rend le texte agréable à écouter : liens, émojis, longueur. */
    private fun clean(raw: String): String {
        var s = raw
            .replace(Regex("https?://\\S+|www\\.\\S+"), " un lien ")
            .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}\\uFE0F\\u200D]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val max = prefs.maxChars
        if (s.length > max) {
            val cut = s.substring(0, max)
            val sp = cut.lastIndexOf(' ')
            s = (if (sp > max / 2) cut.substring(0, sp) else cut) + "… message tronqué."
        }
        if (s.isBlank()) s = "un émoji ou une pièce jointe"
        return s
    }

    private fun appName(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) { pkg }

    companion object {
        @Volatile var running = false

        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_CALL, Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_STATUS, Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_ALARM, Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_NAVIGATION
        )
    }
}
