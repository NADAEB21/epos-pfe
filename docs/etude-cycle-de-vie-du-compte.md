# Étude transverse — ce que « désactiver un compte » déclenche réellement

- **Date :** 2026-08-02
- **Auteur :** Claude Code, à la demande de Nada.
- **Statut :** étude d'architecture (constat), pas une décision. Les décisions sont dans **ADR-0023** ;
  les correctifs dans #287 / #288 / #289 / #294 / #295.
- **Méthode :** chaque ligne marquée **[mesuré]** a été reproduite en direct sur la pile qui tourne
  (gateway `:8080`, base réelle) ; chaque ligne marquée **[lu]** est citée en `fichier:ligne`.
  Rien ici ne vient de la mémoire d'une session précédente.

---

## 0. Résumé en une page

Désactiver un compte est **le seul acte du produit qui traverse les cinq couches à la fois** :
identité, passerelle, définition d'examen, notation, et les deux clients. Or il n'a jamais été conçu
comme tel — c'est un `UPDATE` d'une colonne booléenne, et tout le reste du système en déduit ce
qu'il peut.

**Ce que l'acte fait vraiment :** il empêche une **future connexion**. Rien d'autre. Il n'interrompt
pas une session, ne libère pas une station, ne prévient personne, et ne peut pas être annulé.

**Les cinq défauts structurels que l'étude établit :**

| # | défaut | conséquence la plus concrète |
|:-:|---|---|
| 1 | **un bouton, trois intentions** (départ / ménage / incident) | le remède réflexe (couper les sessions) casserait l'examen en cours |
| 2 | **le drapeau est surchargé** : `is_active=false` = « retiré » **et** « verrouillé 3 essais » | aucun écran ne peut donner la bonne consigne ; les remèdes sont opposés |
| 3 | **aucun retour en arrière** : ni réactivation, ni déverrouillage, ni même par mot de passe oublié | une faute de frappe à 8 h exclut un examinateur pour la journée |
| 4 | **aucune couche en aval n'est informée** : ni la passerelle, ni exam, ni scoring, ni les écrans | l'examen se lance avec une station dont l'examinateur ne peut pas se connecter |
| 5 | **aucune garde sur l'acte lui-même** : ni sur soi, ni sur le dernier admin, ni sur une personne qui examine à cet instant | l'administration peut casser une épreuve sans le savoir |

**La phrase à retenir :** *aujourd'hui, « désactiver » est une promesse pour demain, pas un acte sur
aujourd'hui.* Tant que ce n'est pas dit explicitement dans le produit, chacun lui prête le pouvoir
qu'il imagine.

---

## 1. Le fait générateur

Un seul chemin d'écriture existe : `DELETE /api/v1/users/{id}` → `UserService.deactivateUser`
(`UserService.java:234-245`). **[lu]**

Il fait exactement trois choses :

1. `user.setIsActive(false)` ;
2. `refreshTokenRepository.revokeAllByUserId(userId)` — tous les jetons de rafraîchissement révoqués ;
3. une entrée d'audit `USER_DEACTIVATED`, **portant l'identité de la cible, pas celle de l'acteur**
   (`ipAddress` codé en dur à `null`).

Un **second** chemin écrit la même colonne sans jamais passer par là :
`UserRepository.lockAccount` (`UserRepository.java:38-39`), appelé après 3 échecs de connexion.
**C'est le défaut nº2 : deux causes, un seul drapeau, aucune trace de laquelle.** **[lu]**

---

## 2. Propagation, couche par couche

| couche | sait-elle que le compte est mort ? | ce qui se passe | délai |
|---|:--:|---|---|
| **Base `auth_db`** | ✅ source de vérité | `is_active=f`, `failed_login_attempts` inchangé | immédiat |
| **auth-service — login** | ✅ | refuse : `AccountLockedException` → **403** | immédiat **[mesuré]** |
| **auth-service — refresh** | ✅ | jetons révoqués → **401** « Token reuse detected » | au prochain refresh **[mesuré]** |
| **auth-service — requêtes portant un jeton d'accès valide** | ❌ | **acceptées** : `JwtAuthenticationFilter:45-77` reconstruit l'utilisateur depuis le jeton signé, **sans lecture en base** | jusqu'à **24 h** **[mesuré : `GET /auth/me` → 200 après désactivation]** |
| **api-gateway** | ❌ | valide la **signature** uniquement ; aucune notion de compte actif (`grep isActive\|revok\|blacklist` = vide) | — **[lu]** |
| **exam-service** | ❌ *et ne peut pas le savoir* | `station_evaluateurs` garde l'id ; **aucun client HTTP dans tout le service** → il ne peut pas interroger auth | jamais **[lu + mesuré : `evaluateurIds:[18]` après désactivation]** |
| **scoring-service** | ❌ | `Rotation.evaluateurId` est une clé étrangère **logique** (ADR-0006) ; `EvaluateurScopeChecker` compare des identifiants, ne valide aucune existence | jamais **[lu]** |
| **web (Angular)** | ⚠️ partiellement | le sélecteur d'affectation filtre déjà `isActive` (`stations-grilles.component.ts:292`) ; **une affectation antérieure restait muette** → corrigé par #287 (puce + ligne de pré-vol) | au chargement de l'écran |
| **mobile (Flutter)** | ❌ | l'intercepteur ne réagit qu'au **401** (`api_client.dart:111`) ; or les requêtes renvoient **200** tant que le jeton vit | jusqu'à 24 h **[lu]** |
| **WebSocket** | ❌ | le jeton est présenté **à la connexion** (`websocket_service.dart:143`) ; une connexion établie n'est jamais revalidée | jusqu'à déconnexion **[lu]** |

