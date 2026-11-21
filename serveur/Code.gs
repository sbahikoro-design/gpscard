/**
 * ANADER GPS CARD — service d'authentification, de délégation et de journalisation
 * Google Apps Script · à déployer en application web
 *
 * Déploiement
 *   1. script.google.com → Nouveau projet → coller ce fichier
 *   2. Exécuter la fonction installer() une fois (elle crée le classeur et le compte
 *      administrateur, et affiche son mot de passe provisoire dans le journal d'exécution)
 *   3. Déployer → Nouveau déploiement → Application web
 *        Exécuter en tant que : moi
 *        Accès : tout le monde
 *   4. Coller l'adresse /exec dans la fenêtre Administration de l'application
 *
 * Le corps des requêtes arrive en text/plain : c'est volontaire, cela évite la
 * requête preflight CORS qu'Apps Script ne sait pas traiter.
 *
 * Version 2 — mise à jour d'un déploiement existant
 *   Remplacer tout le contenu du fichier, puis Déployer → Gérer les
 *   déploiements → crayon → Version : nouvelle → Déployer. L'adresse /exec ne
 *   change pas. Le classeur est complété tout seul : la colonne « provisoire »
 *   s'ajoute au premier appel, les comptes et le journal sont conservés.
 *
 *   Ce que la version 2 ajoute
 *     · colonne « provisoire » : le mot de passe délivré par l'administration
 *       reste lisible dans la liste tant que l'intéressé n'a pas choisi le sien ;
 *     · modifierCompte     — corriger un champ sans toucher au reste ;
 *     · definirMotDePasse  — fixer soi-même le mot de passe d'un compte ;
 *     · detailActivite     — la liste des événements derrière chaque indicateur ;
 *     · comptage distinct des cartes éditées et des cartes imprimées.
 *
 * Version 3
 *     · colonnes « fonction » et « matricule » sur chaque compte.
 *   La délégation reste en place : l'administrateur peut autoriser un compte à
 *   créer des accès sous sa propre portée, à un niveau inférieur au sien.
 */

var CLASSEUR = 'ANADER GPS CARD — comptes et activité';
var DUREE_SESSION_H = 12;
var NIVEAUX = { dg: 0, dr: 1, zone: 2, cadr: 3 };
var LIB_NIVEAU = { dg: 'Direction Générale', dr: 'Direction Régionale',
                   zone: 'Zone ANADER', cadr: 'CADR' };

/* ------------------------------------------------------------------ entrées */

function doPost(e) {
  var rep;
  try {
    var d = JSON.parse(e.postData.contents);
    rep = router(d);
  } catch (err) {
    rep = { ok: false, erreur: String(err && err.message || err) };
  }
  return ContentService.createTextOutput(JSON.stringify(rep))
    .setMimeType(ContentService.MimeType.JSON);
}

function doGet() {
  return ContentService.createTextOutput(JSON.stringify(
    { ok: true, service: 'ANADER GPS CARD', version: 3 }))
    .setMimeType(ContentService.MimeType.JSON);
}

function router(d) {
  switch (d.action) {
    case 'connexion':        return connexion(d);
    case 'session':          return infoSession(d);
    case 'premiereConnexion':return premiereConnexion(d);
    case 'changerMotDePasse':return changerMotDePasse(d);
    case 'creerCompte':      return creerCompte(d);
    case 'listerComptes':    return listerComptes(d);
    case 'modifierCompte':   return modifierCompte(d);
    case 'definirMotDePasse':return definirMotDePasse(d);
    case 'reinitialiser':    return reinitialiser(d);
    case 'supprimerCompte':  return supprimerCompte(d);
    case 'deposerCarte':     return deposerCarte(d);
    case 'listerCartes':     return listerCartes(d);
    case 'supprimerCarte':   return supprimerCarte(d);
    case 'statistiques':     return statistiques(d);
    case 'detailActivite':   return detailActivite(d);
    case 'journaliser':      return journaliser(d);
    case 'deconnexion':      return deconnexion(d);
    default: throw new Error('Action inconnue');
  }
}

