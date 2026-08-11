# ADR-0025 — Une note manquante est une QUESTION, pas un nombre

- **Statut :** Proposé (2026-08-11)
- **Décideuse :** Nada (architecte)
- **Origine :** #297, requalifiée. Question posée par Feten, puis arbitrée par Nada.
- **Mise en œuvre :** #297 (la garde) → #316 (l'acte évaluateur) → #317 (les actes du responsable).
- **Liés :** ADR-0013 (le réajustement audité est le seul canal de correction) ·
  ADR-0014 (l'horloge est un PLANCHER, jamais un plafond) ·
  ADR-0015 (l'instantané rend les écritures du jour J indépendantes du réseau) ·
  ADR-0017 cas C (suppléance d'un évaluateur) ·
  ADR-0018 D5 (l'autorité pédagogique est celle du responsable) ·
  ADR-0021 D3 (descriptif, jamais punitif, **jamais automatique**) et D4 (l'écran de
  délibération précède toute statistique) ·
  ADR-0022 (la frontière du système est la clôture + le procès-verbal) · #276 (la même
  famille, vue du côté de la définition) · #307/#308 (rien n'est perdu côté téléphone)
- ⚠️ **Numérotation :** 0024 est réservé au brouillon local « Le conducteur d'une épreuve »,
  non encore versionné. Cet ADR prend donc 0025.

---

## Le problème

Un étudiant passe 4 stations. Trois notes existent, la quatrième n'a jamais été saisie —
tablette hors réseau, coupure de courant, évaluateur parti, ou simple oubli.

Le système doit alors produire un total. **Il n'a aucune façon neutre de le faire**, et
pourtant il en produit un :

| lecture | ce que ça signifie | qui l'a décidé |
|---|---|---|
| `45/80` | la station manquante vaut zéro | personne — l'étudiant paie une panne |
| `45/60` | la station est retirée du barème | personne — **c'est un re-barème** |
| aucun verdict | la mesure est incomplète | ✅ le seul défaut correct |

Les deux premières lignes ont réellement existé dans le produit. La première était le constat
d'origine de #297. La seconde est ce qui tourne aujourd'hui
(`resultats.component.ts` : `if (s.score != null) totalMax += …`), et elle a été prise pour un
correctif — y compris par moi, dans la relecture du 2026-08-11. **C'en est un piège plus subtil
et non un correctif** : rééchelonner un barème est un acte pédagogique, réservé au responsable
(ADR-0018 D5), et le faire en silence contredit ADR-0021 D3 (« jamais automatique »).

### Ce que le code fait aujourd'hui (vérifié le 2026-08-11)

- L'**absence par station** existe déjà, c'est l'acte de l'évaluateur, elle vaut 0, elle est
  attribuée et elle ne touche pas les autres stations
  (`EvaluateurDashboardService.validerEtudiant`, branche `isAbsent()`). **C'est un verdict
  légitime**, et ce n'est pas le problème.
- Le **verrouillage ne vérifie AUCUNE complétude** : `notation.setVerouillee(true)` sans
  contrôle des critères.
- La **validation d'un groupe saute en silence** un étudiant sans note
  (`findByAssignmentId(...).ifPresent(...)`) puis marque la rotation `TERMINE` — ce qui peut
  clôturer le lot automatiquement.
- La complétude est pourtant **calculable LOCALEMENT** : `exam_item_snapshot` est l'autorité sur
  l'ensemble des items notables (« the snapshot IS the notable-item set », V8), déjà utilisée
  comme garde de feuille. **Donc la garde fonctionne aussi pendant une panne** — ce qui compte,
  puisque le cas de défaillance EST une panne.
- La **reprise fonctionne déjà** : `EtudiantLotResponse.notationItems` rejoue les valeurs
  saisies, et aucune horloge ne ferme une rotation (ADR-0014).
- Le **réajustement ne peut pas réparer une note ABSENTE** : il exige une notation existante
  (`findById(notationId).orElseThrow`). Cette contrainte est correcte et doit rester.
- **Il n'existe aucun acte** par lequel un évaluateur dit « je ne peux pas terminer ».

C'est ce dernier vide qui explique tout le reste : **faute d'acte humain disponible, le code a
fini par en inventer un.**

---

## Décision

### D1 — Trois états, et deux seulement sont des verdicts

Pour chaque couple **(étudiant, station)** :

| état | signification | verdict ? |
|---|---|---|
| **NOTÉ** | tous les critères notables saisis, puis verrouillé | ✅ |
| **ABSENT** | déclaré par l'évaluateur — 0, attribué | ✅ |
| **SANS VERDICT** | partiellement noté, ou jamais ouvert | ❌ |

Seuls les deux premiers alimentent un total. **Le troisième ne doit JAMAIS être interprété par
du code** — ni en zéro, ni en station retirée du barème.

### D2 — Le verrouillage REFUSE tant que la mesure est incomplète

- Verrouiller un étudiant est refusé si des critères notables manquent **et** que l'étudiant
  n'est pas déclaré absent. Le refus **nomme ce qui manque** (« il reste 3 critères non notés
  pour Sana B. »).
- Valider un groupe est refusé tant qu'un étudiant **présent** est SANS VERDICT, en le nommant.
- **L'absence ne bloque jamais** : c'est déjà un verdict.
- Contrôle **local** (`exam_item_snapshot`), donc valable hors réseau.

**Refus dur, et non une confirmation à cocher.** Une boîte « continuer quand même ? »
transformerait « j'ai oublié » en « j'ai cliqué », ce qui est le défaut d'origine avec une
étape de plus. Le refus est acceptable précisément parce que **deux sorties honnêtes existent** :
déclarer l'absence (D1), ou rendre la main (D4).

### D3 — Rien n'est jamais détruit ; la reprise est le défaut

Ce que l'évaluateur a saisi reste **exactement tel quel** : les lignes par critère persistent
côté serveur et côté téléphone (#307/#308). Aucune remise à zéro, aucun ménage.

Comme aucune horloge ne ferme une rotation (ADR-0014), **« le réseau est revenu » ne demande
aucun mécanisme nouveau** : l'évaluateur réouvre l'écran, ses valeurs sont rejouées, il continue.

### D4 — L'évaluateur peut RENDRE LA MAIN, il ne peut pas abandonner en silence

Nouvel acte : **« je ne peux pas terminer »**. Il :

- laisse les notes partielles intactes ;
- place le couple en **SANS VERDICT — signalé**, avec auteur et horodatage ;
- apparaît immédiatement sur le Suivi du responsable.

**Le motif est choisi dans une LISTE FERMÉE** — *décision de Nada : « les listes sont toujours
plus ergonomiques que laisser l'utilisateur taper »*. Pas de saisie libre :

1. **Panne réseau ou appareil**
2. **Coupure de courant**
3. **Fin de ma journée — je ne reprendrai pas**
4. **Incident en salle**

Quatre entrées, parce que chacune appelle une réponse différente du responsable — un motif qui
ne change rien à la décision n'a pas à être demandé. Le n° 4 tient le rôle de fourre-tout
explicite : il dit au responsable « viens me parler », ce qu'un texte libre ferait moins bien et
moins vite.

**Sans cet acte, « je suis parti » est indiscernable de « j'ai oublié »** — et c'est toute
l'ambiguïté de #297.

### D5 — Le responsable a trois actes, tous attribués et motivés ; aucun n'est automatique

1. **Faire terminer** — confier la station à un autre examinateur. Existe déjà
   (`remplacer-evaluateur`, ADR-0017 cas C). Meilleure issue : un vrai verdict.
2. **Neutraliser cette station pour cet étudiant** — décision explicite et motivée de la
   retirer du barème **de cet étudiant**. C'est l'issue « 45/60 », rendue légitime par le fait
   qu'une personne la prend et la signe.
   **Le responsable seul suffit** — *décision de Nada : « toute autre consultation peut se faire
   hors application »*. Pas de contreseing du jury dans le produit.
3. **Escalader au jury** — aucun verdict, aucune mention, aucun rang ; marqué pour délibération
   (ADR-0021 D4 : l'écran de délibération précède toute statistique).

⛔ **Ce que le responsable ne peut PAS faire : saisir une note à la place de l'examinateur.**
Toute modification de note passe par le réajustement audité (ADR-0013), qui exige une note
existante. Pour une note absente, ce sont les actes 1, 2 ou 3 — jamais « taper un chiffre ».

### D6 — Pas de verdict, pas de mention ni de rang ; et la clôture ne passe pas en silence

- Tant qu'aucun acte de D5 n'a tranché, l'étudiant apparaît **« incomplet — en attente de
  décision »** : ni mention, ni rang, et l'export porte l'état.
  *Aujourd'hui le rang est calculé sur `moyenne20`, donc un résultat incomplet est classé contre
  des résultats complets sans le moindre marqueur.*
- **La clôture (UC-71, ADR-0022) est le point de contrôle** : elle ne peut pas geler une épreuve
  en laissant des couples SANS VERDICT non tranchés. Soit ils sont résolus, soit le
  procès-verbal (UC-77) les porte comme tels, avec leur motif et leur auteur.

Ce n'est pas de la publication aux étudiants — ADR-0022 la place hors périmètre, et cet ADR ne
la ramène pas.

---

## Conséquences

- **L'étudiant** ne perd jamais de points parce qu'une machine ou un examinateur a failli, et
  n'est jamais rééchelonné en silence : si son épreuve a compté 3 stations, une personne l'a
  décidé et son nom est dessus.
- **L'évaluateur** est arrêté s'il oublie, ne perd aucune frappe, reprend sans cérémonie, et
  dispose d'une façon honnête de dire « pas moi, pas aujourd'hui ».
- **Le responsable** voit tous les couples sans verdict et a de vrais actes — mais ne peut pas
  inventer une note.
- **Coût :** un refus (local, donc hors-ligne aussi), un acte évaluateur, deux actes
  responsable, un état de plus à afficher. Le refus seul supprime le cas le plus fréquent
  (l'oubli).
- **Régression assumée :** des verrouillages qui passent aujourd'hui seront refusés. C'est le but.
- **Séquence de livraison :** #297 d'abord — c'est la garde, elle est locale, petite, et elle
  supprime à elle seule le cas « oubli ». Puis #316, qui rend la garde acceptable dans le cas
  « panne ». Puis #317, qui donne au responsable de quoi trancher.

## Ce que cet ADR ne décide PAS

- **Le format du procès-verbal** (UC-77) — toujours ouvert, ADR-0022 le dit déjà.
- **La publication aux étudiants** — hors périmètre (ADR-0022), inchangé.
- **Le barème global d'un examen** : neutraliser une station vaut **pour un étudiant**. Un
  barème faux pour tout le monde, c'est #276, et c'est un autre problème.
- **Ce qui arrive à une station neutralisée dans les statistiques psychométriques** —
  ADR-0021 D4 impose la délibération d'abord ; la question se posera là.
- **Le placement exact de l'état** (colonne sur `Notation`, ou table dédiée à côté de
  `notation_adjustments`) — choix d'implémentation, à trancher dans le ticket.
