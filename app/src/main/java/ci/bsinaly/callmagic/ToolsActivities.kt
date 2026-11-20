package ci.bsinaly.callmagic

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ====================================================================== 📮 Facteur & 🌐 Internet partagé

class PostActivity : BaseActivity() {
    private lateinit var stats: TextView
    private lateinit var bShare: Button
    private lateinit var pending: LinearLayout
    private val observer: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this); HubService.start(this)
        val root = Ui.screen(this)
        root.addView(Ui.title(this, "📮 Le facteur invisible"))
        root.addView(Ui.text(this,
            "Quand une personne est hors de portée, votre message est scellé (chiffré) et confié aux téléphones qui passent. " +
            "Il voyage avec les gens — le car, le commerçant, le voisin — jusqu'à elle. Personne d'autre ne peut le lire. " +
            "Dès qu'un téléphone du chemin a Internet, il part directement.", 13f, Ui.MUTED))
        val c = Ui.card(this)
        stats = Ui.text(this, "", 15f, Ui.TEXT)
        c.addView(stats)
        root.addView(c, Ui.lp(this, top = 14))

        root.addView(Ui.text(this, "🌐 Partage d'Internet", 18f, Ui.GOLD, true), Ui.lp(this, top = 20))
        root.addView(Ui.text(this,
            "Si ce téléphone a des données, il expédie par Internet les messages (scellés) des téléphones voisins qui n'en ont pas. " +
            "Un seul téléphone connecté suffit à relier tout un village au reste du pays. Ça ne consomme que quelques Ko par message.", 13f, Ui.MUTED))
        bShare = Ui.button(this, "", Ui.GREY) { Prefs.setShareInternet(this, !Prefs.shareInternet(this)); render() }
        root.addView(bShare, Ui.lp(this, top = 8))

        root.addView(Ui.text(this, "Mes lettres en route", 18f, Ui.GOLD, true), Ui.lp(this, top = 20))
        pending = Ui.vertical(this)
        root.addView(pending)
        root.addView(Ui.text(this, "Pour envoyer une lettre : ouvrez la conversation d'une personne hors de portée et écrivez normalement. " +
            "L'application choisit le facteur toute seule. Il faut l'avoir rencontrée au moins une fois (pour sa clé de chiffrement).", 12f, Ui.MUTED), Ui.lp(this, top = 14))
    }

    override fun onResume() { super.onResume(); Hub.observe(observer); render() }
    override fun onPause() { super.onPause(); Hub.unobserve(observer) }

    private fun render() {
        stats.text = "🎒 Vous transportez ${Courier.carriedForOthers()} lettre(s) scellée(s) pour d'autres personnes.\n" +
            "✉ ${Courier.mineInTransit()} de vos lettres sont en route.\n" +
            "🤝 Vous avez aidé à livrer ${Courier.helped()} lettre(s).\n" + Relay.statusText()
        val on = Prefs.shareInternet(this)
        bShare.text = "🌐 Partager mon Internet : " + if (on) "ACTIVÉ" else "désactivé"
        bShare.background = Ui.rounded(if (on) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
        pending.removeAllViews()
        val mine = Hub.history.filter { it.mine && it.via == Proto.POST }.takeLast(15).reversed()
        if (mine.isEmpty()) pending.addView(Ui.text(this, "Aucune lettre pour l'instant.", 14f, Ui.MUTED), Ui.lp(this, top = 6))
        for (m in mine) {
            val who = Contacts.nameFor(m.to)
            pending.addView(Ui.text(this, "${m.status.ifEmpty { "📮" }}  →  $who\n« ${m.summary()} » · ${m.hour}", 14f, Ui.TEXT).apply {
                background = Ui.rounded(Ui.CARD, Ui.dp(this@PostActivity, 10).toFloat())
                setPadding(Ui.dp(this@PostActivity, 12), Ui.dp(this@PostActivity, 8), Ui.dp(this@PostActivity, 12), Ui.dp(this@PostActivity, 8))
                setOnClickListener { startActivity(Intent(this@PostActivity, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_TARGET, m.to)) }
            }, Ui.lp(this, top = 8))
        }
    }
}

// ====================================================================== 🌉 Pont SMS

