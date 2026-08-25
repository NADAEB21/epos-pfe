# F1 — Générateur de cohorte synthétique (IA/BI)

Épic #354, tâche F1 (P0, taille L). Exécute le plan canonique §8 : produire une
cohorte réaliste **via l'API publique uniquement**, avec 3 défauts psychométriques
plantés et documentés, pour que la démo jury montre le moteur (#357/N5) **retrouver**
les défauts semés — la parade au risque n°1 du plan (29 notations réelles en base
au 20/08/2026).

## Lancer le générateur

Prérequis : la pile tourne (`docker compose up -d` dans `infrastructure/`) et les
comptes seed d'`init.sql` existent (`admin@epos.tn` / `resp@epos.tn`).

```bash
python scripts/generate-cohorte-ia.py
# ou, contre une autre pile / avec plus d'examens de contrôle :
python scripts/generate-cohorte-ia.py --base-url http://host:8080/api/v1 --n-control 3
```

Rejouable d'une seule commande sur une pile fraîche. Chaque run crée de **nouveaux**
examens et étudiants, noms et matricules suffixés par la graine RNG **et** un
identifiant de run (`run_id`, aléatoire, découplé de `--seed`) — deux runs au
même seed ne collisionnent donc jamais sur un nom d'examen ou un numéro
d'inscription. Le script ne modifie ni ne supprime jamais rien d'existant, et
clôture systématiquement (`TERMINE`) l'examen de référence dans un bloc
`finally` : même si le script plante en cours de route (comme lors des
itérations de mise au point de F1), aucun examen ne reste `EN_COURS` à
bloquer les runs suivants via la garde #265 (évaluateurs déjà engagés). 
Les comptes évaluateurs dédiés (`ia.f1.eval.a/b/c@epos.tn`) sont créés une fois puis 
réutilisés (409 toléré et résolu par relecture de `/users?role=EVALUATEUR`).

Le script ne fait **aucun INSERT SQL** : chaque écriture passe par l'API publique
(auth-service, exam-service, scoring-service via le gateway), donc par les mêmes
gardes qu'en production (#274 périmètre matière, ADR-0015 snapshot au lancement,
ADR-0019 barème atteignable, ADR-0014/-A/-B cycle de vie évaluateur-cadencé...).

