package ci.bsinaly.callmagic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Activité de base : demandes d'autorisations avec suite. */
open class BaseActivity : Activity() {
    private var afterPerms: (() -> Unit)? = null

    fun needPerms(perms: List<String>, then: () -> Unit) {
        if (Build.VERSION.SDK_INT < 23) { then(); return }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { then(); return }
        afterPerms = then
        requestPermissions(missing.toTypedArray(), 77)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 77) return
        HubService.start(this, HubService.ACTION_REFRESH)
        val f = afterPerms
        afterPerms = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) f?.invoke()
        else Ui.toast(this, "Autorisation refusée.")
    }
}

/** Annuaire : liste des personnes, moyens de les joindre, actions (message, appel, fichier…). */
object People {

    fun stateIcon(c: Contact): String = when (Contacts.bestState(c)) {
        Access.CONNECTED -> "🟢"
        Access.AVAILABLE -> "🟡"
        Access.MAYBE -> "⚪"
        else -> "⚫"
    }

    fun accessText(c: Contact): String =
        Contacts.access(c).joinToString("\n") { it.label }.ifEmpty { "⚫ Hors de portée — aucun accès disponible pour l'instant" }

    /** Ligne d'annuaire. */
    fun row(a: Activity, c: Contact, onClick: () -> Unit): View {
        val card = Ui.card(a)
        val top = Ui.horizontal(a).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(a, "${stateIcon(c)}  ${c.name}", 17f, Ui.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val unread = if (c.id.isNotEmpty()) Hub.unreadBy[c.id] ?: 0 else 0
        if (unread > 0) top.addView(Ui.text(a, " $unread ", 12f, Ui.TEXT, true).apply {
            background = Ui.rounded(Ui.RED, Ui.dp(a, 10).toFloat())
        })
        card.addView(top)
        card.addView(Ui.text(a, accessText(c), 12f, Ui.MUTED), Ui.lp(a, top = 4))
        if (!c.saved) card.addView(Ui.text(a, "Nouveau — pas encore dans vos contacts", 11f, Ui.GOLD), Ui.lp(a, top = 2))
        card.setOnClickListener { onClick() }
        return card
    }

    /** Fiche d'une personne avec toutes les actions possibles. */
    fun showActions(a: BaseActivity, c: Contact, refresh: () -> Unit = {}) {
        val box = Ui.vertical(a).apply { setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8)) }
        box.addView(Ui.text(a, accessText(c), 13f, Ui.TEXT))
        if (c.code.isNotEmpty()) box.addView(Ui.text(a, "Code : ${Contacts.pretty(c.code)}", 12f, Ui.GOLD), Ui.lp(a, top = 6))
        val scroll = ScrollView(a).apply { addView(box) }
        lateinit var dlg: AlertDialog
        fun act(label: String, color: Int, f: () -> Unit) {
            box.addView(Ui.button(a, label, color) { dlg.dismiss(); f() }, Ui.lp(a, top = 8))
        }
        act("💬  Écrire (conversation privée)", Ui.GREEN) { openChat(a, c) }
        act("📞  Appel vocal", Ui.GREEN) { call(a, c, false) }
        act("🎥  Appel vidéo", Ui.BLUE) { call(a, c, true) }
        act("📎  Envoyer un fichier", Ui.GREY) { openChat(a, c, pickFile = true) }
        act("✏️  Renommer", Ui.GREY) { rename(a, c, refresh) }
        if (!c.saved) act("➕  Enregistrer dans mes contacts", Ui.GREY) {
            Contacts.addFromDirectory(c); refresh()
        } else act("🗑  Supprimer ce contact", Ui.RED) {
            AlertDialog.Builder(a).setTitle("Supprimer ${c.name} ?")
                .setPositiveButton("Supprimer") { _, _ -> Contacts.remove(c); refresh() }
                .setNegativeButton("Annuler", null).show()
        }
        dlg = AlertDialog.Builder(a).setTitle("${stateIcon(c)} ${c.name}").setView(scroll)
            .setNegativeButton("Fermer", null).create()
        dlg.show()
    }

    private fun rename(a: Activity, c: Contact, refresh: () -> Unit) {
        val e = EditText(a).apply { setText(c.name); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        AlertDialog.Builder(a).setTitle("Nom du contact").setView(e)
            .setPositiveButton("OK") { _, _ ->
                if (!c.saved) Contacts.addFromDirectory(c)
                Contacts.rename(c, e.text.toString()); refresh()
            }
            .setNegativeButton("Annuler", null).show()
    }

    /** Choix de l'accès quand plusieurs sont possibles (« Automatique » choisit le meilleur). */
    fun chooseVia(a: Activity, c: Contact, then: (String?) -> Unit) {
        val acc = Contacts.access(c).distinctBy { it.via }
        if (acc.size <= 1) { then(null); return }
        val labels = listOf("⚡ Automatique — le meilleur accès") + acc.map { it.label }
        AlertDialog.Builder(a).setTitle("Joindre ${c.name} par…")
            .setItems(labels.toTypedArray()) { _, i -> then(if (i == 0) null else acc[i - 1].via) }
            .setNegativeButton("Annuler", null).show()
    }

    /** Établit la liaison (ou réutilise l'existante) puis exécute [then]. */
    fun connect(a: Activity, c: Contact, via: String?, onFail: (() -> Unit)? = null, then: (Peer) -> Unit) {
        val existing = if (c.id.isNotEmpty()) Hub.peerFor(c.id, via) else null
        if (existing != null) { then(existing); return }
        var cancelled = false
        val tv = TextView(a).apply { textSize = 15f; setPadding(Ui.dp(a, 22), Ui.dp(a, 12), Ui.dp(a, 22), Ui.dp(a, 4)) }
        tv.text = "Recherche du meilleur accès…"
        val dlg = AlertDialog.Builder(a).setTitle("Connexion à ${c.name}").setView(tv).setCancelable(false)
            .setNegativeButton("Annuler") { _, _ -> cancelled = true }.create()
        dlg.show()
        Hub.reach(c, via, { step -> tv.text = step }) { p ->
            try { dlg.dismiss() } catch (_: Exception) {}
            if (cancelled || a.isFinishing) return@reach
            if (p != null) then(p)
            else if (onFail != null) onFail()
            else failMessage(a, c, via)
        }
    }

    fun failMessage(a: Activity, c: Contact, via: String?) {
        val tips = StringBuilder("${c.name} n'est pas joignable" + (if (via != null) " par $via" else "") + " pour le moment.\n\n")
        tips.append("• Vérifiez que BS CALLMagic est ouvert (ou actif en arrière-plan) sur son téléphone.\n")
        if (c.code.isNotEmpty() && !Relay.online) tips.append("• Votre téléphone n'a pas Internet : le code ne peut pas être utilisé.\n")
        if (c.code.isNotEmpty() && Relay.online) tips.append("• Par Internet, son téléphone doit lui aussi avoir Internet.\n")
        if (c.ip.isNotEmpty()) tips.append("• L'adresse IP directe ne marche que si son réseau l'autorise (IPv6, box avec redirection du port ${c.port}).\n")
        tips.append("• Sur le même Wi-Fi ou à portée Bluetooth, la connexion est automatique.")
        AlertDialog.Builder(a).setTitle("Injoignable").setMessage(tips.toString()).setPositiveButton("OK", null).show()
    }

    /** Conversation privée (connexion d'abord si on ne connaît pas encore l'identifiant). */
    fun openChat(a: Activity, c: Contact, pickFile: Boolean = false) {
        val go = { id: String ->
            a.startActivity(Intent(a, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_TARGET, id)
                .putExtra(ChatActivity.EXTRA_PICK_FILE, pickFile))
        }
        if (c.id.isNotEmpty()) go(c.id)
        else connect(a, c, null) { p -> go(p.deviceId) }
    }

    /** Appel vocal ou vidéo : choix de l'accès, autorisations, connexion, sonnerie. */
    fun call(a: BaseActivity, c: Contact, video: Boolean) {
        if (Hub.call != Hub.Call.IDLE) { a.startActivity(Intent(a, CallActivity::class.java)); return }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) perms.add(Manifest.permission.CAMERA)
        a.needPerms(perms) {
            chooseVia(a, c) { via ->
                connect(a, c, via) { p ->
                    if (Hub.startCall(p, video)) a.startActivity(Intent(a, CallActivity::class.java))
                    else Ui.toast(a, "Un appel est déjà en cours.")
                }
            }
        }
    }

    /** Choisir une personne dans l'annuaire (disponibles en premier, avec leur accès). */
    fun pick(a: Activity, title: String, then: (Contact) -> Unit) {
        val dir = Contacts.directory()
        if (dir.isEmpty()) { Ui.toast(a, "Aucune personne dans l'annuaire. Connectez-vous en Wi-Fi, Bluetooth ou Internet."); return }
        val labels = dir.map { c ->
            val best = Contacts.access(c).firstOrNull()?.label ?: "⚫ hors de portée"
            "${stateIcon(c)} ${c.name}\n      $best"
        }
        AlertDialog.Builder(a).setTitle(title)
            .setItems(labels.toTypedArray()) { _, i -> then(dir[i]) }
            .setNegativeButton("Annuler", null).show()
    }

    /** Contact correspondant à une conversation privée. */
    fun contactFor(id: String): Contact? =
        Contacts.byId(id) ?: Hub.peerFor(id)?.let { Contact(it.deviceId, it.name, Contacts.codeOf(it.deviceId), saved = false) }
}
