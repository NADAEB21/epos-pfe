# Lire les résultats d'un examen après délibération

*Écran Résultats d'un examen terminé ou archivé. Concerne le responsable de matière ; le
super-administrateur lit la même chose en supervision.*

## Quel barème fait le résultat

Tant qu'aucun barème de délibération n'a été approuvé, le classement, la moyenne sur 20 et la
mention sont calculés sur les notes saisies par les évaluateurs, au barème d'origine.

Dès qu'un barème de délibération est **approuvé** (onglet Délibération : accepter une
proposition, ou composer et enregistrer une version), **c'est lui qui fait le résultat** :
le total, la moyenne sur 20, le rang, la mention, les cartes du haut de l'écran et l'export
CSV sont recalculés sous ce barème. L'écran le dit en clair : « Barème de délibération vN
appliqué — classement, moyenne et mention sous ce barème », et chaque carte nomme le barème
qu'elle lit.

## Ce qui ne bouge jamais : la trace

Les notes saisies ne sont **jamais réécrites**. Elles restent :

- dans les cellules du tableau, station par station — ce que l'évaluateur a réellement
  attribué. Quand le barème délibéré lit une station autrement (un critère retiré, une
  station repondérée), la cellule le dit à côté de la note saisie, par exemple « → 10 / 15 » ;
  une station retirée par le jury est barrée et marquée « exclue » ;
- dans la colonne « Origine /20 », la même moyenne au barème d'origine ;
- dans l'export CSV : colonnes « Total origine » et « Moyenne origine/20 », à côté du
  résultat, avec la colonne « Bareme » qui nomme la version appliquée.

Une réclamation porte sur une note saisie (le réajustement, tracé et motivé), jamais sur le
barème : les deux actes restent distincts.

## Deux règles de calcul à connaître

- Le rang, la moyenne et la mention n'existent que si **toutes** les stations de l'examen
  sont verrouillées pour l'étudiant. Cette règle ne change pas avec la délibération.
- La moyenne sur 20 d'un étudiant se calcule sur les stations **qu'il a passées** : une
  station exclue par le jury sort à la fois de son total et de son maximum.

## Quand le barème est enregistré mais pas appliqué

Si une station notée n'a pas de barème figé (cas d'un examen ancien), le service de notation
refuse de calculer des totaux délibérés partiels. L'écran affiche alors une pastille rouge
« Barème vN enregistré — lecture délibérée non servie » et garde le classement d'origine.
Ce n'est pas une panne : c'est le refus de servir un chiffre incomplet.

## L'onglet Analyse

Avant de délibérer, l'onglet **Analyse** d'un examen terminé lit les notes verrouillées en
français : pour chaque station, la cohérence de la grille, la concentration d'échec, puis
critère par critère la difficulté et la discrimination ; ensuite, l'écart de chaque évaluateur
à ses collègues sur la même station (jamais un classement, et un refus nommé quand une station
n'a qu'un seul évaluateur). Les lectures « à regarder de près » y sont comptées ; celles qui
appellent une décision deviennent des suggestions dans l'onglet Délibération.