/* ------------------------------------------------------- accès au classeur */

function classeur() {
  var id = PropertiesService.getScriptProperties().getProperty('classeur');
  if (id) return SpreadsheetApp.openById(id);
  var ss = SpreadsheetApp.create(CLASSEUR);
  PropertiesService.getScriptProperties().setProperty('classeur', ss.getId());
  return ss;
}

function feuille(nom, entetes) {
  var ss = classeur(), f = ss.getSheetByName(nom);
  if (!f) {
    f = ss.insertSheet(nom);
    f.appendRow(entetes);
    f.setFrozenRows(1);
  }
  return f;
}

function fComptes() {
  var f = feuille('Comptes', ['login', 'nom', 'empreinte', 'sel', 'niveau', 'id',
    'libelle', 'role', 'peutCreer', 'mail', 'createur', 'cree', 'premiere',
    'derniere', 'actif', 'ids', 'provisoire', 'fonction', 'matricule']);
  /* colonnes ajoutées après coup : « ids » (un compte de CADR peut couvrir
     plusieurs emprises) et « provisoire » (mot de passe délivré par
     l'administration, effacé dès que l'agent choisit le sien). */
  colonne(f, 'ids');
  colonne(f, 'provisoire');
  colonne(f, 'fonction');
  colonne(f, 'matricule');
  return f;
}

/** Numéro de la colonne portant cet en-tête ; la crée à la fin si elle manque.
 *  Les colonnes ne sont jamais insérées au milieu : les numéros déjà écrits
 *  ailleurs dans ce fichier restent valables. */
function colonne(f, nom) {
  var n = f.getLastColumn();
  var e = f.getRange(1, 1, 1, n).getValues()[0];
  var i = e.indexOf(nom);
  if (i >= 0) return i + 1;
  f.getRange(1, n + 1).setValue(nom);
  return n + 1;
}
function fJournal() {
  return feuille('Journal', ['horodatage', 'login', 'nom', 'portee', 'type',
    'niveau', 'entite', 'format', 'resolution']);
}
function fSessions() {
  return feuille('Sessions', ['jeton', 'login', 'ouverte', 'expire']);
}
function fCartes() {
  return feuille('Cartotheque', ['id', 'date', 'login', 'auteur', 'portee', 'nature',
    'niveau', 'entite', 'titre', 'commentaire', 'format', 'resolution', 'fichier', 'lien']);
}
/** Dossier Drive où sont déposées les cartes ; créé au premier dépôt. */
function dossierCartes() {
  var pr = PropertiesService.getScriptProperties();
  var id = pr.getProperty('dossierCartes');
  if (id) { try { return DriveApp.getFolderById(id); } catch (e) {} }
  var d = DriveApp.createFolder('WebMapANADER — Cartothèque');
  pr.setProperty('dossierCartes', d.getId());
  return d;
}

function lignes(f) {
  var v = f.getDataRange().getValues();
  if (v.length < 2) return [];
  var e = v[0], out = [];
  for (var i = 1; i < v.length; i++) {
    var o = { _ligne: i + 1 };
    for (var j = 0; j < e.length; j++) o[e[j]] = v[i][j];
    out.push(o);
  }
  return out;
}

/* ------------------------------------------------------------- utilitaires */

function empreinte(mdp, sel) {
  var b = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, sel + '|' + mdp,
    Utilities.Charset.UTF_8);
  return Utilities.base64Encode(b);
}
function motDePasseProvisoire() {
  var a = 'ABCDEFGHJKLMNPQRSTUVWXYZ', c = 'abcdefghijkmnpqrstuvwxyz', n = '23456789';
  var t = a.charAt(Math.floor(Math.random() * a.length));
  for (var i = 0; i < 5; i++) t += c.charAt(Math.floor(Math.random() * c.length));
  for (var k = 0; k < 3; k++) t += n.charAt(Math.floor(Math.random() * n.length));
  return t;
}
function nouveauSel() { return Utilities.getUuid().replace(/-/g, '').substring(0, 16); }

