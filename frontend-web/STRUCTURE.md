# frontend-web — organisation du code

Ce document existe pour que **l'admin dashboard, le module IA et tout écran à
venir** se rangent au même endroit que le reste, au lieu de rouvrir la question à
chaque fois. Il décrit ce qui est appliqué aujourd'hui, pas une intention.

Référence : le [guide de style Angular](https://angular.dev/style-guide) —
*folders-by-feature*, *aussi plat que possible*, une responsabilité par fichier.

## Arborescence

```
src/app/
├── core/                    # ce qui est chargé une fois, pour toute l'app
│   ├── api/                 # clients HTTP + modèles de l'API
│   ├── auth/                # session, garde de route, intercepteur, stockage du token
│   └── layout/              # coquille : app-shell, sidebar, topbar
├── shared/                  # briques réutilisables, sans logique métier
└── features/                # un dossier par domaine fonctionnel
    ├── auth/login/
    ├── access/              # écran « accès refusé »
    ├── home/                # accueil du responsable
    ├── admin/
    ├── bibliotheque/        # bibliothèque de modèles de grilles
    └── examens/
        ├── examens-list · examen-create      # entrée : lister / créer
        ├── workspace/       # coquille à onglets + store partagé + vue d'ensemble
        ├── preparation/     # stations & grilles, étudiants, lots, convocations
        ├── jour-j/          # lancement, suivi
        └── resultats/       # résultats, réclamations
```

`features/examens/` suit **les phases du cycle de vie d'un examen**, le même
vocabulaire que l'interface et que les ADR. On relie un écran à un dossier sans
lire une ligne de code.

## Règles

1. **Un template = un fichier `.component.html`.** Le guide Angular fixe la
   limite à 3 lignes ; au-delà, on sort le markup. Un `.ts` doit se lire comme de
   la logique, pas comme une page HTML. (Historiquement le projet avait les deux
   conventions à la fois : `login`/`sidebar` séparés, tout le reste inline, d'où
   des fichiers de 1 400 lignes.)
2. **Pas de style inline.** La mise en forme passe par les classes Tailwind dans
   le template. Un `.scss` seulement quand Tailwind ne suffit pas.
3. **`core/` = singletons.** Y mettre un composant d'écran est une erreur : `core`
   est ce dont toute l'application dépend.
4. **`shared/` = réutilisable ET sans métier.** On n'y déplace un composant
   qu'après une **deuxième** utilisation réelle — pas « au cas où ».
5. **Une feature qui a sa propre route de premier niveau est une feature.** C'est
   pourquoi `bibliotheque/` a quitté `examens/` : ce n'est pas un onglet d'examen.
6. **Composants standalone + `loadComponent`.** Pas de NgModule ; chaque route
   charge son composant à la demande (déjà en place partout).
7. **Nommage `sujet.type.ts`** — `etudiants.component.ts`, `auth.guard.ts`,
   `examen-workspace.store.ts`. Fichiers et dossiers en `kebab-case`.

## Où poser le prochain écran

| ce que vous ajoutez | où |
|---|---|
| un onglet dans l'espace de travail d'un examen | la sous-phase concernée : `preparation/`, `jour-j/`, `resultats/` |
| un écran d'administration | `features/admin/` |
| les écrans d'analyse IA | `features/analyses-ia/` (route `analyses-ia` déjà réservée) |
| un appel HTTP vers un service | le client `core/api/` du service concerné |
| un état partagé par plusieurs onglets | un `*.store.ts` à côté des écrans qui le partagent |
| un bouton/tableau réutilisé ailleurs | `shared/` — après la 2ᵉ utilisation, pas avant |

## Vérifier une réorganisation

Un déplacement de fichiers **se vérifie**, il ne se relit pas : `ng build` est
typé et refuse tout import cassé. Mais compiler n'est pas afficher — un template
peut compiler et rendre un panneau vide.

```bash
npx ng build                    # imports + templates (AOT)
npx ng test --watch=false       # unitaires
node C:/Users/Nada/pwverify/s31-routes-smoke.js          # les 13 routes rendent
node C:/Users/Nada/pwverify/s31-grille-editor-probe.js   # le plus gros template
```

`s31-routes-smoke` visite chaque route et vérifie qu'elle affiche du contenu réel,
sans erreur console, sans fuite `null`/`undefined`/`NaN`, sans accolades
d'interpolation à l'écran. `EXAM_ID` doit pointer sur un examen configuré.