À la fin, le script imprime un **bilan auto-calculé** (à partir des valeurs
réellement envoyées, pas d'une relecture DB) qui permet de vérifier tout de suite
que les défauts sont bien dans la fourchette visée.

## Graine et reproductibilité

`--seed` (défaut `20260821`) initialise `random.Random` : à seed identique, les
tirages sont identiques d'un run à l'autre (module `random` du stdlib Python,
implémentation Mersenne Twister stable entre versions 3.x). Les valeurs
« visées » ci-dessous sont donc rejouables — elles ne sont **pas** garanties au
chiffre près (c'est un tirage, pas une constante), mais l'ordre de grandeur et le
signe le sont, avec les effectifs choisis.

## Ce qui est créé

| | Examens de contrôle (`--n-control`, défaut 2) | Examen de référence (1, toujours) |
|---|---|---|
| Statut final | `TERMINE` (clos) | `TERMINE` (clos) |
| Stations | 3 (« Station A/B/C »), 4 items chacune, aucun défaut | 3 stations, dont 2 portent un défaut (voir tableau ci-dessous) |
| Étudiants | 18 (1 lot) | 36 (2 lots de 18) |
| Notations verrouillées / station | 18 | 36 (Défauts, Témoin) ou 18+18 (Sévérité, split par lot) |

Chaque étudiant se voit attribuer une **habileté latente** `theta ~ N(0,1)`, tirée
une fois et réutilisée pour tous ses items « normaux » de tous les examens : c'est
elle qui rend un item normal réellement discriminant (corrélé au score), par
construction — le générateur ne simule pas seulement des défauts, il simule
d'abord un instrument SAIN pour que le contraste soit lisible.

## Les 3 défauts plantés — où les trouver, et valeurs visées

Tous vivent dans l'examen nommé `IA-F1 — Cohorte de référence (défauts plantés) —
seed<SEED>-<RUN_ID>` (matière 1, Chimie thérapeutique — comme les autres scripts
de démo du dépôt). L'identifiant réel de l'examen et des stations/items est imprimé
par le script en fin de run (bloc « Identifiants clés »).

### Défaut 1 — un critère où (presque) personne ne réussit

- **Où** : station « Station Défauts », critère **« Critère impossible »**
  (BINAIRE, pondération 5).
- **Comment** : la valeur est tirée `1` avec une probabilité fixe de **5 %**,
  **indépendamment** de l'habileté de l'étudiant (`P_CIBLE_IMPOSSIBLE = 0.05` dans
  le script).
- **Effectif** : n = 36 (les deux lots passent tous par cette station).
- **Indice attendu côté moteur** (`app.stats.engine.difficulte`) :
  `statut = CONCLUANT` (n ≥ 10), `valeur (p) ≈ 0.05`, IC bootstrap resserré
  proche de 0. Interprétation psychométrique : item beaucoup trop difficile,
  candidat à un retrait de barème (ADR-0021 D8 opération 1).

### Défaut 2 — un critère qui ne discrimine pas

- **Où** : même station « Station Défauts », critère **« Critère sans lien »**
  (BINAIRE, pondération 5).
- **Comment** : pile ou face à 50 %, **indépendant** de l'habileté (aucune
  corrélation construite avec `theta` ni avec les autres items).
- **Effectif** : n = 36 (détail complet — les 4 items de la station sont
  toujours tous remplis, aucun étudiant absent).
- **Indice attendu côté moteur** (`app.stats.engine.discrimination`) :
  `statut = CONCLUANT` (n ≥ 15), `valeur (r) ≈ 0`, IC bootstrap encadrant 0.
  Interprétation : item qui ne sépare pas les bons des faibles — même verdict
  que l'exemple documenté dans le plan (« ce critère n'a séparé personne »).
- **Vérification en fin de run** : le script imprime la corrélation
  observée(habileté, note) pour ce critère (doit être proche de 0) **et**, en
  contraste, celle de l'item normal « Précision du geste » de la même station
  (doit être nettement non nulle — sinon c'est le générateur lui-même qui est
  cassé, pas un défaut à documenter).

### Défaut 3 — un évaluateur décalé de +2 points/critère (sévérité intra-station)

- **Où** : station « Station Sévérité » (2 critères NUMERIQUE, pondération 10
  chacun, `valeurMax = 10`), **même grille, même station, même examen**.
- **Comment** : la Station Sévérité est notée par l'évaluateur `ia.f1.eval.a`
  sur le **lot 1** (18 étudiants, décalage 0 — la référence). Pour le lot 2,
  l'examen étant déjà `EN_COURS`, `PATCH /api/stations/{id}/evaluateurs`
  (exam-service) est **bloqué** — `isGrilleModifiable()` (ADR-0015) n'admet
  que `BROUILLON`/`CONFIGURE`. La bascule passe donc par le canal légitime
  post-lancement, la **suppléance ADR-0017 (cas C)** :
  `POST /api/lots/{lotId}/stations/{stationId}/remplacer-evaluateur`, motif
  obligatoire — appelée **après** la génération des rotations du lot 2
  (`presence-et-demarrer`) et **avant** son ouverture (`POST .../ouvrir`), le
  seul ordre où les rotations de la station existent sans qu'aucune ne soit
  encore `EN_COURS`. Le résultat est identique : `ia.f1.eval.b` note le lot 2
  avec un décalage **+2 points par critère**, capé à `valeurMax`.
- **Pourquoi c'est bien une comparaison *intra-station*** au sens d'ADR-0021 D2 :
  même station, même grille, même examen, cohortes tirées de la **même**
  distribution d'habileté (`theta ~ N(0,1)` dans les deux lots) — exactement la
  condition d'« overlap » que D2 exige pour qu'une comparaison de sévérité soit
  valide (jamais une moyenne globale entre examens différents).
- **Effectif** : n = 18 par évaluateur, 36 au total sur la station.
- **Indice attendu** : cet indice (sévérité/lenience évaluateur, D1/D2 d'ADR-0021)
  est livré depuis N6 (#359, PR #374) : `GET /ai/examens/{id}/evaluateurs` doit
  retrouver un écart moyen ≈ **+2 points/critère** (≈ +4/20 au total) entre les
  deux évaluateurs sur cette station — **vérifié au run réel** (voir la section
  « Vérifié contre le moteur réel » : ±4.61/20, CONCLUANT, IC hors de 0). Le
  script calcule et imprime aussi lui-même la moyenne observée par évaluateur et
  l'écart — la preuve que les données sont plantées comme annoncé, indépendamment
  du moteur.

### Item « normal » de contrôle (pas un défaut)

Le 4ᵉ critère de la Station Défauts (« Précision du geste », NUMERIQUE) et les 4
critères de la Station Témoin sont des items **sains**, corrélés à `theta` par
construction. Ils servent de repère : si leur discrimination ressort proche de 0
au calcul, c'est le générateur qui a un bug — pas un défaut « métier » à
documenter.

## Statut de vérification (relecture croisée, 2 personnes)

Le script a été relu contre le **vrai** `EvaluateurDashboardService.java` (pas
seulement les DTO/controllers). Trois points, initialement signalés comme
« à confirmer », sont **vérifiés positifs** :

1. `LotDetailResponse.groupeSuivantDisponible` — champ réel, calculé par
   `rotationSuivante(rotation).isPresent()` (jamais un total de groupes
   codé en dur côté serveur non plus). Le script s'appuie donc sur
   exactement la même source de vérité que le serveur, et l'indexe
   **strictement** (`detail["groupeSuivantDisponible"]`, pas `.get(...)`) :
   une régression future du contrat fait planter le script bruyamment, au
   lieu de le faire silencieusement s'arrêter après le premier groupe.
2. L'ordre **saisir → verrouiller chaque étudiant → `validerGroupe` →
   `avancerGroupe`** n'est pas un choix arbitraire : `validerGroupe` **refuse**
   avec la liste nominative des étudiants tant qu'un seul n'a pas de
   notation verrouillée (`sansVerdict`, `BusinessException`). C'est la
   seule séquence qui passe cette garde.
3. `ValiderEtudiantRequest{grilleId, absent, commentaire}` correspond au DTO
   réel.

Un compteur de rangs codé en dur (`k_ranks`) a été envisagé puis **écarté** :
`avancerGroupe` calcule le rang suivant dynamiquement côté serveur
(`ordrePassage`), donc un total fixe côté client dériverait silencieusement
dès que la répartition n'est pas parfaitement homogène — exactement l'erreur
qu'ADR-0014 interdit (« on lit l'état, on ne le devine pas »).

**Mécanisme du défaut 3 — la suppléance ADR-0017, et ce n'est pas évitable.**
Un premier essai visait les cas A/B d'ADR-0017 (réaffecter la station comme
une simple assignation, avant le lancement) — mais l'examen est déjà
`EN_COURS` au moment de la bascule (lot 2, dans un examen à un seul
lancement), donc `affecterEvaluateurs` refuse systématiquement
(`isGrilleModifiable` n'admet que `BROUILLON`/`CONFIGURE`). Le seul chemin
possible post-lancement est le cas C : suppléance nominative, motivée,
tracée. F1 exerce donc réellement la suppléance ADR-0017 en passant — un
bénéfice accessoire : la cohorte de référence sert aussi de preuve vivante
que ce mécanisme fonctionne, sans que ce soit son but premier.

## Vérifié contre le MOTEUR RÉEL (run du 2026-08-25, seed 20260821, examen 80 de la base de dev)

Le premier run réel a été confronté aux endpoints N6 (`/ai/examens/{id}/indices`
et `/evaluateurs`). Les trois défauts sont **retrouvés** :

| Défaut | attendu | mesuré par le moteur |
|---|---|---|
| 1 — « Critère impossible » | p ≈ 0.05 | **p = 0.056** (CONCLUANT, n=36) |
| 2 — « Critère sans lien » | r ≈ 0 | **r = −0.08** (CONCLUANT, n=36) |
| 3 — sévérité intra-station | ≈ ±4/20 | **−4.61 / +4.61** (CONCLUANT, IC hors de 0, n=18+18) |

108 notations analysées, **zéro exclusion** (aucun `saisi_par` NULL, aucun détail
incomplet — le chemin API pur produit des données que les vues voient en entier).

**Deux lectures à connaître AVANT la démo (sinon un juré les découvre pour vous) :**

1. **Dans la Station Défauts, les items SAINS lisent une discrimination faible**
   (« Geste conforme » : r ≈ −0.03 ; « Précision du geste » : r ≈ 0.27). Ce n'est
   pas un bug du moteur ni du générateur : la discrimination corrèle l'item au
   *reste* de sa station, et ce reste est dominé par les deux items plantés
   (du bruit pur). Le **contraste sain se lit à la Station Témoin** (r = 0.24 à
   0.69 selon l'item) — c'est elle, le repère à montrer au jury, pas les voisins
   des défauts.
2. **α de Cronbach raconte la même histoire en bonus** : Station Défauts
   α ≈ 0.06 (les deux items plantés détruisent la cohérence interne — un
   4ᵉ signal gratuit), Station Témoin α ≈ 0.59 (saine), Station Sévérité
   **refusée** (k=2 < 3 critères — le contrat petits-N du moteur s'applique
   aussi à la cohorte synthétique, et c'est voulu).

## Utilisation par N5 / N6 (moteur statistique)

- Aucune configuration particulière n'est nécessaire côté `ai-service` : les
  notations sont verrouillées via les mêmes chemins que la production
  (`POST /evaluateur/notations/saisir` puis
  `POST /evaluateur/etudiants/{id}/stations/{id}/valider`), donc visibles par
  `v_ai_notations_verrouillees` / `v_ai_criteres` comme n'importe quelle donnée
  réelle.
- Le calcul ne portant que sur des examens **CLOS** (ADR-0029 D2), le script
  clôture systématiquement chaque examen (`PATCH .../statut?statut=TERMINE`) en
  toute fin de construction.
- Pour retrouver l'`examen_id` de référence sans réimprimer la sortie du script,
  chercher côté exam-service un examen dont le nom commence par
  `IA-F1 — Cohorte de référence`.

## Ce que le script ne fait PAS (hors périmètre F1)

- Pas d'anomalie type évaluateur/étudiant hors sévérité (stretch, hors scope).
- Pas de réajustement audité ni de barème de délibération : F1 fournit la
  matière première, pas la démo de bout en bout (ADR-0013/ADR-0030 — tâches N7-N9).
- Pas de suppression/nettoyage automatique : les examens générés sont des
  données de démonstration légitimes, destinées à rester en base pour la
  cohorte IA/BI — ce n'est pas un test jetable (contrairement à
  `e2e_rotation_generation.py` ou `verify_pause_resume.py`).
- Pas de retrait automatique des examens abandonnés d'anciens runs manuels
  (avant l'ajout du `finally`) : si un examen `IA-F1*` reste visible
  `EN_COURS`, le clôturer via `PATCH /examens/{id}/statut?statut=TERMINE`
  avant de relancer.