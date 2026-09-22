# API et protocoles

Ce document décrit la **surface d'échange** d'EPOS : les routes REST exposées par
la passerelle, et l'unique canal temps réel (WebSocket/STOMP). Il complète les ADR
en donnant le contrat effectif, tel qu'il est implémenté.

Référence : ADR-0003 (contrat d'API), ADR-0004 (enveloppe de réponse),
ADR-0005 (autorités portées par le jeton).

---

## 1. Principe : une seule porte

`api-gateway` est **le seul service exposé à l'hôte** (`:8080`). Les services
applicatifs n'ont aucun port publié. La passerelle valide le JWT avant tout
routage et propage l'identité en en-têtes (`X-User-Id`, `X-User-Authorities`).

Toutes les routes publiques sont préfixées `/api/v1/`.

## 2. Surface REST par service

| Service | Préfixes routés | Réécriture |
|---|---|---|
| `auth-service` | `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/matieres/**` | — |
| `exam-service` | `/api/v1/examens/**`, `/api/v1/stations/**`, `/api/v1/grilles/**`, `/api/v1/items/**`, `/api/v1/templates/**` | `/api/v1/…` → `/api/…` |
| `scoring-service` | `/api/v1/etudiants/**`, `/api/v1/participations/**`, `/api/v1/lots/**`, `/api/v1/notations/**`, `/api/v1/notation-items/**`, `/api/v1/assignments/**`, `/api/v1/rotations/**`, `/api/v1/student-groups/**`, `/api/v1/evaluateur/**`, `/api/v1/reclamations/**`, `/api/v1/convocations/**` | `/api/v1/…` → `/api/…` |
| `ai-service` | `/api/v1/ai/**` | `/api/v1/…` → `/…` |

`auth-service`, `exam-service` et `scoring-service` sont résolus par Eureka
(`lb://`). `ai-service` est joint par **route statique** : un service Python à
instance unique et nom DNS stable ne gagne rien à s'enregistrer dans l'annuaire.

Toutes les réponses suivent l'enveloppe commune `ApiResponse` (`success`, `data`,
`message`) — ADR-0004.

## 3. Le canal temps réel (WebSocket / STOMP)

Un seul service publie du temps réel : **`scoring-service`**. Le choix d'un canal
poussé côté mobile et d'un rafraîchissement périodique côté web est un contrat
**par acteur** (ADR-0002) : l'évaluateur note en salle et doit voir l'état de sa
station immédiatement ; le responsable suit une progression qui avance à la
vitesse d'actes humains, pas d'une horloge.

### Établissement

| | |
|---|---|
| Point d'entrée | `/ws` (SockJS accepté) |
| Préfixe courtier | `/topic` (broker simple, en mémoire) |
| Préfixe applicatif | `/app` (réservé — aucune action client → serveur à ce jour) |

### Authentification et révocation

L'authentification se fait à la **trame STOMP `CONNECT`** : le jeton est présenté
dans les en-têtes de connexion et validé par `StompConnectAuthenticator`. Une
session acceptée est inscrite dans `WebSocketSessionRegistry`.

Un jeton révoqué ne doit pas survivre dans une session déjà ouverte : un balayage
(`WebSocketRevocationSweep`) ferme les sessions dont le jeton a été révoqué. Une
déconnexion n'est donc pas seulement un événement client.

### Destinations publiées

| Destination | Charge utile | Émise quand |
|---|---|---|
| `/topic/stations/{stationId}/scores` | `ScoreUpdateMessage` | une notation de la station évolue |
| `/topic/lots/{lotId}/status` | `LotStatusMessage` | le statut du lot change (ouverture, avancement) |

Les destinations sont **paramétrées par identifiant** : un client ne s'abonne
qu'aux stations et aux lots qui le concernent. Le périmètre reste vérifié côté
serveur — l'abonnement ne fait pas autorité.

## 4. Pourquoi pas de spécification OpenAPI générée

C'est une décision écrite, pas un oubli : **ADR-0003** proposait une source unique
OpenAPI avec génération des clients Dart et TypeScript. Elle est marquée
**`LAPSED — never adopted`**, et son §0 consigne ce qui s'est réellement passé,
le coût payé (dérive `ouvertA` → `ouverta`, une session de débogage, PR #258) et
la règle : ne pas la reprendre sans écrire un ADR successeur qui cite ces
incidents comme données d'entrée.

À l'échelle du projet — deux clients, un seul développeur back-end — le coût
d'installation du générateur n'a jamais trouvé sa fenêtre. Le présent document
est le contrat tenu à la main que cette décision implique.