function compteDe(login) {
  var l = String(login || '').toLowerCase().trim();
  var t = lignes(fComptes());
  for (var i = 0; i < t.length; i++)
    if (String(t[i].login).toLowerCase() === l) return t[i];
  return null;
}

function portee(c) {
  if (!c) return '';
  if (c.niveau === 'dg') return 'Direction Générale';
  var n = String(c.ids || '').split(',').filter(String).length;
  var suffixe = (n > 1) ? ' (' + n + ' emprises)' : '';
  return LIB_NIVEAU[c.niveau] + ' — ' + (c.libelle || c.id) + suffixe;
}

/* ------------------------------------------------------------- session */

function ouvrirSession(login) {
  var jeton = Utilities.getUuid();
  var exp = new Date(Date.now() + DUREE_SESSION_H * 3600 * 1000);
  fSessions().appendRow([jeton, login, new Date(), exp]);
  return jeton;
}

function compteDuJeton(jeton) {
  if (!jeton) throw new Error('Session absente');
  var t = lignes(fSessions());
  for (var i = t.length - 1; i >= 0; i--) {
    if (t[i].jeton === jeton) {
      if (new Date(t[i].expire) < new Date()) throw new Error('Session expirée');
      var c = compteDe(t[i].login);
      if (!c || c.actif === false) throw new Error('Compte désactivé');
      return c;
    }
  }
  throw new Error('Session inconnue — reconnectez-vous');
}

function vueCompte(c) {
  var ids = String(c.ids || '').split(',').map(function (x) { return Number(x); })
    .filter(function (x) { return !isNaN(x); });
  if (!ids.length && c.id !== '' && c.id !== null) ids = [Number(c.id)];
  return { login: c.login, nom: c.nom, role: c.role, niveau: c.niveau,
    id: Number(c.id), ids: ids, libelle: c.libelle, mail: c.mail,
    peutCreer: c.peutCreer === true || c.peutCreer === 'VRAI' || c.role === 'admin',
    premiere: c.premiere === true || c.premiere === 'VRAI' };
}

function connexion(d) {
  var c = compteDe(d.login);
  if (!c) throw new Error('Identifiant ou mot de passe incorrect');
  if (c.actif === false) throw new Error('Compte désactivé');
  if (empreinte(d.motdepasse, c.sel) !== c.empreinte)
    throw new Error('Identifiant ou mot de passe incorrect');
  var f = fComptes();
  f.getRange(c._ligne, 14).setValue(new Date());
  var jeton = ouvrirSession(c.login);
  enregistrer(c, 'connexion', {});
  return { ok: true, jeton: jeton, compte: vueCompte(c) };
}

function infoSession(d) {
  var c = compteDuJeton(d.jeton);
  return { ok: true, compte: vueCompte(c) };
}

function deconnexion(d) {
  var t = lignes(fSessions()), f = fSessions();
  for (var i = 0; i < t.length; i++)
    if (t[i].jeton === d.jeton) f.getRange(t[i]._ligne, 4).setValue(new Date());
  return { ok: true };
}

function premiereConnexion(d) {
  var c = compteDuJeton(d.jeton);
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(String(d.mail || '')))
    throw new Error('Adresse électronique invalide');
  if (String(d.motdepasse || '').length < 8)
    throw new Error('Le mot de passe doit compter au moins 8 caractères');
  var sel = nouveauSel(), f = fComptes();
  f.getRange(c._ligne, 3).setValue(empreinte(d.motdepasse, sel));
  f.getRange(c._ligne, 4).setValue(sel);
  f.getRange(c._ligne, 10).setValue(d.mail);
  f.getRange(c._ligne, 13).setValue(false);
  /* l'agent a choisi son mot de passe : l'administration ne le connaît plus */
  f.getRange(c._ligne, colonne(f, 'provisoire')).setValue('');
  c.mail = d.mail; c.premiere = false;
  return { ok: true, compte: vueCompte(c) };
}

