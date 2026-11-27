# BS ALERTE ROUTE

Application Android d'alerte d'obstacles sur le trajet (dos d'âne, nids-de-poule, virages, ponts, villages, contrôles…).

1. **Premier trajet** : DÉMARRER, choisir le type, toucher **OBSTACLE ICI** (ou bouton volume –, ou action de la notification) en passant sur chaque obstacle.
2. **Trajets suivants** : DÉMARRER. À l'approche : 3 bips + vibration + annonce vocale, puis bips rapides à moins de 35 m.

Intelligence : rayon d'alerte qui grandit avec la vitesse, pas d'alerte pour un obstacle déjà dépassé (sens de marche), fusion des doublons (position affinée), ré-armement automatique, export/import entre chauffeurs.

Compilation : GitHub Actions (`.github/workflows/apk.yml`) → APK publié dans les Releases.
BSINALY · 0779687995
