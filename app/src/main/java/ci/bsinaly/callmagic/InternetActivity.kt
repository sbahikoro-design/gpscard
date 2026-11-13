package ci.bsinaly.callmagic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Internet : mes coordonnées (code BS, IP publiques) à partager, et contacter quelqu'un à partir
 * de son code, de son adresse IP, ou du message qu'il a envoyé (tout est reconnu automatiquement).
 */
class InternetActivity : BaseActivity() {

    private lateinit var status: TextView
    private lateinit var ipText: TextView
    private lateinit var diag: TextView
    private lateinit var input: EditText
    private lateinit var nameIn: EditText
    private lateinit var log: TextView
    @Volatile private var publicV4 = ""
    @Volatile private var publicV6 = ""
    private val observer: () -> Unit = { status.text = Relay.statusText() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        HubService.start(this)
        val root = Ui.screen(this)
        root.addView(Ui.title(this, "🌐 Internet"))
        root.addView(Ui.text(this, "Joignez n'importe qui, n'importe où, du moment que vous avez tous les deux Internet (données mobiles ou Wi-Fi).", 13f, Ui.MUTED))

        // Mes coordonnées
        val me = Ui.card(this)
        me.addView(Ui.text(this, "Mon code BS — fonctionne partout, même derrière une box ou un opérateur", 12f, Ui.MUTED))
        me.addView(Ui.text(this, Contacts.pretty(Hub.myCode()), 26f, Ui.GOLD, true))
        status = Ui.text(this, Relay.statusText(), 13f, Ui.TEXT)
        me.addView(status, Ui.lp(this, top = 4))
        me.addView(Ui.text(this, "Mes adresses IP (connexion directe, port ${Hub.PORT})", 12f, Ui.MUTED), Ui.lp(this, top = 12))
        ipText = Ui.text(this, "Recherche…", 14f, Ui.TEXT)
        me.addView(ipText)
        diag = Ui.text(this, "", 12f, Ui.MUTED)
        me.addView(diag, Ui.lp(this, top = 4))
        val r = Ui.horizontal(this)
        r.addView(Ui.button(this, "📤 Partager", Ui.GREEN) { share() },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.dp(this@InternetActivity, 4) })
        r.addView(Ui.button(this, "📋 Copier", Ui.GREY) { copy() },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.dp(this@InternetActivity, 4) })
        me.addView(r, Ui.lp(this, top = 10))
        root.addView(me, Ui.lp(this, top = 14))

        // Contacter quelqu'un
        root.addView(Ui.text(this, "Contacter quelqu'un", 20f, Ui.GOLD, true), Ui.lp(this, top = 22))
        val c = Ui.card(this)
        c.addView(Ui.text(this, "Collez son code BS, son adresse IP (IPv4 ou IPv6) ou tout le message qu'il vous a envoyé :", 13f, Ui.TEXT))
        input = EditText(this).apply {
            hint = "ex. BS-7K3Q-92XF  ou  41.207.12.8  ou  2c0f:f0f8::1"
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2; maxLines = 6
        }
        c.addView(input)
        nameIn = EditText(this).apply {
            hint = "Nom (facultatif)"
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        c.addView(nameIn)
        c.addView(Ui.button(this, "🔌  Se connecter", Ui.GREEN) { connect() }, Ui.lp(this, top = 8))
        c.addView(Ui.button(this, "💾  Enregistrer seulement", Ui.GREY) { saveOnly() }, Ui.lp(this, top = 8))
        log = Ui.text(this, "", 13f, Ui.GOLD)
        c.addView(log, Ui.lp(this, top = 8))
        root.addView(c, Ui.lp(this, top = 8))

        val help = Ui.card(this)
        help.addView(Ui.text(this, "Comment ça marche", 15f, Ui.GOLD, true))
        help.addView(Ui.text(this,
            "• Le code BS passe par deux serveurs publics en parallèle (si l'un tombe, l'autre prend le relais). Tout est chiffré de bout en bout : les serveurs ne peuvent rien lire ni écouter.\n" +
            "• L'adresse IP directe ne passe par aucun serveur, mais l'autre téléphone doit être joignable : IPv6 ouverte, ou box avec le port ${Hub.PORT} redirigé. La plupart des forfaits mobiles en IPv4 ne le permettent pas.\n" +
            "• En collant le message complet, l'application essaie d'abord l'IP directe puis bascule automatiquement sur le code.\n" +
            "• Une fois la personne rencontrée, elle reste dans vos contacts : vous pourrez la rejoindre plus tard par Wi-Fi, Bluetooth ou Internet, selon ce qui est disponible.",
            12f, Ui.TEXT), Ui.lp(this, top = 4))
        root.addView(help, Ui.lp(this, top = 16))

        fetchPublicIps()
    }

    override fun onResume() {
        super.onResume()
        Hub.observe(observer)
        status.text = Relay.statusText()
        showIps()
    }

    override fun onPause() {
        super.onPause()
        Hub.unobserve(observer)
    }

    // ------------------------------------------------------------------ mes adresses

    private fun fetchPublicIps() {
        Thread({
            publicV4 = get("https://api.ipify.org")
            publicV6 = get("https://api6.ipify.org").takeIf { it.contains(':') } ?: ""
            runOnUiThread { showIps() }
        }, "public-ip").start()
    }

    private fun get(u: String): String = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.inputStream.bufferedReader().use { it.readText().trim() }.take(60)
    } catch (_: Exception) { "" }

    private fun v6List(): List<String> = (listOf(publicV6) + Hub.globalIpv6()).filter { it.isNotEmpty() }.distinct()

    private fun showIps() {
        val local = Hub.localIps()
        val sb = StringBuilder()
        sb.append("IPv4 publique : ").append(publicV4.ifEmpty { "—" }).append('\n')
        val v6 = v6List()
        sb.append("IPv6 : ").append(if (v6.isEmpty()) "—" else v6.joinToString("\n         ")).append('\n')
        sb.append("Réseau local : ").append(if (local.isEmpty()) "—" else local.joinToString(", "))
        ipText.text = sb.toString()
        diag.text = when {
            publicV4.isEmpty() && v6.isEmpty() -> "⚠ Pas d'accès Internet détecté pour l'instant."
            publicV4.isNotEmpty() && publicV4 in local -> "✅ Votre IPv4 publique est directement sur ce téléphone : on peut vous joindre par IP."
            v6.isNotEmpty() -> "ℹ Votre IPv4 est partagée (box ou opérateur) : donnez plutôt votre code BS ou votre IPv6."
            else -> "ℹ Votre IPv4 est partagée (box ou opérateur) : donnez plutôt votre code BS (l'IP directe ne marchera pas sans redirection de port)."
        }
    }

    private fun shareText(): String {
        val sb = StringBuilder("📞 Contacte-moi sur BS CALLMagic\n")
        sb.append("Nom : ${Prefs.name(this)}\n")
        sb.append("Code : ${Contacts.pretty(Hub.myCode())}\n")
        v6List().firstOrNull()?.let { sb.append("IPv6 : [$it]:${Hub.PORT}\n") }
        if (publicV4.isNotEmpty()) sb.append("IPv4 : $publicV4:${Hub.PORT}\n")
        sb.append("Colle ce message dans BS CALLMagic › Internet › Contacter quelqu'un.")
        return sb.toString()
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText())
        try { startActivity(Intent.createChooser(i, "Partager mes coordonnées")) } catch (_: Exception) {}
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BS CALLMagic", shareText()))
        Ui.toast(this, "Coordonnées copiées")
    }

    // ------------------------------------------------------------------ contacter

    private fun saveOnly() {
        val c = Contacts.addManual(nameIn.text.toString(), input.text.toString())
        if (c == null) { Ui.toast(this, "Aucun code BS ni adresse IP reconnu dans le texte."); return }
        Ui.toast(this, "Contact « ${c.name} » enregistré")
        finish()
    }

    private fun connect() {
        val text = input.text.toString()
        val code = Contacts.parseCode(text)
        val ips = Contacts.parseIps(text)
        if (code == null && ips.isEmpty()) { Ui.toast(this, "Aucun code BS ni adresse IP reconnu dans le texte."); return }
        if (code != null && code == Hub.myCode()) { Ui.toast(this, "C'est votre propre code 🙂"); return }
        val c = Contacts.addManual(nameIn.text.toString(), text) ?: return
        log.text = ""
        val attempts = ArrayList<() -> Unit>()
        fun line(s: String) { log.append(s + "\n") }
        lateinit var next: () -> Unit
        val success: (Peer) -> Unit = { p ->
            Contacts.absorb(c, p.deviceId)
            line("✅ Connecté à ${p.name} par ${p.transport}")
            startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_TARGET, p.deviceId))
        }
        for ((ip, port) in ips) attempts.add {
            line("🌐 Essai direct $ip (port $port)…")
            Hub.connectIp(ip, port, 7000) { p ->
                if (p == null) { line("   ✖ injoignable directement"); next() }
                else waitReady(p) { ok -> if (ok != null) { c.ip = ip; c.port = port; Contacts.byId(ok.deviceId)?.let { it.ip = ip; it.port = port }; success(ok) } else { line("   ✖ pas de réponse BS CALLMagic"); next() } }
            }
        }
        if (code != null) attempts.add {
            if (!Relay.online) { line("✖ Pas d'Internet : le code ne peut pas être utilisé."); next() }
            else {
                line("🔒🌐 Essai par le code ${Contacts.pretty(code)}…" + if (!Relay.isOnline(code)) " (semble hors ligne)" else "")
                Relay.connect(code) { p ->
                    if (p == null) { line("   ✖ pas de réponse (application fermée ou pas d'Internet chez lui)"); next() }
                    else waitReady(p) { ok -> if (ok != null) success(ok) else { line("   ✖ pas de réponse"); next() } }
                }
            }
        }
        next = {
            if (isFinishing) Unit
            else if (attempts.isEmpty()) line("Échec : ${c.name} n'est pas joignable pour l'instant. Le contact est enregistré : réessayez plus tard depuis l'annuaire.")
            else attempts.removeAt(0).invoke()
        }
        next()
    }

    private fun waitReady(p: Peer, done: (Peer?) -> Unit) {
        Thread({
            var n = 0
            while (n++ < 100 && p.alive && p.deviceId.isEmpty()) Thread.sleep(100)
            runOnUiThread { done(if (p.alive && p.deviceId.isNotEmpty()) p else null) }
        }, "wait-ready").start()
    }
}
