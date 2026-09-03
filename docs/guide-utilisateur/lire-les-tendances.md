# Lire les tendances (matière) et la synthèse (faculté)

Ces deux écrans sont la face transversale du module d'analyse : ils reprennent les
mêmes chiffres que l'écran de délibération d'un examen (distribution des totaux, taux de
réussite, échec par station) et les montrent **d'une session à l'autre**. Ils ne calculent
rien de nouveau et ne lisent que les examens **terminés ou archivés**.

## Tendances — pour le responsable de matière

Menu **Tendances** (espace de travail). L'écran montre :

1. **Les sessions closes de la matière**, dans l'ordre des dates, avec leur taux de réussite
   (réussite = total ≥ 50 % du barème, le même seuil que la délibération) et, au survol, la
   médiane ramenée sur 20. Cliquer une session la détaille.
2. **La session choisie** (la plus récente par défaut) : la distribution des totaux, l'échec
   par station, et — si un barème de délibération a été appliqué — la lecture avant → après.
   Un lien ouvre l'écran Résultats de l'examen.

Ce que l'écran ne fait pas :

- il n'affiche **aucun étudiant** : pour un cas individuel, c'est l'écran Résultats ;
- il ne compare pas deux stations de deux sessions différentes « automatiquement » : une
  station est un objet de sa session ; la lecture d'une dérive d'une année sur l'autre reste
  votre lecture, l'écran vous donne les deux barres côte à côte ;
- il ne lit pas les sessions en cours ou en préparation — elles sont comptées en bas de
  l'écran (« non lues ») et apparaîtront une fois terminées.

## Synthèse — pour l'administrateur

Menu **Synthèse** (administration). Toutes les matières, **agrégées** : nombre de sessions
closes, étudiants notés, médiane sur 20, taux de réussite, dernière session, barèmes
délibérés. Chaque matière ouvre ses tendances **en lecture seule**.

L'écran est agrégé **d'abord et seulement** : aucun tableau par étudiant, aucun nom, aucun
numéro d'inscription n'y apparaît — c'est la frontière voulue de l'analyse inter-matières
(ADR-0021 D5). Un taux inhabituel se lit ici ; corriger un barème reste l'acte du
responsable de la matière, dans son écran de délibération.

## Pourquoi l'écran refuse parfois de conclure

Comme les indices, la synthèse applique le contrat de refus : sous **10 étudiants notés**
pour une matière, la barre reste grise et porte la raison, par exemple
« non concluant — effectif insuffisant (n=3 < 10) ». Ce n'est pas une panne : c'est le
module qui refuse de sur-lire un bruit.

Autres lectures possibles, toujours dites en clair :

| Lecture | Sens |
|---|---|
| Aucune session close | la matière n'a pas encore d'examen terminé — les tendances n'existent qu'à l'usage |
| Session sans notation | l'examen est clos mais aucune notation n'a été verrouillée |
| Couverture du barème figé incomplète | une station n'a pas de barème figé : les totaux ne sont pas calculables, la session est listée sans taux |

## Quand le module est indisponible

Un bandeau ambre le dit (« module IA injoignable ») ; rien n'est affiché à la place. Les
écrans Résultats et Délibération de chaque examen, servis par le cœur, restent utilisables.