**Lecture de ce tableau :** la désactivation est connue de **une** couche sur neuf. Les huit autres
continuent comme si de rien n'était — non par négligence, mais parce que **rien ne les prévient** et
que, pour exam-service, rien ne le *peut* (voir §7).

---

## 3. Chronologie d'un compte désactivé

```
T+0      l'administrateur clique « Désactiver »
         └─ is_active=false · refresh tokens révoqués · audit écrit (sans l'acteur)
         └─ la personne, si elle est connectée, ne remarque RIEN

T+0 → 24 h   FENÊTRE D'ACTIVITÉ RÉSIDUELLE
         └─ web + mobile : toutes les requêtes passent (200)
         └─ un évaluateur continue de NOTER ; ses notes sont acceptées et comptées
         └─ le WebSocket reste ouvert
         └─ scoring n'a aucun moyen de savoir que l'auteur est un compte retiré

T+24 h   le jeton d'accès expire
         └─ 401 → l'appli tente un refresh → REFUSÉ (révoqué)
         └─ mobile : `_storage.deleteAll()` — les JETONS sont effacés
            ⚠️ la file hors-ligne (SQLite, `offline_storage_service.dart`) SURVIT
               mais ne pourra JAMAIS se synchroniser : plus de session possible
         └─ la personne voit une erreur, sans explication utile

J+1…     l'affectation à la station est TOUJOURS là
         └─ le pré-vol de lancement le signale désormais (#287) — non bloquant
         └─ l'examen peut être lancé : la station a « un évaluateur », qui ne viendra pas

jamais   retour en arrière
         └─ aucun endpoint de réactivation
         └─ la réinitialisation de mot de passe ne débloque PAS (§5)
         └─ seule sortie : UPDATE SQL
```

---

## 4. Les scénarios réels, par acteur

### 4.1 L'évaluateur qui note à cet instant — *le cas qui interdit le remède réflexe*

Il est à sa station, quatre candidats devant lui, une grille à moitié remplie. Son compte est
désactivé (départ programmé, erreur de ligne, homonyme).

- **Aujourd'hui :** il ne remarque rien et **finit son épreuve**. Ses notes sont acceptées.
- **Si l'on « corrigeait » la fenêtre de 24 h** (jeton court ou liste de révocation), il serait
  **déconnecté en pleine station**, sur un Wi-Fi de faculté qui lâche déjà.

C'est la raison pour laquelle **ADR-0023** refuse le raccourcissement global des jetons : ce serait
troquer une gêne administrative contre une épreuve cassée. La fenêtre résiduelle est un défaut
**pour l'incident de sécurité**, et une protection **pour la conduite d'épreuve**. D'où deux actes
distincts, pas un réglage unique.

**Question ouverte, honnête :** ses notes doivent-elles rester valides ? Le produit dit oui par
défaut (silence). Un jury pourrait demander à qui appartient une note saisie par un compte retiré.
Aucun ADR ne tranche — c'est un angle mort à ajouter à la liste des six.

### 4.2 L'évaluateur désactivé entre deux examens — *le cas fréquent*

Praticien externe invité en mars, offboardé en avril, encore affecté à l'examen de juin.
Toute la préparation était verte jusqu'à #287 ; désormais la puce et le pré-vol le disent.
**Le bon remède est la substitution (ADR-0017), pas une action sur le compte.**

### 4.3 L'enseignante verrouillée par 3 mots de passe — *le pire, parce qu'il est banal*

Même drapeau, cause opposée, remède opposé : il faut la **débloquer**, pas la remplacer.
Le système ne sait pas distinguer, donc aucun écran ne peut conseiller juste — c'est pourquoi le
message de #287 nomme désormais les deux causes au lieu d'en affirmer une.
Et il n'existe **aucun** moyen de la débloquer (§5).

