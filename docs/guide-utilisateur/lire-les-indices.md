# Lire les indices — guide du responsable de matière

*Écran de délibération, onglet Analyse. Concerne les examens **clos**
uniquement (ADR-0029 D2) — l'onglet reste inutilisable pendant que l'examen
tourne, la notation elle continue normalement.*

## Ce que cet écran fait, et ce qu'il ne fait pas

Les indices ci-dessous sont des **statistiques descriptives** calculées sur
les notes déjà verrouillées de l'examen. Ils ne changent, ne suppriment et
n'ajustent **jamais** une note tout seuls (ADR-0021 D3, D10). Ils sont un
**signal pour votre délibération** : à vous de décider, avec un motif écrit,
si un critère ou une station doit être revu (voir le guide « réajuster une
note » et l'écran de délibération).

Rien ici ne remplace votre jugement. Un chiffre qui dit « ce critère
discrimine mal » ne dit pas *pourquoi* — la réponse peut être un critère mal
formulé, une consigne ambiguë donnée en salle, ou simplement une cohorte
homogène cette année-là.

## Pourquoi certaines cases disent « non concluant »

Un indice calculé sur trop peu de copies est plus souvent faux que juste.
Plutôt que d'afficher un chiffre trompeur, le système **refuse de conclure**
et vous dit pourquoi :

> *« comparaison non concluante — effectif insuffisant (n=7 < 15) »*

C'est un choix délibéré (ADR-0021 D2) : un indice qui reconnaît ne pas
pouvoir conclure est plus fiable qu'un indice qui invente une réponse sur
trop peu de données. Le refus n'est **pas une erreur technique** — c'est le
système qui vous protège d'une fausse certitude.

## Les cinq indices

### Difficulté

*Quelle proportion des étudiants a réussi ce critère ?*

Un critère où presque tout le monde échoue, ou au contraire où tout le monde
réussit sans effort, n'apporte pas grand-chose pour distinguer les copies —
même s'il reste pédagogiquement pertinent à poser.

| Lecture affichée | Ce que ça veut dire |
|---|---|
| Très difficile | Presque personne n'a réussi |
| Difficile | Une minorité a réussi |
| Difficulté équilibrée | Environ la moitié a réussi — le cas le plus informatif |
| Facile | Une large majorité a réussi |
| Très facile | Presque tout le monde a réussi |

### Discrimination

*Ce critère sépare-t-il les bons étudiants des étudiants faibles ?*

Un bon critère est réussi surtout par les étudiants qui ont bien réussi
l'ensemble de l'épreuve. Un critère avec une discrimination proche de zéro ne
sépare personne — il pourrait être ambigu ou mal noté. Une discrimination
**négative** est un signal d'alerte plus sérieux : les étudiants faibles y
réussissent *mieux* que les bons, ce qui arrive typiquement quand un
corrigé est inversé ou qu'un critère est mal formulé.

### Cohérence interne (α de Cronbach)

*Les critères d'une même grille mesurent-ils bien la même chose ?*

Calculé sur l'ensemble d'une grille (station), pas sur un critère isolé. Une
cohérence faible suggère que certains critères de la grille n'ont pas de
rapport avec les autres ; une cohérence très élevée peut au contraire
signaler des critères redondants (qui se répètent).

### Concentration d'échec

*Cette station échoue-t-elle plus que les autres ?*

Compare le taux d'échec d'une station à celui des autres stations du même
examen, avec un test statistique — l'écart doit être plus grand que ce que le
hasard produirait pour être signalé comme réel. C'est l'indice qui **justifie
concrètement** une décision de barème (par exemple retirer un critère
défaillant, voir ADR-0021 Partie 2).

### Sévérité (comparaison intra-station)

*Un évaluateur note-t-il plus ou moins sévèrement que ses collègues sur la
même station ?*

⚠️ **Comparaison volontairement limitée aux évaluateurs d'une même station,
du même examen, sur la même cohorte** (ADR-0021 D2). Comparer des
évaluateurs de stations différentes n'aurait aucun sens : un évaluateur qui
note une station plus difficile paraîtrait « sévère » sans l'être.

Cet indice est **purement descriptif** — jamais un classement, jamais publié
à l'échelle de la faculté. Une station à un seul évaluateur ne peut, par
construction, rien comparer : l'écran vous le dit plutôt que d'afficher un
chiffre creux.

## L'intervalle de confiance

Quand vous voyez « intervalle de confiance 95 % : 0,18 à 0,61 », cela veut
dire : si l'examen avait légèrement différé (d'autres étudiants, un autre
jour), la valeur observée aurait pu se situer n'importe où dans cette
fourchette. Plus la fourchette est large, moins la valeur affichée doit être
lue au chiffre près — regardez la tendance, pas la deuxième décimale.

## En résumé

- **Aucune décision n'est prise automatiquement.** Vous voyez, vous décidez,
  vous motivez.
- **« Non concluant » est une réponse honnête**, pas un bug.
- **La sévérité n'est jamais un palmarès** — elle sert une conversation
  entre collègues, pas un classement.

---
*Document de référence : ADR-0008 (pivot psychométrie), ADR-0021 (ce que le
module calcule et propose), ADR-0029 (où et comment il tourne). Pour toute
question sur un chiffre précis, contactez l'équipe technique avec le numéro
d'examen concerné.*