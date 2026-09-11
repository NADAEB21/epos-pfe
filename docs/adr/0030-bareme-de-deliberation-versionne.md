# ADR-0030 — Le barème de délibération : un artefact ADDITIF et versionné dans scoring

**Statut : Accepté (2026-08-21, avec ADR-0021 et ADR-0029 — voir ADR-0021 §Status). Livré :
#361 entité + V25 + les deux dénominateurs (PR #380) · #362 propositions (l'effet projeté
AVANT décision est calculé par ai-service avec l'arithmétique EXACTE de
`BaremeDeliberationEngine`, lue par les vues V26) · #364 recette (PR #387) · #363 UI (PR #394)
· **#401 (2026-09-04) : la lecture délibérée EST le résultat — D4 révisé.**
**Décideuse : Nada. Préparé en S43, sur le plan IA/BI de S42.**
**Exécute : ADR-0021 D6–D10 (Part 2 — « ce que changer le barème veut dire »). Donne sa
forme au besoin que D9 nommait sans le dessiner (« a barème needs versions »), et sa
définition finale à #135. Implémentation : #361 (entité), #362 (propositions), #363 (UI).**

## Contexte

ADR-0021 Part 2 a établi le fond : le jugement de l'examinateur ne change **jamais** (D6),
seule l'agrégation peut changer, uniformément et motivée ; trois opérations permises,
classées par défendabilité (D8) ; le snapshot gelé d'ADR-0015 reste intact — un re-barème
est un **second artefact par-dessus** (D9) ; l'IA propose, l'humain décide, l'effet projeté
est visible AVANT l'acte (D10). Restait à décider : le schéma, la sémantique de version,
qui écrit, et comment les résultats ajustés sont servis.

## Décision

### D1 — L'artefact vit dans SCORING, écrit par le responsable, jamais par l'IA

Le barème de délibération est un **acte du responsable** : il vit dans scoring_db
(migration V·n), écrit par un endpoint scoring
(`POST /examens/{id}/bareme-deliberation`) gardé **matière + rôle**, sur le modèle exact du
réajustement ADR-0013 : `motif` **obligatoire**, auteur enregistré, examen **CLOS**
uniquement. `ai-service` n'a aucun chemin d'écriture vers scoring (ADR-0029 D2) : une
proposition ne devient un barème que par la main du responsable. Le journal de ce que l'IA
a proposé (et de la décision, refus compris) vit dans `ai_db` — deux artefacts, deux
propriétaires.

### D2 — Le schéma : une en-tête motivée + des opérations typées

- **`bareme_deliberation`** : `examen_id`, `motif` (obligatoire), `cree_par`, `created_at`,
  `version` (1..n par examen).
- **`bareme_deliberation_operation`** (lignes filles) : `type` ∈ {`EXCLURE_CRITERE`,
  `EXCLURE_STATION`, `REPONDERER`}, la cible (id de critère du snapshot / id de station),
  et pour `REPONDERER` la nouvelle échelle. **Exactement les trois opérations d'ADR-0021
  D8, aucune autre** — le type est une énumération fermée, pas un champ libre.

Les cibles référencent les ids du **snapshot** (`exam_item_snapshot`), pas la grille
vivante : le barème délibéré est défini par rapport à ce qui a réellement servi à noter.

### D3 — Sémantique de version : lignes immuables, la dernière fait foi

