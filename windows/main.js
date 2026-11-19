/*
 * WebMapANADER — version Windows.
 *
 * La fenêtre ouvre la plateforme publiée en ligne. Si le poste n'a pas de
 * réseau, la copie embarquée dans l'application prend le relais : les
 * découpages et les localités sont dans le fichier, seul le fond de plan
 * OpenStreetMap manque.
 *
 * ANADER — DMAOPAG / DGRM / Cellule Géomatique.
 */

'use strict';

const { app, BrowserWindow, Menu, shell, dialog, session } = require('electron');
const path = require('path');
const fs = require('fs');

const ACCUEIL = 'https://sbahikoro-design.github.io/gpscard/';
const LOCALE = path.join(__dirname, 'site', 'index.html');
const ICONE = path.join(__dirname, 'build', 'icon.ico');

let fenetre = null;
let replieEnLocal = false;

/* ------------------------------------------------------------------ */

function creerFenetre() {
  fenetre = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 640,
    title: 'WebMapANADER',
    icon: fs.existsSync(ICONE) ? ICONE : undefined,
    backgroundColor: '#ffffff',
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false
    }
  });

  fenetre.once('ready-to-show', () => {
    fenetre.maximize();
    fenetre.show();
  });

  /* Les liens vers d'autres sites partent dans le navigateur du poste. */
  fenetre.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });

  /* Hors réseau : bascule sur la copie embarquée, une seule fois. */
  fenetre.webContents.on('did-fail-load', (e, code, description, url, principale) => {
    if (!principale) return;
    if (code === -3) return; /* chargement interrompu volontairement */
    if (!replieEnLocal && fs.existsSync(LOCALE)) {
      replieEnLocal = true;
      fenetre.loadFile(LOCALE);
      fenetre.webContents.once('did-finish-load', () => {
        fenetre.setTitle('WebMapANADER — copie locale (hors connexion)');
      });
    } else {
      dialog.showErrorBox(
        'Plateforme injoignable',
        'La plateforme en ligne n\'a pas répondu et aucune copie locale n\'est '
        + 'disponible.\n\nDétail : ' + description
      );
    }
  });

  fenetre.on('closed', () => { fenetre = null; });

  fenetre.loadURL(ACCUEIL);
}

/* ------------------------------------------------------------------ */
/* Les exports de la plateforme vont dans le dossier Téléchargements. */

function reglerTelechargements() {
  session.defaultSession.on('will-download', (evenement, element) => {
    const dossier = app.getPath('downloads');
    let nom = element.getFilename() || 'webmapanader';
    let cible = path.join(dossier, nom);

    /* Ne jamais écraser un fichier déjà présent. */
    if (fs.existsSync(cible)) {
      const ext = path.extname(nom);
      const base = path.basename(nom, ext);
      let i = 2;
      while (fs.existsSync(path.join(dossier, base + ' (' + i + ')' + ext))) i++;
      cible = path.join(dossier, base + ' (' + i + ')' + ext);
    }

    element.setSavePath(cible);

    element.once('done', (ev, etat) => {
      if (etat === 'completed') {
        if (fenetre) {
          fenetre.setTitle('WebMapANADER — enregistré : ' + path.basename(cible));
          setTimeout(() => { if (fenetre) fenetre.setTitle('WebMapANADER'); }, 6000);
        }
        shell.showItemInFolder(cible);
      } else {
        dialog.showErrorBox('Export interrompu',
          'Le fichier n\'a pas pu être enregistré (' + etat + ').');
      }
    });
  });
}

/* ------------------------------------------------------------------ */

function construireMenu() {
  const modele = [
    {
      label: 'Fichier',
      submenu: [
        {
          label: 'Plateforme en ligne',
          accelerator: 'CmdOrCtrl+H',
          click: () => {
            replieEnLocal = false;
            if (fenetre) { fenetre.setTitle('WebMapANADER'); fenetre.loadURL(ACCUEIL); }
          }
        },
        {
          label: 'Copie locale (hors connexion)',
          enabled: fs.existsSync(LOCALE),
          click: () => {
            if (fenetre) {
              replieEnLocal = true;
              fenetre.loadFile(LOCALE);
              fenetre.setTitle('WebMapANADER — copie locale (hors connexion)');
            }
          }
        },
        { type: 'separator' },
        { label: 'Recharger', accelerator: 'F5', role: 'reload' },
        { type: 'separator' },
        { label: 'Quitter', accelerator: 'Alt+F4', role: 'quit' }
      ]
    },
    {
      label: 'Affichage',
      submenu: [
        { label: 'Plein écran', accelerator: 'F11', role: 'togglefullscreen' },
        { type: 'separator' },
        { label: 'Agrandir', accelerator: 'CmdOrCtrl+Plus', role: 'zoomIn' },
        { label: 'Réduire', accelerator: 'CmdOrCtrl+-', role: 'zoomOut' },
        { label: 'Taille normale', accelerator: 'CmdOrCtrl+0', role: 'resetZoom' },
        { type: 'separator' },
        { label: 'Outils de développement', accelerator: 'F12', role: 'toggleDevTools' }
      ]
    },
    {
      label: 'Édition',
      submenu: [
        { label: 'Annuler', role: 'undo' },
        { label: 'Rétablir', role: 'redo' },
        { type: 'separator' },
        { label: 'Couper', role: 'cut' },
        { label: 'Copier', role: 'copy' },
        { label: 'Coller', role: 'paste' },
        { label: 'Tout sélectionner', role: 'selectAll' }
      ]
    },
    {
      label: 'Aide',
      submenu: [
        {
          label: 'Ouvrir le dossier Téléchargements',
          click: () => shell.openPath(app.getPath('downloads'))
        },
        { type: 'separator' },
        {
          label: 'À propos',
          click: () => {
            dialog.showMessageBox(fenetre, {
              type: 'info',
              title: 'À propos',
              message: 'WebMapANADER ' + app.getVersion(),
              detail:
                'Plateforme cartographique de l\'ANADER.\n\n'
                + 'Adresse en ligne :\n' + ACCUEIL + '\n\n'
                + 'Source : ANADER\n'
                + 'Auteur : DMAOPAG / DGRM / Cellule Géomatique',
              buttons: ['Fermer']
            });
          }
        }
      ]
    }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(modele));
}

/* ------------------------------------------------------------------ */

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (fenetre) {
      if (fenetre.isMinimized()) fenetre.restore();
      fenetre.focus();
    }
  });

  app.whenReady().then(() => {
    reglerTelechargements();
    construireMenu();
    creerFenetre();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) creerFenetre();
    });
  });

  app.on('window-all-closed', () => { app.quit(); });
}