function changerMotDePasse(d) {
  var c = compteDuJeton(d.jeton);
  if (empreinte(d.ancien, c.sel) !== c.empreinte)
    throw new Error('Mot de passe actuel incorrect');
  if (String(d.nouveau || '').length < 8)
    throw new Error('Le mot de passe doit compter au moins 8 caractères');
  var sel = nouveauSel(), f = fComptes();
  f.getRange(c._ligne, 3).setValue(empreinte(d.nouveau, sel));
  f.getRange(c._ligne, 4).setValue(sel);
  f.getRange(c._ligne, colonne(f, 'provisoire')).setValue('');
  return { ok: true };
}

/* --------------------------------------------- délégation hiérarchique */

/** Un compte ne crée que dans sa propre portée et à un niveau inférieur au sien. */
function verifierDelegation(createur, niveau, id) {
  if (createur.role !== 'admin') {
    if (!(createur.peutCreer === true || createur.peutCreer === 'VRAI'))
      throw new Error('Votre compte n’est pas autorisé à créer des accès');
    if (NIVEAUX[niveau] === undefined || NIVEAUX[niveau] <= NIVEAUX[createur.niveau])
      throw new Error('Vous ne pouvez créer que des comptes d’un niveau inférieur au vôtre');
  }
  if (NIVEAUX[niveau] === undefined) throw new Error('Niveau inconnu');
}

function creerCompte(d) {
  var createur = compteDuJeton(d.jeton);
  verifierDelegation(createur, d.niveau, d.id);
  var login = String(d.login || '').toLowerCase().trim();
  if (!login || !d.nom) throw new Error('Nom et identifiant sont requis');
  if (!/^[a-z0-9._-]{3,40}$/.test(login))
    throw new Error('Identifiant invalide : lettres, chiffres, point, tiret');
  if (compteDe(login)) throw new Error('Cet identifiant existe déjà');
  var ids = (d.ids && d.ids.length) ? d.ids.map(Number) : [Number(d.id)];
  var mdp = motDePasseProvisoire(), sel = nouveauSel();
  var f = fComptes();
  f.appendRow([login, d.nom, empreinte(mdp, sel), sel, d.niveau,
    ids[0], d.libelle || '', 'agent', d.peutCreer === true, '',
    createur.login, new Date(), true, '', true, ids.join(',')]);
  var L = f.getLastRow();
  f.getRange(L, colonne(f, 'provisoire')).setValue(mdp);
  f.getRange(L, colonne(f, 'fonction')).setValue(String(d.fonction || '').trim());
  f.getRange(L, colonne(f, 'matricule')).setValue(String(d.matricule || '').trim());
  return { ok: true, motdepasse: mdp,
    compte: { login: login, nom: d.nom, niveau: d.niveau, id: Number(d.id) } };
}

function listerComptes(d) {
  var c = compteDuJeton(d.jeton);
  var t = lignes(fComptes()), out = [];
  for (var i = 0; i < t.length; i++) {
    var x = t[i];
    if (c.role !== 'admin') {
      if (x.createur !== c.login && x.login !== c.login) continue;
    }
    var idsx = String(x.ids || '').split(',').map(Number)
      .filter(function (v) { return !isNaN(v); });
    if (!idsx.length && x.id !== '' && x.id !== null) idsx = [Number(x.id)];
    out.push({ nom: x.nom, login: x.login, portee: portee(x), mail: x.mail,
      fonction: x.fonction || '', matricule: x.matricule || '',
      soi: x.login === c.login, role: x.role,
      niveau: x.niveau, id: Number(x.id), ids: idsx, libelle: x.libelle,
      peutCreer: x.peutCreer === true || x.peutCreer === 'VRAI' || x.role === 'admin',
      premiere: x.premiere === true || x.premiere === 'VRAI',
      /* mot de passe délivré par l'administration, tant que l'agent n'a pas
         choisi le sien ; ensuite la case est vide, et elle le reste. */
      provisoire: x.provisoire || '',
      derniere: x.derniere ? Utilities.formatDate(new Date(x.derniere),
        Session.getScriptTimeZone(), 'dd/MM/yyyy HH:mm') : '' });
  }
  return { ok: true, comptes: out };
}

