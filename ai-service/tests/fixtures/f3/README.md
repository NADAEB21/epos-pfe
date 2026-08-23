# Fixtures de vérité F3 (#358) — le contrat JSON

Ce dossier reçoit les fixtures calculées **à la main** par F3 (8 étudiants ×
4 critères + tableur). `tests/test_fixtures_f3.py` découvre automatiquement
tout `*.json` posé ici et vérifie le moteur contre les valeurs attendues —
aucun code à écrire côté F3, juste déposer le fichier.

⚠️ Tant que le dossier est vide, le test s'affiche `SKIPPED` avec la raison
« F3 (#358) pas encore livré » — c'est voulu, jamais un faux vert.

## Schéma d'un fichier

```json
{
  "nom": "f3-8x4-nominal",
  "commentaire": "Renvoi vers le tableur (chemin dans le dépôt) et la ligne de calcul.",
  "criteres": [
    { "item_id": 1, "type": "BINAIRE",   "ponderation": 5,  "valeur_max": null },
    { "item_id": 2, "type": "NUMERIQUE", "ponderation": 10, "valeur_max": 10 }
  ],
  "notations": [
    { "etudiant": "E1", "valeurs": { "1": 1, "2": 8 } },
    { "etudiant": "E2", "valeurs": { "1": 0, "2": 5 } }
  ],
  "attendus": {
    "difficulte":     { "1": 0.5,   "2": 0.65 },
    "discrimination": { "1": 0.62,  "2": 0.71 },
    "alpha_cronbach": 0.71
  },
  "tolerance": 0.005
}
```

## Sémantique (identique au moteur — voir `app/stats/engine.py`, en-tête)

- **difficulté** : BINAIRE → proportion d'acquis ; NUMERIQUE → `moyenne/valeur_max` ;
- **discrimination** : Pearson corrigée item vs (total − item), le total étant la
  somme des **contributions pondérées** (BINAIRE → `valeur × ponderation`,
  NUMERIQUE → `valeur` brute) ;
- **α de Cronbach** : sur les contributions pondérées, variances **échantillon**
  (`ddof = 1` — au tableur : `VAR()` / `VAR.S()`, PAS `VAR.P()`).

## Seuils — et comment tester quand n = 8

Les seuils de production (p : n ≥ 10 · r et α : n ≥ 15) refuseraient un jeu de
8 étudiants. Le harnais F3 compare donc les **formules**, seuils désactivés
(c'est le calcul qu'on vérifie au tableur, pas le contrat de refus — lui a ses
propres tests). Champ optionnel `"attendus_refus"` si vous voulez AUSSI vérifier
un refus (`{"discrimination": {"1": "non concluant — effectif insuffisant (n=8 < 15)"}}`).