Un barème n'est **jamais modifié ni supprimé** : corriger, c'est écrire une **nouvelle
version** (motivée) qui remplace la précédente comme version courante ; revenir au barème
d'origine, c'est une version explicitement vide (« retour au barème du lancement », motif à
l'appui). Toute l'histoire reste lisible — même contrat d'immuabilité que la chaîne
réajustement d'ADR-0013, et la seule sémantique compatible avec un procès-verbal (W11) :
ce que le jury a vu à l'instant T est reconstructible.

### D4 — L'effet : recalcul de PRÉSENTATION, les deux dénominateurs servis

Aucune valeur brute n'est réécrite, aucun `score_final` stocké n'est modifié. Les résultats
ajustés sont **calculés à la lecture** en appliquant la version courante sur le snapshot
intact, conformément à l'arithmétique d'ADR-0021 D7/D8 :

- **exclusion** (critère ou station) : la contribution sort de la somme, le dénominateur
  devient le maximum atteignable restant — au choix de présentation, brut (« /15 ») ou
  reconverti (« ×20/15 »), les deux honnêtes ;
- **repondération** : re-mise à l'échelle proportionnelle de la performance vers la
  nouvelle échelle (le déplacement de budget seul est un no-op — la découverte centrale
  d'ADR-0021 Part 2).

L'écran Résultats sert **toujours les deux lectures** : le résultat au barème d'origine ET
au barème délibéré, l'historique et les motifs visibles. Le jury voit ce qui a changé, par
qui, pourquoi.

> **Révision 2026-09-04 (décision Nada, #401)** — les deux lectures restent servies, mais
> elles n'ont pas le même statut : **la lecture délibérée EST le résultat**. Dès qu'une
> version existe et que scoring la sert (couverture snapshot complète, ADR-0015), le total,
> la moyenne /20, le rang, la mention, l'export et les agrégats BI (tendances, synthèse) sont
> calculés sous le barème délibéré. La note saisie reste **la trace** : jamais réécrite,
> toujours visible (cellules par station, colonne « Origine /20 », colonnes « … origine » de
> l'export). Deux règles d'arithmétique d'écran, fixées ici : le dénominateur effectif est
> **par étudiant** (somme des maxima délibérés des stations qu'il a passées — jamais le
> dénominateur d'examen, identique pour tous), et la garde de verdict #297 (verrou sur toutes
> les stations) vaut pour les deux lectures. Une station exclue sort des deux sommes et n'est
> plus un échec de personne. Invariant tenu côté écran : Σ des scores délibérés par station =
> total délibéré servi ; sinon la lecture délibérée n'est pas servie comme résultat et l'écran
> le dit. N9 (#394) avait livré une simple colonne à côté du classement d'origine — c'était
> une lecture trop timide de ce paragraphe, corrigée par #401.

### D5 — Les gardes, héritées telles quelles

Refus nominatifs et sentinelles exigés (recette adversariale #364) : examen non clos ·
hors matière (#274) · motif vide · double application de la même version · toute tentative
de toucher au snapshot (sentinelle **byte-à-byte** dans les critères de #361). Le
per-étudiant reste le territoire de la réclamation (ADR-0013 / #136) — jamais du barème.

## Conséquences

- #135 est enfin implémentable : D6–D9 d'ADR-0021 lui donnaient le sens, cet ADR lui donne
  le schéma. Il se ferme par #361.
- Le procès-verbal (W11, P1 d'après-IA) consommera la version courante et son historique —
  le PV dit sur quel barème les notes sont arrêtées.
- Perspective produit (non engagée) : une proposition acceptée peut générer la grille
  corrigée **en modèle** pour la session suivante — la passerelle vers ADR-0027.
- ⚠️ Implémentation : update-in-place n'existe pas ici par construction (lignes immuables),
  mais le piège JPA delete+insert (23505) reste à surveiller sur les collections filles.

## Explicitement NON décidé ici

- ~~La présentation par défaut (brut « /15 » vs reconverti « /20 ») — choix d'écran, à
  trancher en #363 avec Nada devant les maquettes.~~ **Tranché** : #363 sert les deux
  écritures (brut « x / d » et « ≈ y /20 ») ; #401 (2026-09-04) tranche le point qui restait
  implicite — **la lecture délibérée est le résultat**, l'origine est la trace (voir D4,
  révision).
- L'éventuelle signature/verrouillage du barème à la clôture institutionnelle (#236/W12) —
  après l'IA, avec le dossier P1.
