# BS IR SCAN — Télécommande infrarouge universelle (Android)

Application Android native (Kotlin, sans bibliothèque externe) qui utilise l'émetteur
infrarouge du téléphone pour **balayer, identifier puis contrôler** les appareils à
télécommande IR (TV, vidéoprojecteur, décodeur, ampli, ventilateur, climatiseur…).

## Fonctionnement

1. **Balayer la zone** : le téléphone, pointé vers l'appareil, envoie tour à tour le code
   « Marche/Arrêt » de chaque profil de la base (toutes les 2 s). Pause, précédent, suivant.
2. **Choisir l'appareil** : dès que l'appareil réagit (il s'éteint), on appuie sur
   « L'appareil a réagi ». Le profil (marque/protocole) est retenu ; un bouton « Tester »
   permet de confirmer (Vol +, Muet…).
3. **Définir le type** : téléviseur, décodeur, projecteur, audio, ventilateur, climatiseur, autre,
   puis un nom (ex. « TV salle de réunion »).
4. **Contrôler** : télécommande adaptée au type. Appui long sur une touche pour saisir ou
   corriger son code. Appareils enregistrés sur le téléphone.

## Nouveautés v2.0 — détection renforcée

- **Puissance d'émission** : Normale / Forte / Maximale (salves répétées ; en Maximale, émission sur 3 fréquences 36–38–40 kHz).
- **Balayage Rapide / Profond / Exhaustif** : marques connues, puis adresses NEC/Samsung génériques (décodeurs, TV génériques, ventilateurs), jusqu'à plus de 2 300 codes.
- **Affinage** : renvoi un à un des 12 derniers codes émis pour isoler le bon.
- **Explorateur de touches** : parcourt toutes les commandes du protocole de l'appareil et les assigne aux touches.
- **Détecteur IR par caméra** : repère les éclairs infrarouges d'une télécommande.
- **Émission en arrière-plan** : l'interface ne se fige jamais ; fréquence ajustée au matériel.
- Profil **Panasonic** ajouté.

## Limite physique à connaître

L'émetteur IR d'un téléphone **émet seulement, il ne reçoit pas**. Et les TV, climatiseurs, etc.
ne diffusent aucun signal : ils se contentent d'écouter. Aucun téléphone ne peut donc « voir »
les appareils de la pièce. Le balayage marche comme celui des télécommandes universelles :
on envoie des codes et on observe quel appareil réagit.

## Formats de codes acceptés (appui long sur une touche)

| Format | Exemple |
|---|---|
| NEC (adresse 8 bits) | `NEC:04:08` |
| NEC étendu (16 bits) | `NECX:5583:90` |
| Samsung32 | `SAMSUNG:07:02` |
| Sony SIRC 12/15 bits | `SONY12:01:15` |
| Philips RC5 | `RC5:00:0C` |
| Pronto hexadécimal | `PRONTO:0000 006D 0022 0002 0156 00AB …` |
| Brut (µs) | `RAW:38000:9000,4500,560,560,…` |

Les codes Pronto se trouvent dans les bases publiques (irdb, RemoteCentral…). C'est la voie
à suivre pour les **climatiseurs**, dont les codes varient selon la marque et le modèle.

## Base intégrée (v1.0)

Samsung TV, LG TV, Sony TV, Philips TV (RC5), Toshiba TV, vidéoprojecteur Epson.
Pour ajouter une marque : `CodeDatabase.kt` → nouveau `Profile(...)`.

## Compiler l'APK

**Option A — GitHub (sans rien installer)**
1. Créer un dépôt GitHub et y déposer le contenu de ce dossier.
2. Onglet **Actions** → workflow « Compiler l'APK » (se lance tout seul à chaque envoi).
3. À la fin, télécharger l'artefact `BS_IR_SCAN-apk` → installer l'APK sur le téléphone
   (autoriser « sources inconnues »).

**Option B — Android Studio**
Ouvrir le dossier → laisser Gradle synchroniser → *Build › Build APK(s)*.

## Téléphones compatibles

Téléphones Android avec émetteur IR : Xiaomi / Redmi / POCO (la plupart), Huawei (certains),
Honor, Tecno et Infinix (certains), anciens Samsung Galaxy S4–S6, LG G3–G5…
Les iPhone n'ont pas d'émetteur IR. Sans émetteur, l'application fonctionne en mode simulation.

Fichiers source : `app/src/main/java/ci/bsinaly/irscan/`
- `IrEncoder.kt` — encodage des protocoles (NEC, Samsung, Sony, RC5, Pronto, brut)
- `IrTransmitter.kt` — pilotage de l'émetteur (ConsumerIrManager)
- `CodeDatabase.kt` — types d'appareils, touches, dispositions, base de codes
- `DeviceStore.kt` — enregistrement des appareils
- `MainActivity.kt`, `ScanActivity.kt`, `RemoteActivity.kt` — écrans
