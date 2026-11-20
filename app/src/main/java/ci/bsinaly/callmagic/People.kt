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

/** Activité de base : demandes d'autorisations avec suite, choix de photo d'un contact. */
open class BaseActivity : Activity() {
    /** Écrans qui doivent s'afficher même verrouillé (alertes). */
    open val lockExempt = false

    override fun onResume() {
        super.onResume()
        if (!lockExempt) Lock.onResumeCheck(this)
    }

    override fun onPause() {
        super.onPause()
        Lock.onPause()
    }

    private var afterPerms: (() -> Unit)? = null
    private var photoFor: Contact? = null
    private var photoDone: (() -> Unit)? = null

    fun pickPhoto(c: Contact, done: () -> Unit) {
        photoFor = c; photoDone = done
        try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 88) }
        catch (_: Exception) { Ui.toast(this, "Aucune galerie disponible.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 88 || resultCode != RESULT_OK) return
        val c = photoFor ?: return
        val uri = data?.data ?: return
        try {
            val src = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return
            val side = minOf(src.width, src.height)
            val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val small = android.graphics.Bitmap.createScaledBitmap(sq, 360, 360, true)
            val dir = java.io.File(filesDir, "faces").apply { mkdirs() }
            val f = java.io.File(dir, "${c.key.hashCode()}.jpg")
            f.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            if (!c.saved) Contacts.addFromDirectory(c)
            c.photo = f.absolutePath
            Contacts.save()
            photoDone?.invoke()
        } catch (_: Exception) { Ui.toast(this, "Photo illisible.") }
    }

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
        top.addView(Ui.text(a, "${stateIcon(c)}  ${c.name}" + if (c.verified) "  ✅" else "", 17f, Ui.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
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
        val extra = listOfNotNull(
            if (c.caps and Proto.CAP_SMS != 0) "🌉 Pont SMS" else null,
            if (c.caps and Proto.CAP_NET != 0) "🌐 Partage son Internet" else null,
            if (c.pub.isNotEmpty()) "📮 Peut recevoir des lettres du facteur" else null)
        if (extra.isNotEmpty()) box.addView(Ui.text(a, extra.joinToString(" · "), 12f, Ui.MUTED), Ui.lp(a, top = 4))
        val pk = c.pubKey()
        if (pk != null) {
            val code = Identity.safetyCode(Identity.publicKey, pk)
            val sec = Ui.card(a)
            sec.addView(Ui.text(a, if (c.verified) "✅ Identité vérifiée en personne" else "🔐 Vérifier que c'est bien ${c.name}", 14f, Ui.GOLD, true))
            sec.addView(Ui.text(a, code, 26f, Ui.TEXT, true), Ui.lp(a, top = 4))
            sec.addView(Ui.text(a, "Ouvrez cette fiche sur les deux téléphones : le code doit être exactement le même. " +
                "S'il diffère, ce n'est pas la bonne personne.", 12f, Ui.MUTED), Ui.lp(a, top = 2))
            if (!c.verified) sec.addView(Ui.button(a, "✅ Les codes sont identiques", Ui.GREEN) {
                if (!c.saved) Contacts.addFromDirectory(c)
                c.verified = true; Contacts.save(); refresh(); Ui.toast(a, "${c.name} est vérifié.")
            }, Ui.lp(a, top = 8))
            box.addView(sec, Ui.lp(a, top = 10))
        }
        val scroll = ScrollView(a).apply { addView(box) }
        lateinit var dlg: AlertDialog
        fun act(label: String, color: Int, f: () -> Unit) {
            box.addView(Ui.button(a, label, color) { dlg.dismiss(); f() }, Ui.lp(a, top = 8))
        }
        act("💬  Écrire (conversation privée)", Ui.GREEN) { openChat(a, c) }
        act("📞  Appel vocal", Ui.GREEN) { call(a, c, false) }
        act("🎥  Appel vidéo", Ui.BLUE) { call(a, c, true) }
        act("📎  Envoyer un fichier", Ui.GREY) { openChat(a, c, pickFile = true) }
        act("🖼  Choisir sa photo (mode sans lecture)", Ui.GREY) { a.pickPhoto(c, refresh) }
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

    /** Envoie un SMS par un pont (le dernier utilisé, sinon au choix). */
    fun sendSmsVia(a: Activity, number: String, text: String, then: () -> Unit) {
        val bridges = SmsBridge.bridges()
        if (bridges.isEmpty()) {
            AlertDialog.Builder(a).setTitle("Aucun pont SMS connu")
                .setMessage("Il faut qu'un téléphone de votre entourage active « Ce téléphone est un pont » (menu Pont SMS), " +
                    "et que vous l'ayez rencontré au moins une fois.").setPositiveButton("OK", null).show()
            return
        }
        val go = { b: Contact ->
            if (SmsBridge.request(a, b, number, text)) then() else Ui.toast(a, "Impossible de joindre ce pont.")
        }
        val last = bridges.firstOrNull { it.id == Prefs.lastBridge(a) }
        if (last != null || bridges.size == 1) { go(last ?: bridges[0]); return }
        val labels = bridges.map { b -> "${stateIcon(b)} ${b.name}\n      ${Contacts.access(b).firstOrNull()?.label ?: "📮 par le facteur"}" }
        AlertDialog.Builder(a).setTitle("Par quel pont ?").setItems(labels.toTypedArray()) { _, i -> go(bridges[i]) }
            .setNegativeButton("Annuler", null).show()
    }

    /** Contact correspondant à une conversation privée. */
    fun contactFor(id: String): Contact? =
        Contacts.byId(id) ?: Hub.peerFor(id)?.let { Contact(it.deviceId, it.name, Contacts.codeOf(it.deviceId), saved = false) }
}