class SmsActivity : BaseActivity() {
    private lateinit var bBridge: Button
    private lateinit var info: TextView
    private lateinit var convs: LinearLayout
    private val observer: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this); HubService.start(this)
        val root = Ui.screen(this)
        root.addView(Ui.title(this, "🌉 Pont SMS"))
        root.addView(Ui.text(this,
            "Écrivez un vrai SMS à n'importe quel numéro — même à quelqu'un qui n'a pas l'application — sans crédit ni réseau : " +
            "votre message voyage jusqu'à un téléphone « pont » qui l'envoie en SMS. Les réponses reviennent ici.", 13f, Ui.MUTED))
        root.addView(Ui.button(this, "✉  Nouveau SMS", Ui.GREEN) { newSms() }, Ui.lp(this, top = 14))

        root.addView(Ui.text(this, "Conversations SMS", 18f, Ui.GOLD, true), Ui.lp(this, top = 20))
        convs = Ui.vertical(this)
        root.addView(convs)

        root.addView(Ui.text(this, "Devenir un pont", 18f, Ui.GOLD, true), Ui.lp(this, top = 24))
        root.addView(Ui.text(this,
            "Votre téléphone envoie les SMS des autres avec votre forfait, avec une limite par jour que vous choisissez. " +
            "Chaque SMS est signé du nom de la personne. Vous recevez une notification à chaque envoi.", 13f, Ui.MUTED))
        bBridge = Ui.button(this, "", Ui.GREY) { toggleBridge() }
        root.addView(bBridge, Ui.lp(this, top = 8))
        info = Ui.text(this, "", 13f, Ui.TEXT)
        root.addView(info, Ui.lp(this, top = 6))
        root.addView(Ui.button(this, "Limite par jour…", Ui.GREY) { quota() }, Ui.lp(this, top = 8))
    }

    override fun onResume() { super.onResume(); Hub.observe(observer); render() }
    override fun onPause() { super.onPause(); Hub.unobserve(observer) }

    private fun render() {
        val on = SmsBridge.active(this)
        bBridge.text = "🌉 Ce téléphone est un pont : " + if (on) "ACTIVÉ" else "désactivé"
        bBridge.background = Ui.rounded(if (on) Ui.GREEN else Ui.GREY, Ui.dp(this, 12).toFloat())
        info.text = "Limite : ${Prefs.smsQuota(this)} SMS / jour · reste ${SmsBridge.remainingToday(this)} aujourd'hui.\n" +
            "Ponts connus : " + SmsBridge.bridges().joinToString(", ") { it.name }.ifEmpty { "aucun pour l'instant" }
        convs.removeAllViews()
        val keys = Hub.history.filter { it.conv.startsWith("sms:") }.map { it.conv }.distinct().reversed()
        if (keys.isEmpty()) convs.addView(Ui.text(this, "Aucune pour l'instant.", 14f, Ui.MUTED), Ui.lp(this, top = 6))
        for (k in keys) {
            val last = Hub.history.last { it.conv == k }
            convs.addView(Ui.button(this, "📱 ${k.removePrefix("sms:")}   ·   ${last.summary().take(30)}", Ui.CARD) {
                startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_TARGET, k))
            }, Ui.lp(this, top = 8))
        }
    }

    private fun toggleBridge() {
        if (SmsBridge.active(this)) { Prefs.setSmsBridge(this, false); render(); return }
        needPerms(listOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)) {
            Prefs.setSmsBridge(this, true); render()
            Ui.toast(this, "Pont activé : les autres le verront dans quelques secondes.")
        }
    }

    private fun quota() {
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(Prefs.smsQuota(this@SmsActivity).toString()) }
        AlertDialog.Builder(this).setTitle("SMS envoyés pour les autres, par jour").setView(e)
            .setPositiveButton("OK") { _, _ -> Prefs.setSmsQuota(this, (e.text.toString().toIntOrNull() ?: 20).coerceIn(0, 500)); render() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun newSms() {
        val box = Ui.vertical(this).apply { setPadding(Ui.dp(this@SmsActivity, 20), 0, Ui.dp(this@SmsActivity, 20), 0) }
        val num = EditText(this).apply { hint = "Numéro (ex. 0707070707)"; inputType = InputType.TYPE_CLASS_PHONE }
        val msg = EditText(this).apply { hint = "Message"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        box.addView(num); box.addView(msg)
        AlertDialog.Builder(this).setTitle("Nouveau SMS").setView(box)
            .setPositiveButton("Envoyer") { _, _ ->
                val n = num.text.toString().trim(); val t = msg.text.toString().trim()
                if (SmsBridge.digits(n).length < 8 || t.isEmpty()) { Ui.toast(this, "Numéro ou message manquant."); return@setPositiveButton }
                People.sendSmsVia(this, n, t) {
                    startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_TARGET, Hub.smsConv(n)))
                }
            }.setNegativeButton("Annuler", null).show()
    }
}

// ====================================================================== 📍 Messages posés

class GeoActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private val observer: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this); HubService.start(this)
        val root = Ui.screen(this)
        root.addView(Ui.title(this, "📍 Messages posés"))
        root.addView(Ui.text(this,
            "Laissez un message à l'endroit où vous êtes. Il voyage jusqu'au destinataire mais reste caché : " +
            "il ne s'ouvre que quand la personne arrive sur place. Un post-it dans le monde réel.", 13f, Ui.MUTED))
        root.addView(Ui.button(this, "📍  Déposer un message ici", Ui.GREEN) { compose() }, Ui.lp(this, top = 14))
        list = Ui.vertical(this)
        root.addView(list)
    }

    override fun onResume() {
        super.onResume()
        Hub.observe(observer)
        needPerms(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { HubService.start(this, HubService.ACTION_REFRESH); Loc.current(this) { render() } }
        render()
    }
    override fun onPause() { super.onPause(); Hub.unobserve(observer) }

    private fun render() {
        list.removeAllViews()
        val all = Geo.all(this).reversed()
        val here = Loc.last
        val waiting = all.filter { !it.mine && !it.opened }
        val opened = all.filter { !it.mine && it.opened }
        val mine = all.filter { it.mine }
        fun section(t: String) = list.addView(Ui.text(this, t, 18f, Ui.GOLD, true), Ui.lp(this, top = 20))
        section("🎁 Messages qui vous attendent quelque part")
        if (waiting.isEmpty()) list.addView(Ui.text(this, "Aucun pour l'instant.", 14f, Ui.MUTED), Ui.lp(this, top = 4))
        for (d in waiting) {
            val where = if (here != null) {
                val dist = Loc.distance(here.latitude, here.longitude, d.lat, d.lon)
                "à ${Loc.fmtDistance(dist)}, ${Loc.direction(Loc.bearing(here.latitude, here.longitude, d.lat, d.lon))}"
            } else "lieu inconnu (activez la localisation)"
            list.addView(card("🔒 De ${d.fromName} — $where\nContenu caché : allez sur place pour l'ouvrir.") {
                try { startActivity(Loc.mapIntent(d.lat, d.lon, "Message de ${d.fromName}")) } catch (_: Exception) {}
            }, Ui.lp(this, top = 8))
        }
        section("📬 Ouverts sur place")
        if (opened.isEmpty()) list.addView(Ui.text(this, "Aucun.", 14f, Ui.MUTED), Ui.lp(this, top = 4))
        for (d in opened) list.addView(card("De ${d.fromName} : « ${d.text} »"), Ui.lp(this, top = 8))
        section("✉ Déposés par moi")
        if (mine.isEmpty()) list.addView(Ui.text(this, "Aucun.", 14f, Ui.MUTED), Ui.lp(this, top = 4))
        for (d in mine) list.addView(card("Pour ${d.toName} · rayon ${d.radius} m · " +
            (if (d.opened) "📍 lu sur place" else "en attente") + "\n« ${d.text} » · " +
            SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(d.created))), Ui.lp(this, top = 8))
    }

    private fun card(t: String, click: (() -> Unit)? = null) = Ui.text(this, t, 14f, Ui.TEXT).apply {
        background = Ui.rounded(Ui.CARD, Ui.dp(this@GeoActivity, 10).toFloat())
        setPadding(Ui.dp(this@GeoActivity, 12), Ui.dp(this@GeoActivity, 10), Ui.dp(this@GeoActivity, 12), Ui.dp(this@GeoActivity, 10))
        if (click != null) setOnClickListener { click() }
    }

    private fun compose() {
        val people = Contacts.all().filter { it.pubKey() != null }
        val names = listOf("👥 Tout le monde") + people.map { "🔒 ${it.name}" }
        AlertDialog.Builder(this).setTitle("Pour qui ?").setItems(names.toTypedArray()) { _, i ->
            val to = if (i == 0) null else people[i - 1]
            val radii = arrayOf("Ici même (30 m)", "Le quartier proche (100 m)", "Les environs (500 m)")
            AlertDialog.Builder(this).setTitle("Il s'ouvrira dans un rayon de…").setItems(radii) { _, r ->
                val radius = intArrayOf(30, 100, 500)[r]
                val e = EditText(this).apply { hint = "ex. La clé est sous le pot de fleurs."; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
                AlertDialog.Builder(this).setTitle("Votre message").setView(e).setPositiveButton("Déposer ici") { _, _ ->
                    val t = e.text.toString().trim()
                    if (t.isEmpty()) return@setPositiveButton
                    Ui.toast(this, "Recherche de votre position…")
                    Geo.drop(this, to, radius, t) { err ->
                        if (err != null) Ui.toast(this, err) else { Ui.toast(this, "📍 Message déposé ici."); render() }
                    }
                }.setNegativeButton("Annuler", null).show()
            }.show()
        }.setNegativeButton("Annuler", null).show()
    }
}