function reinitialiser(d) {
  var c = compteDuJeton(d.jeton);
  var cible = compteDe(d.login);
  if (!cible) throw new Error('Compte introuvable');
  if (c.role !== 'admin' && cible.createur !== c.login)
    throw new Error('Ce compte ne relève pas de votre portée');
  var mdp = motDePasseProvisoire(), sel = nouveauSel(), f = fComptes();
  f.getRange(cible._ligne, 3).setValue(empreinte(mdp, sel));
  f.getRange(cible._ligne, 4).setValue(sel);
  f.getRange(cible._ligne, 13).setValue(true);
  f.getRange(cible._ligne, colonne(f, 'provisoire')).setValue(mdp);
  return { ok: true, motdepasse: mdp };
}

/**
 * Modification d'un compte, champ par champ. Seuls les champs présents dans la
 * requête sont touchés : l'écran peut ainsi corriger le nom sans rien changer
 * d'autre. L'identifiant, lui, n'est jamais modifiable — c'est la clé qui relie
 * le compte à son historique d'activité.
 */
function modifierCompte(d) {
  var c = compteDuJeton(d.jeton);
  var cible = compteDe(d.login);
  if (!cible) throw new Error('Compte introuvable');
  if (c.role !== 'admin' && cible.createur !== c.login && cible.login !== c.login)
    throw new Error('Ce compte ne relève pas de votre portée');
  var f = fComptes(), L = cible._ligne, fait = [];

  if (d.fonction !== undefined) {
    f.getRange(L, colonne(f, 'fonction')).setValue(String(d.fonction).trim());
    fait.push('fonction');
  }
  if (d.matricule !== undefined) {
    f.getRange(L, colonne(f, 'matricule')).setValue(String(d.matricule).trim());
    fait.push('matricule');
  }

  if (d.nom !== undefined) {
    var nom = String(d.nom).trim();
    if (!nom) throw new Error('Le nom ne peut pas être vide');
    f.getRange(L, colonne(f, 'nom')).setValue(nom);
    fait.push('nom');
  }
  if (d.mail !== undefined) {
    var m = String(d.mail).trim();
    if (m && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(m))
      throw new Error('Adresse électronique invalide');
    f.getRange(L, colonne(f, 'mail')).setValue(m);
    fait.push('courriel');
  }
  if (d.niveau !== undefined) {
    verifierDelegation(c, d.niveau, d.id);
    var ids = (d.ids && d.ids.length) ? d.ids.map(Number) : [Number(d.id)];
    f.getRange(L, colonne(f, 'niveau')).setValue(d.niveau);
    f.getRange(L, colonne(f, 'id')).setValue(ids[0]);
    f.getRange(L, colonne(f, 'ids')).setValue(ids.join(','));
    f.getRange(L, colonne(f, 'libelle')).setValue(d.libelle || '');
    fait.push('centre de coût');
  }
  if (d.peutCreer !== undefined) {
    f.getRange(L, colonne(f, 'peutCreer')).setValue(d.peutCreer === true);
    fait.push('délégation');
  }
  if (!fait.length) throw new Error('Rien à modifier');
  enregistrer(c, 'modification', { niveau: cible.niveau, entite: cible.login });
  return { ok: true, modifie: fait };
}

/**
 * L'administration fixe elle-même le mot de passe d'un compte, au lieu d'en
 * tirer un au hasard. Le mot de passe choisi reste lisible dans la liste tant
 * que l'intéressé n'en a pas choisi un autre.
 */
function definirMotDePasse(d) {
  var c = compteDuJeton(d.jeton);
  var cible = compteDe(d.login);
  if (!cible) throw new Error('Compte introuvable');
  if (c.role !== 'admin' && cible.createur !== c.login && cible.login !== c.login)
    throw new Error('Ce compte ne relève pas de votre portée');
  var mdp = String(d.motdepasse || '');
  if (mdp.length < 8)
    throw new Error('Le mot de passe doit compter au moins 8 caractères');
  var sel = nouveauSel(), f = fComptes();
  f.getRange(cible._ligne, 3).setValue(empreinte(mdp, sel));
  f.getRange(cible._ligne, 4).setValue(sel);
  /* le compte n'est plus en première connexion : le mot de passe donné sert tel quel */
  f.getRange(cible._ligne, 13).setValue(false);
  f.getRange(cible._ligne, colonne(f, 'provisoire')).setValue(mdp);
  enregistrer(c, 'motdepasse', { niveau: cible.niveau, entite: cible.login });
  return { ok: true, login: cible.login };
}

