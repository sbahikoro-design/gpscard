# WebMapANADER — application Windows

Deux fichiers sont produits à chaque compilation, publiés dans **Releases** du
dépôt :

| Fichier | Usage |
|---|---|
| `WebMapANADER-1.x.0-portable.exe` | **Double-clic, rien à installer.** À poser sur une clé USB ou dans un dossier partagé. |
| `WebMapANADER-1.x.0-installation.exe` | Installe l'application avec un raccourci sur le Bureau et dans le menu Démarrer. |

Aucun logiciel préalable n'est requis : ni navigateur particulier, ni Node, ni
.NET. Le moteur d'affichage est embarqué dans l'exécutable.

Au premier lancement, Windows peut afficher **« Windows a protégé votre PC »** :
c'est SmartScreen, qui signale un éditeur inconnu. Cliquer sur *Informations
complémentaires* puis *Exécuter quand même*. L'avertissement ne disparaîtra
qu'avec un certificat de signature de code acheté auprès d'une autorité.

## Ce que fait l'application

- Ouvre la plateforme en ligne <https://sbahikoro-design.github.io/gpscard/> :
  les mises à jour du site arrivent sans réinstaller l'application.
- **Bascule automatiquement sur une copie embarquée si le poste n'a pas de
  réseau.** Découpages, chefs-lieux et localités sont dans l'exécutable ; seul
  le fond de plan OpenStreetMap manque, la carte reste donc lisible sur fond
  blanc. Le menu *Fichier* permet de passer de l'une à l'autre à la main.
- Enregistre les exports — classeurs Excel, planches, GeoJSON — directement
  dans le dossier **Téléchargements**, puis ouvre l'explorateur sur le fichier.
- Menu en français : plein écran (F11), zoom, rechargement (F5).
- Les liens vers d'autres sites s'ouvrent dans le navigateur du poste.

## Compiler

GitHub compile à chaque modification de `windows/` ou de `index.html`. À la
main : onglet **Actions** → *EXE WebMapANADER* → **Run workflow**.

Sur un poste de développement :

```bash
cd windows
node embarquer.js     # fabrique la copie hors connexion
npm install
npx electron-builder --win portable nsis
```

`node embarquer.js` reprend l'`index.html` de la racine et remplace les trois
bibliothèques servies par cdnjs (Leaflet, SheetJS, sql.js) par les copies de
`site/vendor`. Sans cette étape, la copie hors connexion s'ouvrirait sur une
page blanche.

---

*ANADER — DMAOPAG / DGRM / Cellule Géomatique.*
