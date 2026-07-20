# Suite E2E évaluateur — catalogue de scénarios

Pilote l'**app réelle** contre le **backend réel** via `integration_test` + chromedriver.
Remplace la vérification manuelle : `integration_test` pilote l'**arbre de widgets**, pas les pixels,
donc le problème de rastérisation CanvasKit (Playwright/Selenium) **ne s'applique pas**.

## Lancer

```bash
# 1. pile Docker up (l'app tape localhost:8080 — ceci n'est PAS hermétique)
docker compose -f infrastructure/docker-compose.yml up -d

# 2. chromedriver (version = MAJEURE de Chrome ; 150.0.7871.124 pour Chrome 150.x)
chromedriver --port=4444

# 3. un scénario
cd epos_mobile
flutter drive --driver=test_driver/integration_test.dart \
              --target=integration_test/smoke_test.dart -d chrome
```

## Règles de la suite

1. **Chaque scénario restaure sa fixture** en `tearDownAll`, puis vérifie la restauration.
   Une fixture non restaurée fausse le scénario suivant *et* la session suivante.
2. **Jamais d'assertion dérivée de `now`.** Doctrine ADR-0014 : l'horloge ne pilote rien.
   Un test qui calcule un statut attendu à partir d'un offset temporel **cimente le chronomètre**.
   On paramètre par `statut`, pas par durée.
3. **Le plancher vs le plafond** (distinction ouverte, cf. §Doctrine) : l'horloge peut imposer un
   *plancher* (l'étudiant a droit à son temps) mais jamais un *plafond* (retirer une session
   expirée). Les scénarios notent lequel est testé.
4. Un scénario qui décrit un comportement **défectueux actuel** le dit en commentaire et **doit
   échouer** quand #207 atterrit. C'est son rôle.

---

## S1 — Cycle de vie nominal (responsable → évaluateur)

| id | scénario | statut |
|---|---|---|
| S1-1 | responsable lance l'examen → l'évaluateur voit une carte notable | ⬜ |
| S1-2 | noter les 4 critères × 2 étudiants → score calculé correct | ⬜ |
| S1-3 | valider étudiant → valider groupe → le web Suivi passe au vert | ✅ observé manuellement (capt 5) |
| S1-4 | « Groupe suivant » → groupe 2 chargé, bons étudiants | ⬜ |
| S1-5 | dérouler les 4 groupes → lot complet | ⬜ |

> ⚠️ **Cibles déclarées mais pas encore écrites.** `run_scenario.sh` propose `S1-nominal`,
> `S2-1-derive` et `S2-2-entre-groupes`, qui pointent vers `nominal_test.dart` et
> `dead_end_test.dart` — **ces deux fichiers n'existent pas**. Les scénarios correspondants
> échouent donc immédiatement (fixture posée, puis `flutter drive` sur une cible absente).
> Les scénarios opérationnels aujourd'hui : `smoke`, `timer-anchor`, `render-audit`,
> `grading-nominal`, `grading-outage`.

## S1b — L'écran de NOTATION (session 22) — `grading_screen_test.dart`

Premier pilotage réel de l'écran de notation. Jusqu'ici seul l'accueil était piloté.

| id | scénario | commande | statut |
|---|---|---|---|
| S1b-1 | minuteur ancré serveur + ticker vivant + audit de rendu | `run_scenario.sh grading-nominal` | ✅ **VERT** 2026-07-20 |
| S1b-2 | station figée au snapshot, exam-service coupé | `run_scenario.sh grading-outage` | ❌ **ROUGE — attendu, voir #244** |