/**
 * Suppression d'un compte. Le supprimeur doit être administrateur ou créateur du
 * compte visé. Les comptes que le supprimé avait lui-même créés lui sont
 * rattachés, pour qu'aucun accès ne se retrouve orphelin. Les sessions ouvertes
 * sont fermées ; le journal d'activité est conservé.
 */
function supprimerCompte(d) {
  var c = compteDuJeton(d.jeton);
  var cible = compteDe(d.login);
  if (!cible) throw new Error('Compte introuvable');
  if (cible.login === c.login) throw new Error('Vous ne pouvez pas supprimer votre propre compte');
  if (c.role !== 'admin' && cible.createur !== c.login)
    throw new Error('Ce compte ne relève pas de votre portée');
  if (cible.role === 'admin' && c.role !== 'admin')
    throw new Error('Un compte administrateur ne peut être supprimé que par un administrateur');

  var f = fComptes(), t = lignes(f), repris = 0;
  for (var i = 0; i < t.length; i++) {
    if (t[i].createur === cible.login && t[i].login !== cible.login) {
      f.getRange(t[i]._ligne, 11).setValue(c.login);
      repris++;
    }
  }
  var fs = fSessions(), ts = lignes(fs);
  for (var j = ts.length - 1; j >= 0; j--)
    if (ts[j].login === cible.login) fs.deleteRow(ts[j]._ligne);

  f.deleteRow(compteDe(cible.login)._ligne);
  enregistrer(c, 'suppression', { niveau: cible.niveau, entite: cible.login });
  return { ok: true, repris: repris, login: cible.login };
}

/* ---------------------------------------------------------- cartothèque */

/**
 * Dépôt d'une carte imprimée. Le commentaire est obligatoire : une carte sans
 * lecture ne sert à personne. nature vaut 'reference' pour les cartes de
 * référence déposées par l'administration, 'production' pour celles éditées
 * par les centres de coût.
 */
