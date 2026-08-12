# ADR-0024 — Le conducteur d'une épreuve est un fait observé, pas une permission déduite

- **Statut :** Partiellement retenu (2026-08-13) — voir le §0 ci-dessous
- **Contexte technique :** `auth-service`, `exam-service`, `scoring-service`
- **Débloque :** #306 (révocation immédiate), #289 partie B, #288

## §0 — CE QUI EST RETENU ET CE QUI NE L'EST PAS (décision de Nada, 2026-08-13)

⛔ **LA GARDE AU RETRAIT N'EST PAS RETENUE** — D4.2, D4.3, D4.5 et D4.6 ci-dessous sont
**abandonnés**. Décision de Nada, redite le 2026-08-13 (déjà tranchée dans la session du
11–12/08, restée non écrite — d'où ce marqueur) :

> « I can't think of one reason an admin HAS to deactivate a teacher's account mid exam. »

Le scénario que la garde protégeait n'existe pas dans la réalité administrative de la
faculté ; la garde, elle, avait un coût bien réel : les **premiers appels sortants
d'`auth-service`** (deux dépendances nouvelles), et la règle « strict en écriture » aurait
fait dépendre **tout retrait ordinaire** de la disponibilité d'`exam-service`. On ne paie
pas une architecture pour un non-scénario. Le cas accidentel résiduel est **réversible** :
la réactivation existe (#289) et redonne l'accès en un clic.

**Conséquences :**
- ✅ **D4.1 (l'attribution) RESTE** — livrée par #320/#321 (`examen.lance_par`,
  `lot.ouvert_par`, le Suivi nomme le conducteur). Elle vaut par elle-même : audit du
  lancement, réponse à « qui tient cette épreuve ? ».
- ⛔ `auth-service` ne gagne **aucun appel sortant** — la « question ouverte » (à qui auth
  pose la question) et la « question de direction » (`auth → exam` vs #303) **tombent** :
  aucun des deux liens ne se construit.
- ⛔ **ADR-0023 D3 et la colonne « guard » de son D1 tombent avec** — ADR-0023 porte son
  propre marqueur.
- **#306 se traite DIRECTEMENT** (mécanisme de révocation immédiate, option D + filet A de
  l'issue), sans prérequis : un retrait erroné en pleine épreuve — le non-scénario — serait
  de toute façon visible immédiatement et annulable par réactivation dans la fenêtre de
  rafraîchissement de la liste de révocation.
- Le chemin « incident » (D4.5) n'a plus d'objet **en tant que dérogation à une garde** :
  quand #306 rendra le retrait effectif immédiatement, l'acte ordinaire suffit. La
  distinction des trois sens du retrait (#288) reste un sujet, mais d'attribution et de
  message, pas de garde.

---

## Le problème

Retirer l'accès à quelqu'un **pendant** une épreuve peut arrêter l'épreuve. Aucune garde
n'existe aujourd'hui : `deactivateUser` ne vérifie que deux choses — pas soi-même, pas le
dernier administrateur (`UserService:268-278`).

Ça ne se voit pas encore, parce que le retrait met jusqu'à 24 h à prendre effet (#306). Rendre
le retrait immédiat SANS cette garde transformerait une erreur administrative en incident
d'examen.

## Deux règles ont été proposées puis écartées

**« Bloquer si la personne est responsable d'une matière ayant un examen EN_COURS. »**
Trop large : depuis #283 une matière a plusieurs co-responsables, dont la plupart ne font rien
ce jour-là.

**« Bloquer si elle est le DERNIER responsable de cette matière. »**
Écartée par Nada, et elle a raison :

> Matière A, responsables B et C. **B est sur place**, a lancé l'épreuve et fait passer les
> lots. C est chez lui. La règle voit « il reste C », donc autorise le retrait de B — et
> l'épreuve s'arrête.

La règle testait une **capacité** (« quelqu'un d'autre pourrait agir ») là où la question porte
sur une **présence** (« quelqu'un d'autre EST en train d'agir »).

## La cause racine

Le système enregistre **quand** les actes ont lieu, pas **qui** les fait :

| fait | horodaté | attribué |
|---|:--:|:--:|
| lancement de l'épreuve (`examen.launched_at`) | ✅ | ❌ |
| ouverture d'une vague (`lot.ouvert_a`, #208/V9) | ✅ | ❌ |
| saisie d'une note (`notation.saisi_par`, #213) | ✅ | ✅ |

Toute règle qui n'a pas ce « qui » ne peut que **deviner**. C'est pour ça que les deux
tentatives ont échoué : elles inféraient une présence à partir d'un rôle.

## Décision

### D4.1 — Le conducteur est l'AUTEUR DU DERNIER ACTE DE CONDUITE

Rien n'est déclaré. On enregistre l'auteur des actes qui existent déjà :

| acte | où il vit | ce qu'on ajoute |
|---|---|---|
| lancer l'épreuve (`changerStatut` → EN_COURS) | exam-service | `examen.lance_par` |
| ouvrir une vague / présence-et-démarrer | scoring-service | `lot.ouvert_par` |

Le conducteur d'un examen `EN_COURS` = **l'auteur de l'ouverture de vague la plus récente**ic ;
à défaut (aucune vague encore ouverte), l'auteur du lancement.

Chaque écriture est **locale au service qui exécute l'acte** — aucune écriture croisée, aucune
synchronisation. Ce sont des **faits d'évènement**, du même genre que `launched_at`
(ADR-0010), `saisi_par` (#213), `deactivated_by` (#289) et `retired_by` (#134). Ce n'est pas
un instantané d'état : ça ne peut donc pas « périmer » comme un cache.

C'est le vocabulaire que Nada a employé spontanément : *« celui qui est présent sur place a
lancé l'épreuve et a pris la responsabilité de faire passer les lots »*. Ce rôle existe dans la
réalité de la salle ; il n'existait pas dans le modèle.

### D4.2 — La garde porte sur des actes observés, jamais sur des rôles

Le retrait d'accès est **refusé** si la personne est, à cet instant :

1. **le conducteur** d'un examen `EN_COURS` (`examen.conduite_par`) ; **ou**
2. **un évaluateur affecté à une station** d'un examen `EN_COURS`.

Le point 2 est déjà calculable sans rien ajouter : `calculerConflitsEvaluateurs`
(`ExamenServiceImpl:296-321`) parcourt déjà tous les examens `EN_COURS` et collecte les
`evaluateurIds` de leurs stations. C'est la mécanique de #265, réutilisée telle quelle.

Un responsable qui n'est ni conducteur ni évaluateur peut être retiré : il ne fait rien.

### D4.3 — L'administrateur n'orchestre PAS la salle d'examen

⚠️ **Correction apportée par Nada, et elle touche le fond du dessin.**

Une première version disait à l'administrateur : *« confiez la conduite à quelqu'un d'autre,
puis retirez son accès »*. C'est faux. Cela confond deux évènements sans rapport :

| évènement | qui | pourquoi |
|---|---|---|
| **le responsable s'en va / passe la main** | la salle d'examen | il est malade, il rentre, un collègue prend le relais |
| **l'administrateur retire un accès** | l'administration | départ de la faculté, doublon, incident |

L'administrateur est dans un bureau. Il ne peut pas passer la conduite d'une épreuve, et **ce
n'est pas sa décision**. Lui demander de l'organiser, c'est lui confier la salle.

Le refus est donc **purement informatif**, et n'énonce que ce que l'administrateur peut
réellement faire :

> « Mme B conduit l'épreuve « Chimie thérapeutique — session 2 », commencée à 09:12.
> Son accès ne peut pas être retiré tant qu'elle la conduit.
> — Réessayer après l'épreuve
> — Incident : révoquer immédiatement (motif obligatoire) »

Deux leviers, tous deux à sa portée : **attendre**, ou **déclarer un incident**. Rien d'autre.

### D4.4 — Il n'y a AUCUNE cérémonie de passation

Conséquence directe de D4.3 : **rien à construire pour « passer la main »**.

N'importe quel responsable de la matière peut déjà ouvrir un lot — la garde est portée par la
matière, pas par une personne. Donc si C prend le relais, C ouvre simplement le lot suivant.

**L'acte de conduire EST l'enregistrement.** Le conducteur n'est pas déclaré, il est constaté :
c'est l'auteur du dernier acte de conduite (ouverture de vague, présence-et-démarrer ; à
défaut, le lancement).

Le cas de Nada se résout donc tout seul :

- B lance l'épreuve → B est conducteur.
- B rentre chez lui, C ouvre la vague suivante → **C devient conducteur, par ce seul geste**.
- L'administrateur peut alors retirer B : B ne conduit plus rien.

Aucun écran, aucun bouton, aucune formalité. Même doctrine que #209 / `rotation.debut_reel` :
*le minuteur démarre quand l'évaluateur ouvre réellement le groupe, jamais au créneau prévu.*
**Le projet a déjà tranché, ailleurs, en faveur de l'observé contre le déclaré.**

### D4.5 — L'incident garde sa porte, avec son alarme

#288 distingue trois sens du retrait : départ, ménage, **incident**. Seul l'incident doit
couper immédiatement, même en pleine épreuve.

Le chemin « **incident — révoquer immédiatement** » outrepasse D4.2, avec motif obligatoire et
trace nominative. Sans lui, la garde bloquerait le seul cas qui ne doit jamais l'être.

Effet de bord utile : une panne d'`exam-service` ne peut donc jamais empêcher une urgence.

### D4.6 — Le contrôle vit sur le SERVEUR, l'avertissement dans l'écran

- `auth-service` interroge `exam-service` **au moment du retrait** — une question, sur une
  action humaine, jamais par requête.
- L'écran d'administration affiche l'information **avant** le clic.
- Si `exam-service` ne répond pas : le retrait ordinaire est **refusé** (ADR-0015, strict en
  écriture). Refuser est réversible ; retirer quelqu'un en pleine épreuve ne l'est pas. Le
  chemin « incident » reste ouvert.

**C'est le premier appel sortant d'`auth-service`** (vérifié : aucun `RestTemplate`,
`WebClient` ni `Feign` dans tout le service). C'est le vrai coût de cette décision, et il est
assumé ici plutôt que contourné.

## Conséquences

**Ce qu'on gagne**

- La règle répond à la bonne question — *qui agit*, pas *qui aurait le droit d'agir*.
- Le scénario de Nada (B sur place, C chez lui) est traité correctement, sans heuristique.
- `conduite_par` répond aussi à une question que le Suivi ne sait pas afficher aujourd'hui :
  **qui tient cette épreuve ?**
- La trace d'audit gagne le seul acte majeur encore anonyme : le lancement.

**Ce qu'on paie**

- Deux colonnes d'attribution : `examen.lance_par` (exam-service) et `lot.ouvert_par`
  (scoring-service). Deux migrations, chacune locale à son service.
- **Aucun écran de passation** — D4.4 : il n'y en a pas besoin.
- Les appels sortants d'`auth-service` (voir la question ouverte ci-dessous).

**⚠️ Question restée ouverte : à qui `auth` pose-t-il la question ?**

Les deux moitiés de la réponse vivent dans deux services :

- « est-elle évaluatrice d'une station en cours ? » → **exam-service** (déjà calculé, #265) ;
- « est-elle la dernière à avoir ouvert une vague ? » → **scoring-service**.

Trois options, à trancher avant l'implémentation :

1. **`auth` interroge les deux** — simple, explicite, mais deux dépendances sortantes.
2. **`auth` interroge `scoring`, qui répond pour tout** — scoring consulte déjà exam-service
   (il l'interroge pour l'état d'un examen, cf. #287), donc le lien existe et n'est pas
   nouveau. Une seule dépendance pour auth.
3. **Fusionner l'information dans `exam-service`** — imposerait à scoring d'écrire chez exam :
   ❌ écarté, c'est exactement l'écriture croisée qu'on refuse.

Préférence : **option 2**, parce qu'elle n'ajoute qu'UN lien sortant à `auth` et réutilise un
chemin `scoring → exam` déjà existant. À confirmer en vérifiant ce chemin dans le code avant
de s'engager.

**Ce qu'on ne fait PAS**

- ❌ Recopier « qui examine » dans `auth_db` — un cache périme exactement quand la question
  compte (épreuve qui vient de démarrer). Écarté par Nada, à raison.
- ❌ Se contenter d'un avertissement dans l'écran : une garde côté client n'est pas une garde.
- ❌ Déduire la présence d'un rôle : c'est l'erreur que ce document corrige.

## Question de direction, à trancher avec celle-ci

Cette décision autorise **`auth → exam`**. Or **#303** demande l'inverse : `exam → auth`
(« cette matière est-elle encore ouverte ? »). Autoriser les deux crée un cycle entre services.

Proposition : n'autoriser QUE `auth → exam` (sens de cette ADR), et traiter #303 autrement —
par exemple en refusant la fermeture d'une matière qui porte un examen non terminé, contrôle
qui peut vivre entièrement dans `exam-service` au moment du lancement.

## Alternatives écartées

| alternative | pourquoi non |
|---|---|
| « dernier responsable de la matière » | teste une capacité, pas une présence — cassée par le cas B/C |
| instantané « qui examine » dans `auth_db` | périmé précisément quand ça compte |
| garde uniquement dans l'IHM | contournable par un appel direct |
| bloquer tout retrait pendant un examen | punit des gens qui ne font rien ce jour-là |
| ne rien faire | un retrait immédiat arrêterait une épreuve en cours |

## Liens

ADR-0023 (le retrait est un départ, pas une éjection) · ADR-0018 D3 (continuité
institutionnelle) · ADR-0017 (remplacement d'évaluateur) · ADR-0015 (strict en écriture) ·
ADR-0014-B · #306 · #288 · #289 · #296 · #265 · #213
