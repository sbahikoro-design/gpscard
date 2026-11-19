/*
 * Prépare la copie hors connexion embarquée dans l'application Windows.
 *
 * Reprend l'index.html publié à la racine du dépôt et remplace les trois
 * bibliothèques servies par cdnjs (Leaflet, SheetJS, sql.js) par les copies
 * du dossier windows/site/vendor : sans cela, la carte reste blanche dès que
 * le poste n'a pas de réseau.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const racine = path.join(__dirname, '..');
const source = path.join(racine, 'index.html');
const dossier = path.join(__dirname, 'site');
const cible = path.join(dossier, 'index.html');
const vendor = path.join(dossier, 'vendor');

const BASES = [
  'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/',
  'https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/',
  'https://cdnjs.cloudflare.com/ajax/libs/sql.js/1.10.3/'
];

const ATTENDUS = [
  'leaflet.min.css', 'leaflet.min.js', 'xlsx.full.min.js',
  'sql-wasm.js', 'sql-wasm.wasm', 'images/marker-icon.png'
];

if (!fs.existsSync(source)) {
  console.error('index.html introuvable à la racine du dépôt.');
  process.exit(1);
}

for (const f of ATTENDUS) {
  if (!fs.existsSync(path.join(vendor, f))) {
    console.error('Bibliothèque manquante : windows/site/vendor/' + f);
    process.exit(1);
  }
}

let page = fs.readFileSync(source, 'utf8');
for (const base of BASES) page = page.split(base).join('vendor/');

if (page.indexOf('cdnjs.cloudflare.com') >= 0) {
  console.error('Une adresse cdnjs n\'a pas été remplacée : la copie hors '
              + 'connexion serait inutilisable.');
  process.exit(1);
}

fs.mkdirSync(dossier, { recursive: true });
fs.writeFileSync(cible, page);

console.log('Copie hors connexion prête : '
          + (fs.statSync(cible).size / 1048576).toFixed(2) + ' Mo');