function deposerCarte(d) {
  var c = compteDuJeton(d.jeton);
  var com = String(d.commentaire || '').trim();
  if (com.length < 10)
    throw new Error('Un commentaire d’au moins dix caractères est exigé pour chaque carte');
  if (!d.donnees) throw new Error('Image absente');
  var nature = (d.nature === 'reference' && c.role === 'admin') ? 'reference' : 'production';
  var nom = (d.fichier || 'carte.png').replace(/[\/\\:*?"<>|]/g, '_');
  /* images et PDF seulement : la cartothèque archive des cartes, pas des pièces jointes */
  var types = { 'image/png': 1, 'image/jpeg': 1, 'application/pdf': 1 };
  var mime = d.mime || 'image/png';
  if (!types[mime]) throw new Error('Format non accepté : déposez une image PNG ou JPEG, ou un PDF');
  var b64 = String(d.donnees).replace(/^data:[^,]+,/, '');
  var blob = Utilities.newBlob(Utilities.base64Decode(b64), mime, nom);
  var f = dossierCartes().createFile(blob);
  f.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  var id = Utilities.getUuid().substring(0, 8);
  fCartes().appendRow([id, new Date(), c.login, c.nom, portee(c), nature,
    d.niveau || '', d.entite || '', d.titre || '', com,
    (d.format ? d.format + '|' + mime : mime), d.resolution || '', f.getId(), f.getUrl()]);
  enregistrer(c, 'depot', { niveau: d.niveau, entite: d.entite });
  return { ok: true, id: id, lien: f.getUrl() };
}

function listerCartes(d) {
  var c = compteDuJeton(d.jeton);
  var t = lignes(fCartes()), out = [];
  var q = String(d.recherche || '').toLowerCase();
  for (var i = t.length - 1; i >= 0 && out.length < 300; i--) {
    var x = t[i];
    if (d.nature && d.nature !== 'tout' && x.nature !== d.nature) continue;
    if (q) {
      var texte = [x.titre, x.entite, x.auteur, x.commentaire, x.portee].join(' ').toLowerCase();
      if (texte.indexOf(q) < 0) continue;
    }
    out.push({ id: x.id, auteur: x.auteur, login: x.login, portee: x.portee,
      nature: x.nature, niveau: x.niveau, entite: x.entite, titre: x.titre,
      commentaire: x.commentaire, format: x.format, resolution: x.resolution,
      lien: x.lien, fichier: x.fichier, sien: x.login === c.login,
      date: x.date ? Utilities.formatDate(new Date(x.date),
        Session.getScriptTimeZone(), 'dd/MM/yyyy HH:mm') : '' });
  }
  return { ok: true, cartes: out, total: t.length };
}

function supprimerCarte(d) {
  var c = compteDuJeton(d.jeton);
  var t = lignes(fCartes());
  for (var i = 0; i < t.length; i++) {
    if (t[i].id !== d.id) continue;
    if (c.role !== 'admin' && t[i].login !== c.login)
      throw new Error('Cette carte ne vous appartient pas');
    try { DriveApp.getFileById(t[i].fichier).setTrashed(true); } catch (e) {}
    fCartes().deleteRow(t[i]._ligne);
    return { ok: true };
  }
  throw new Error('Carte introuvable');
}

/* ------------------------------------------------------------ journal */

function enregistrer(c, type, d) {
  fJournal().appendRow([new Date(), c.login, c.nom, portee(c), type,
    d.niveau || '', d.entite || '', d.format || '', d.resolution || '']);
}

function journaliser(d) {
  var c = compteDuJeton(d.jeton);
  enregistrer(c, d.type || 'consultation', d);
  return { ok: true };
}

function statistiques(d) {
  var c = compteDuJeton(d.jeton);
  var jours = Number(d.jours) || 30;
  var depuis = new Date(Date.now() - jours * 86400000);
  var t = lignes(fJournal());
  var s = { connexions: 0, consultations: 0, exports: 0, impressions: 0,
            actifs: 0, emprises: [], comptes: [] };
  var parCompte = {}, parEmprise = {};
  for (var i = 0; i < t.length; i++) {
    var l = t[i];
    if (!l.horodatage || new Date(l.horodatage) < depuis) continue;
    if (c.role !== 'admin' && l.login !== c.login) {
      var sous = compteDe(l.login);
      if (!sous || sous.createur !== c.login) continue;
    }
    if (l.type === 'connexion') s.connexions++;
    else if (l.type === 'consultation') s.consultations++;
    else if (l.type === 'impression') s.impressions++;
    else if (l.type === 'export' || l.type === 'exportLot') s.exports++;
    var k = l.login;
    if (!parCompte[k]) parCompte[k] =
      { nom: l.nom, vues: 0, exports: 0, impressions: 0, derniere: '' };
    if (l.type === 'consultation') parCompte[k].vues++;
    if (l.type === 'impression') parCompte[k].impressions++;
    if (l.type === 'export' || l.type === 'exportLot') {
      parCompte[k].exports++;
      var e = (l.niveau || '') + '|' + (l.entite || '');
      parEmprise[e] = (parEmprise[e] || 0) + 1;
    }
    parCompte[k].derniere = Utilities.formatDate(new Date(l.horodatage),
      Session.getScriptTimeZone(), 'dd/MM/yyyy HH:mm');
  }
  for (var k2 in parCompte) { s.comptes.push(parCompte[k2]); s.actifs++; }
  s.comptes.sort(function (a, b) { return (b.vues + b.exports) - (a.vues + a.exports); });
  s.comptes = s.comptes.slice(0, 40);
  for (var e2 in parEmprise) {
    var p = e2.split('|');
    s.emprises.push({ niveau: LIB_NIVEAU[p[0]] || p[0], nom: p[1], n: parEmprise[e2] });
  }
  s.emprises.sort(function (a, b) { return b.n - a.n; });
  s.emprises = s.emprises.slice(0, 15);
  return { ok: true, stats: s };
}

/**
 * Détail derrière un indicateur : la liste des événements de la période.
 * « quoi » vaut connexion, consultation, edition, impression ou comptes.
 */
function detailActivite(d) {
  var c = compteDuJeton(d.jeton);
  var jours = Number(d.jours) || 30;
  var depuis = new Date(Date.now() - jours * 86400000);
  var quoi = String(d.quoi || 'connexion');
  var familles = { connexion: ['connexion'], consultation: ['consultation'],
                   edition: ['export', 'exportLot'], impression: ['impression'] };
  var vise = familles[quoi] || [quoi];
  var t = lignes(fJournal()), out = [], parCompte = {}, zone = Session.getScriptTimeZone();

  for (var i = t.length - 1; i >= 0; i--) {
    var l = t[i];
    if (!l.horodatage || new Date(l.horodatage) < depuis) continue;
    if (c.role !== 'admin' && l.login !== c.login) {
      var sous = compteDe(l.login);
      if (!sous || sous.createur !== c.login) continue;
    }
    var quand = Utilities.formatDate(new Date(l.horodatage), zone, 'dd/MM/yyyy HH:mm');
    if (quoi === 'comptes') {
      var k = l.login;
      if (!parCompte[k]) parCompte[k] =
        { nom: l.nom, login: k, portee: l.portee, n: 0, derniere: quand };
      parCompte[k].n++;
      continue;
    }
    if (vise.indexOf(l.type) < 0) continue;
    if (out.length >= 300) continue;
    out.push({ date: quand, nom: l.nom, login: l.login, portee: l.portee,
      niveau: LIB_NIVEAU[l.niveau] || l.niveau || '', entite: l.entite || '',
      format: l.format || '', resolution: l.resolution || '' });
  }

  if (quoi === 'comptes') {
    for (var k2 in parCompte) out.push(parCompte[k2]);
    out.sort(function (a, b) { return b.n - a.n; });
  }
  return { ok: true, quoi: quoi, lignes: out };
}

/* ----------------------------------------------------------- installation */

function installer() {
  fComptes(); fJournal(); fSessions(); fCartes();
  if (compteDe('admin')) {
    Logger.log('Le compte admin existe déjà. Utilisez reinitialiserAdmin() si besoin.');
    return;
  }
  var mdp = motDePasseProvisoire(), sel = nouveauSel(), f = fComptes();
  f.appendRow(['admin', 'Administrateur principal', empreinte(mdp, sel), sel,
    'dg', -1, 'Direction Générale', 'admin', true, '', 'installation',
    new Date(), true, '', true, '-1']);
  f.getRange(f.getLastRow(), colonne(f, 'provisoire')).setValue(mdp);
  Logger.log('Classeur : ' + classeur().getUrl());
  Logger.log('Identifiant : admin');
  Logger.log('Mot de passe provisoire : ' + mdp);
}

function reinitialiserAdmin() {
  var c = compteDe('admin');
  if (!c) { installer(); return; }
  var mdp = motDePasseProvisoire(), sel = nouveauSel(), f = fComptes();
  f.getRange(c._ligne, 3).setValue(empreinte(mdp, sel));
  f.getRange(c._ligne, 4).setValue(sel);
  f.getRange(c._ligne, 13).setValue(true);
  f.getRange(c._ligne, colonne(f, 'provisoire')).setValue(mdp);
  Logger.log('Nouveau mot de passe provisoire pour admin : ' + mdp);
}

/** Purge des sessions expirées — à mettre sur un déclencheur quotidien. */
function purgerSessions() {
  var f = fSessions(), t = lignes(f), maintenant = new Date();
  for (var i = t.length - 1; i >= 0; i--)
    if (new Date(t[i].expire) < maintenant) f.deleteRow(t[i]._ligne);
}
