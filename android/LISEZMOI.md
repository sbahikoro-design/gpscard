# WebMapANADER — application Android (version connectée)

L'application ouvre la plateforme publiée à l'adresse
<https://sbahikoro-design.github.io/gpscard/> dans une fenêtre plein écran, avec
l'icône ANADER sur l'écran d'accueil du téléphone. La carte et les données
restent celles du site : **une mise à jour du site met à jour l'application**,
sans réinstaller l'APK.

## Récupérer l'APK

À chaque modification du dossier `android/`, GitHub compile l'application et
publie le fichier dans **Releases** du dépôt. Sur le téléphone :

1. ouvrir la page *Releases* du dépôt ;
2. télécharger `WebMapANADER-1.x.apk` ;
3. ouvrir le fichier téléchargé et autoriser l'installation depuis cette source
   si Android le demande.

On peut aussi lancer la compilation à la main : onglet **Actions** →
*APK WebMapANADER* → **Run workflow**.

## Ce que l'application ajoute au simple navigateur

- **Géolocalisation** : autorisation demandée une fois, puis la position du
  téléphone est disponible dans la carte.
- **Exports** : les fichiers produits par la plateforme (classeurs Excel,
  planches, GeoJSON) sont écrits dans le dossier **Téléchargements**, ce qu'un
  navigateur embarqué ne sait pas faire seul.
- **Imports** : l'écran d'administration peut ouvrir le sélecteur de fichiers du
  téléphone.
- **Hors connexion** : si le réseau manque au lancement, la dernière version
  consultée est rouverte depuis la mémoire du téléphone ; un écran
  « Réessayer » s'affiche si rien n'est disponible.
- **Touche Retour** : revient à l'écran précédent de la carte.
- Les liens vers d'autres sites s'ouvrent dans le navigateur, pas dans
  l'application.

## Signature

Sans réglage particulier, l'APK est signé avec la clé de débogage : il
s'installe normalement, mais n'importe qui peut produire une mise à jour
acceptée par le téléphone.

Pour une clé propre à l'Agence, créer le magasin de clés puis l'enregistrer
dans les secrets du dépôt (*Settings → Secrets and variables → Actions*) :

```bash
keytool -genkeypair -v -keystore anader.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias anader \
        -dname "CN=ANADER, OU=DGRM, O=ANADER, L=Abidjan, C=CI"
base64 -w0 anader.jks > anader.jks.b64
```

| Secret | Contenu |
|---|---|
| `KEYSTORE_B64` | le contenu de `anader.jks.b64` |
| `KEYSTORE_PASSWORD` | le mot de passe du magasin |
| `KEY_ALIAS` | `anader` |
| `KEY_PASSWORD` | le mot de passe de la clé |

Le fichier `anader.jks` se conserve hors du dépôt : il est perdu, et plus aucune
mise à jour ne pourra remplacer les installations existantes.

## Compiler soi-même

```bash
cd android
./gradlew assembleRelease
```

Android Studio Ladybug ou plus récent, JDK 17, SDK Android 34.

---

*ANADER — DMAOPAG / DGRM / Cellule Géomatique.*