**S1b-1 mesuré :** ouverture `499 s` (station de 15 min, passage vieux de ~6,7 min) → `495 s` après
`4071 ms` réels. L'ouverture prouve l'**ancrage serveur** (avec le `;` de #239 elle valait 900 s), le
delta prouve le **ticker**. Intitulé de station **réel**, zéro motif de repli sur 61 textes.

**S1b-2 échoue par conception tant que #244 n'est pas corrigé.** Le tableau de bord charge (gain
d'ADR-0015), puis `GET /stations/{id}/grille` → **503** car le mobile lit la grille **directement dans
exam-service**, hors snapshot. Ce rouge est un **constat**, pas un harnais cassé : il doit passer au
vert quand #244 atterrit. Ne pas le « réparer » en relâchant l'assertion.

### Deux faux résultats que ce test a produits avant d'être juste — ne pas les réintroduire

1. **`find.byType(ElevatedButton)` matche le type EXACT.** `ElevatedButton.icon` construit un
   sous-type privé, donc `widgetWithText(ElevatedButton, …)` ne voyait pas un bouton pourtant à
   l'écran. Cibler `find.text(...)`.
2. **Un scan global de `MM:SS` lisait le mauvais widget.** L'écran de notation est poussé PAR-DESSUS
   l'accueil : `tester.allWidgets` contient encore `heureDebut` (« 02:40 »), de même forme que le
   minuteur (« 08:17 »). Les deux échantillons lisaient ce libellé **statique** ⇒ « écart 0 s » ⇒ faux
   « ticker mort » très crédible. **Scoper l'assertion au widget** (`PassageCountdownBadge`), jamais à
   une forme de texte.
3. **`tester.pump(Duration)` n'avance PAS l'horloge réelle** (contrairement à FakeAsync) alors que le
   `Timer.periodic` du bloc tique sur le temps mur. Attendre sur `DateTime.now()`, et garder la garde
   anti-vacuité qui échoue si le temps réel n'a pas passé.

## S2 — Les impasses (le cœur de #238)

**Trois chemins distincts mènent au même écran « Aucune session en cours ». Ne pas les confondre.**

| id | scénario | signature distinctive | statut |
|---|---|---|---|
| S2-1 | **dérive** : fenêtre dépassée de `duree+30min`, rotation toujours `EN_ATTENTE` | stats = **4**, planning peuplé | ✅ mesuré (session 19) |
| S2-2 | **entre-groupes** : groupe validé en avance, le suivant est `A_VENIR` | stats = **4**, planning `✓ ✓ »` | ✅ observé (capt 7) — **le plus fréquent** |
| S2-3 | **examen terminé** par le responsable | stats = **0/0/0**, planning **vide** | ✅ observé (capture 11) |
| S2-4 | verrouillage en cours de notation : noter 1 item, laisser la fenêtre expirer, revenir | note orpheline en base, inatteignable | ✅ mesuré (session 19) |

## S3 — Grille : formes non testées ⚠️

**Tout ce qui a été testé jusqu'ici utilise la grille 5, PLATE (4 items, 0 sous-critère).**
`items_evaluation.parent_id` existe ; les grilles **38** et **40** ont **2 sous-critères / 3 items**.

| id | scénario | risque visé | statut |
|---|---|---|---|
| S3-1 | grille avec **sous-critères** (parent + enfants) : rendu mobile | l'UI affiche-t-elle la hiérarchie ou aplatit-elle ? | ⬜ |
| S3-2 | sous-critères : **calcul du score** | `recalculerScoreFinal` somme-t-il parent **et** enfants (double comptage) ? | ⬜ |
| S3-3 | grille mixte BINAIRE + NUMERIQUE (grille 5 l'est déjà) | pondération BINAIRE = `valeur × ponderation` vs NUMERIQUE brut | ⬜ |
| S3-4 | grille **vide** / station sans grille | 404 vs écran vide | ⬜ |
| S3-5 | `valeur` hors bornes (> `valeur_max`, négative) | validation côté serveur | ⬜ |
| S3-6 | grille imbriquée créée depuis un **template** | famille de bugs #225/#226 (FK récursive) | ⬜ |

## S4 — Intégrité de la notation

| id | scénario | statut |
|---|---|---|
| S4-1 | étudiant **absent** → `valider` avec `absent:true` | ⬜ |
| S4-2 | note **verrouillée** : re-saisie refusée | ⬜ |
| S4-3 | verrouillé puis **déverrouillage impossible** (remarque Nada) — canal réajustement seulement | ⬜ |
| S4-4 | notation **partielle** puis validation groupe → dialogue « Étudiants non validés » | ✅ observé (capt 3) |
| S4-5 | `validerGroupe` par un **autre** évaluateur (#211) | ⬜ |

## S5 — Oversight responsable (web, Playwright)

| id | scénario | statut |
|---|---|---|
| S5-1 | lancer un examen dont des stations n'ont **aucun évaluateur assigné** | 🐞 **passe aujourd'hui** (capt 4) |
| S5-2 | bannière temps : `Temps effectif écoulé` vs `dépassement` incohérents | 🐞 observé (capt 4/9) |
| S5-3 | `Terminer l'examen` → vérifier l'état évaluateur | ✅ observé (capture 11) |
| S5-4 | pause / reprise → l'horloge mobile se fige | ⬜ |
| S5-5 | Résultats : moyennes/mentions publiées sur notation **incomplète** | 🐞 observé (capture 12) |

## S6 — Hors ligne / temps réel

| id | scénario | statut |
|---|---|---|
| S6-1 | push STOMP : validation → le web Suivi bouge sans refresh | ⬜ |
| S6-2 | hors ligne : noter sans réseau puis resynchroniser | ⚠️ **no-op sur Flutter Web** (`kIsWeb` early-return) — exige un vrai device |
| S6-3 | deux évaluateurs, deux stations, même lot | ⬜ |

---

## Doctrine (ADR-0014 / 0014-A) — à relire avant d'écrire un scénario

**PLAN** (quel jour un lot tourne — stocké, envoyé par mail) vs **PACE** (quand un groupe avance —
action explicite de l'évaluateur, **jamais** déclenchée par l'horloge).
**Le lancement est IMPOSÉ, la clôture est DÉRIVÉE.**
Règle maison : si tu calcules un état à partir de `now` / `debutCreneau` / d'un compte à rebours,
**arrête** — c'est le chronomètre.