### 4.4 Le responsable désactivé en pleine préparation

Rien ne l'empêche. Il garde 24 h d'écriture, puis son espace de travail devient inaccessible.
Ses examens restent, sans propriétaire actif ; **si c'est l'unique responsable de la matière**,
plus personne ne peut la conduire — la co-responsabilité (livrée aujourd'hui) est le filet, mais
rien n'impose qu'il existe.

### 4.5 Le super-administrateur, et le dernier de son espèce

Un `SUPER_ADMIN` peut **se désactiver lui-même** — **[mesuré : self-`DELETE` → 200]** — et rien ne
vérifie qu'il en reste un autre. Combiné à l'absence de réactivation : **la plateforme devient
ingérable, récupération en base uniquement.** C'est #289 partie A, la seule entièrement réparable
sans décision d'architecture.

---

## 5. Le chemin de récupération : mesuré, et il n'existe pas

Reproduction intégrale **[mesuré]**, compte scrap :

| étape | résultat |
|---|---|
| 3 `POST /auth/login` **non authentifiés**, mauvais mot de passe | verrouillé |
| le propriétaire, avec le **bon** mot de passe | **403 « Account is locked »** |
| `POST /auth/password-reset/request` | 200 — mais `StubEmailService` journalise « *mail.enabled=false; token not logged* » ⇒ **le jeton n'atteint personne** |
| jeton forcé en base, `POST /auth/password-reset/confirm` | **200 « Password updated successfully »** |
| connexion avec le **nouveau** mot de passe | **403 « Account is locked »** |
| état en base après cette réinitialisation « réussie » | `is_active=f`, `failed_login_attempts=3` — **inchangés** |

Preuve en code : `AuthService.confirmPasswordReset:253-257` écrit `passwordHash`, sauvegarde, marque
le jeton utilisé. **Il ne touche ni `isActive` ni le compteur.** **[lu]**

Autrement dit : *le seul parcours de récupération que connaissent les utilisateurs se termine par un
message de succès et une porte toujours fermée.* C'est **#294**.

---

## 6. Intégrité des données

| objet | devient-il incohérent ? | détail |
|---|:--:|---|
| `station_evaluateurs` | ⚠️ **oui, silencieusement** | l'id reste ; la station affiche « 1 évaluateur » alors qu'elle n'en a plus de joignable |
| `Rotation.evaluateurId` | ⚠️ figé au moment de la génération | une vague déjà générée pointe sur un compte mort ; cousin de #242 (ids fantômes) |
| `Notation` / `saisi_par` | ✅ conservées | les notes déjà saisies restent valides et attribuées — c'est souhaitable |
| file hors-ligne mobile (SQLite) | ⚠️ **orpheline** | elle survit à l'effacement des jetons mais ne peut plus jamais se synchroniser : les saisies restent prisonnières de l'appareil |
| journal d'audit | ⚠️ incomplet | `USER_DEACTIVATED` nomme **la cible**, pas l'**acteur** (#64) — impossible de répondre « qui a fait ça ? » |
| sessions ouvertes | ⚠️ 24 h de sursis | voir §2 |

**Le point le plus discret et le plus grave : la file hors-ligne.** Un évaluateur qui a noté sans
réseau, puis dont le compte meurt, garde des notes sur son téléphone que **rien ne pourra plus
remonter**. Aucun écran ne le signale — ni à lui, ni au responsable.

---

## 7. Pourquoi ce n'est pas « juste à corriger » — la contrainte d'architecture

La garde qui empêcherait la situation (« refuser de désactiver quelqu'un qui examine ») a besoin
d'un fait qu'**auth-service ne peut pas obtenir** :

- **auth-service n'appelle personne** (`grep RestTemplate|WebClient|FeignClient` sur tout le service
  = vide). **[lu]** C'est la couche du bas : tout le monde en dépend, elle ne dépend de personne.
  L'inverser coupleraient l'identité à la disponibilité du plan examen.
- **exam-service, symétriquement, n'a aucun client HTTP non plus** — il ne peut donc pas valider un
  évaluateur auprès d'auth au moment de l'affectation. **[lu]**
- Seul **scoring-service** possède un client sortant (`ExamServiceClient`), vers exam.

Conclusion : **aucune garde autoritaire côté serveur n'est possible aujourd'hui**, dans un sens
comme dans l'autre, sans un choix d'architecture. C'est exactement **ADR-0023 D4**, laissé ouvert :
pré-vol côté écran · orchestration au-dessus d'auth · drapeau événementiel (ADR-0020).
La **direction d'échec** est en revanche déjà tranchée : si l'engagement ne peut pas être déterminé,
**on refuse la désactivation**, on ne l'admet pas.

---

## 8. Analyse de sécurité

**Ce qui n'est pas une vulnérabilité** (et ne doit pas être filé comme telle) :

- la fenêtre de 24 h en cas de **départ** : personne n'attend, et la couper casserait l'épreuve ;
- l'accès résiduel n'est **pas** une élévation de privilège : la personne garde ce qu'elle avait.

**Ce qui en est une :** l'atteinte à la **disponibilité d'une personne**.

> Trois requêtes non authentifiées contre une adresse e-mail connue suffisent à exclure
> définitivement son propriétaire. Coût nul, aucun compte requis, aucune limite de débit
> franchie (3 requêtes passent sous tout seuil Bucket4j), aucune fenêtre d'expiration, aucune
> récupération. Le jour J, sur le LAN de la faculté, cela retire un examinateur de l'épreuve.

Sévérité **HAUTE** (#294) : fichiers ouverts, lignes citées, rayon d'action concret. Pas critique :
ni fuite, ni élévation, et il faut connaître une adresse valide — or elles suivent un format
institutionnel prévisible, ce qui n'est pas une consolation.

**Le remède standard existe et résout trois problèmes d'un coup :** un verrou **temporaire** à
backoff exponentiel, plus la **séparation des deux états** (`locked_until` vs `deactivated_at`).
Il neutralise l'attaque, rend #287 capable de conseiller juste, et donne à #289 une réactivation qui
sait ce qu'elle répare.

---

## 9. Matrice de l'état actuel

| garde attendue | existe ? | où |
|---|:--:|---|
| refuser la connexion d'un compte inactif | ✅ | `AuthService.login` |
| révoquer les jetons de rafraîchissement | ✅ | `deactivateUser` |
| couper une session en cours | ❌ | par conception aujourd'hui (§4.1) — doit devenir l'acte « révoquer immédiatement » |
| empêcher la désactivation d'une personne engagée | ❌ | bloqué sur ADR-0023 D4 |
| empêcher l'auto-désactivation | ❌ | #289 A |
| empêcher la perte du dernier admin | ❌ | #289 A |
| réactiver / déverrouiller | ❌ | #289 A + #294 |
| distinguer verrouillage et retrait | ❌ | #294 |
| ne pas proposer un compte inactif à l'affectation | ✅ | `stations-grilles.component.ts:292` |
| signaler une affectation devenue inactive | ✅ *(nouveau)* | #287 — puce + pré-vol, non bloquant |
| signaler au jour J (écran Suivi) | ❌ | #295 |
| dire à la personne ce qu'elle doit faire | ❌ | #295 |
| prévenir l'administrateur qu'il casse une épreuve | ❌ | ADR-0023 D3 |
| tracer QUI a désactivé | ❌ | #64 / ADR-0018 D3 |
| valider l'existence d'un évaluateur à l'affectation | ❌ | impossible aujourd'hui (§7) ; parent de #242 |

---

## 10. Ce que je recommande, dans cet ordre

1. **#294 — le verrou** (avant le déploiement facultaire). Verrou temporaire + séparation des deux
   états. Local à auth-service, aucune décision d'architecture requise, et il **débloque** #287
   (message juste), #289 (réactivation qui distingue) et #295 (consigne utile).
2. **#289 A — les gardes de l'administrateur sur lui-même** (soi, dernier admin) + l'endpoint de
   réactivation. Petit, local, immédiat.
3. **ADR-0023 D4 — ta décision** sur le placement de la garde d'engagement. Elle débloque #289 B et
   l'acte « révoquer immédiatement » de #288.
4. **#295 — les trois destinataires** de l'alerte, une fois que le message peut être juste (donc
   après 1).
5. **Angles morts à arbitrer** : la validité d'une note saisie par un compte retiré (§4.1), et la
   file hors-ligne orpheline (§6). Aucun ADR ne les couvre ; ils rejoignent la liste des six.

---

## Annexe — comment ces faits ont été établis

Tout ce qui est marqué **[mesuré]** provient de reproductions live du 2026-08-02 sur la pile locale :
création de comptes scrap, désactivation, tentatives de connexion, réinitialisation avec jeton forcé
en base, lancement d'examen avec un examinateur mort, coupure réseau simulée de `GET /users`.
Les fixtures ont été nettoyées ensuite (comptes scrap désactivés, examens scrap supprimés ou
TERMINE, zéro examen EN_COURS).

Les scénarios rejouables vivent dans `C:/Users/Nada/pwverify/` :
`s34-eval-inactif-287.js` (14 assertions), `s34-287-degraded.js` (panne annuaire).
