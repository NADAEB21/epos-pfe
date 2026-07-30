# EPOS — Acteurs, cas d'utilisation et besoins

> **Statut :** référentiel vivant. Dernière vérification intégrale : **2026-07-30**
> (`develop` = `ea6bf8a`).
>
> **Destination :** ce document alimente le chapitre *Étude préliminaire* du rapport de PFE
> (§ *Analyse des besoins*) ainsi que la section *Spécification* de chaque chapitre de sprint.
> Il est rédigé en français et structuré pour être transposé en LaTeX.

---

## 1. Portée et méthode d'établissement

### 1.1 Deux sources, et une seule d'entre elles est du code

Le catalogue est établi à partir de **deux sources vérifiées**, et l'état de chaque cas
d'utilisation dit laquelle le porte :

1. **Le code** — les 16 contrôleurs des quatre services (relevé exhaustif des `@RequestMapping`
   et `@PreAuthorize`), les 19 composants de l'application web et ses trois services d'API, les
   6 écrans de l'application mobile.
2. **Les décisions d'architecture (ADR-0001 à ADR-0021)** — qui portent des cas d'utilisation
   **arbitrés mais non encore réalisés**. Le code étant incomplet, les ignorer donnerait un
   périmètre fonctionnel faussement étroit.

### 1.2 Le piège méthodologique, et comment il est évité

⚠️ Dériver la liste des besoins de l'inventaire des points d'entrée produit un **catalogue d'API
déguisé en cahier des charges** : « tout est couvert » devient une tautologie, puisque les
exigences ont été déduites de l'implémentation.

Le catalogue est donc construit dans l'autre sens : il **énumère le processus d'examen pratique de
pharmacie de bout en bout** — référentiel institutionnel → conception de l'épreuve → population →
organisation des passages → convocation → jour J → notation → intégrité → résultats →
délibération → publication → archivage → exploitation. Le code et les ADR ne servent qu'à
**renseigner la colonne « état »**.

C'est cette inversion qui fait apparaître le §6 (« les manques que ni le code ni un ADR ne
couvrent »), qu'aucun relevé d'endpoints n'aurait pu produire.

### 1.3 Légende des états

| État | Signification |
|:--:|---|
| ✅ | **Réalisé** — service et interface utilisateur en place, exercé |
| 🔶 | **API sans IHM** — le service existe et est gardé, aucun écran ne l'appelle |
| ⚠️ | **Réalisé avec réserve** — fonctionne, mais un défaut connu et référencé subsiste |
| 📐 | **Décidé, non réalisé** — un ADR arbitre le besoin, aucun code |
| ❌ | **Non couvert** — ni code, ni décision : manque réel |

---

## 2. Acteurs

### 2.1 Acteurs principaux (authentifiés)

| Réf. | Acteur | Rôle technique | Portée | Surface |
|:--:|---|---|---|---|
| **A1** | Visiteur | *aucun* | — | Web + mobile (écrans publics) |
| **A2** | Évaluateur | `EVALUATEUR` | **Globale, mais possession par rotation** (ADR-0007) | Mobile principalement |
| **A3** | Responsable de matière | `RESPONSABLE_MATIERE` | **Une matière** (`matiere_id`) | Web |
| **A4** | Super-administrateur | `SUPER_ADMIN` | **Faculté entière** (ADR-0018 D1) | Web |

**Précisions que le code impose et que le rapport doit conserver :**

- **A3 est aussi le co-responsable.** La co-responsabilité n'est pas un rôle distinct : c'est
  **deux lignes `user_roles`** portant `RESPONSABLE_MATIERE` sur le même `matiere_id`. Le modèle
  la représente déjà et `PUT /api/v1/users/{id}/roles` sait l'écrire ; aucun écran ne le permet
  (UC-12).
- ⚠️ **A4 NE généralise PAS A3.** *Corrigé le 2026-07-31 (Nada) : « a super-admin is an
  administrator, not a subject's professor ».* Les deux sont des **pairs aux métiers distincts**,
  pas un parent et un enfant. **Il n'y a donc pas de généralisation
  `SUPER_ADMIN --|> RESPONSABLE_MATIERE`** — voir **ADR-0018 D5**.
  - **A4 LIT tout** (examens, résultats, archives, analyses agrégées de toutes les matières) :
    c'est le « accès à toutes les données » de son périmètre.
  - **A4 ÉCRIT uniquement son domaine** : comptes, rôles, catalogue des matières, configuration
    globale, modèles de grilles globaux.
  - **A4 n'AUTORISE PAS** : concevoir, lancer, mettre en pause, noter, verrouiller, réajuster,
    clôturer. Ce sont des actes d'**autorité pédagogique**, et l'autorité pédagogique est
    précisément ce qui fait un responsable.
  - **Accéder aux données est une LECTURE.** Créer et lancer n'en sont pas.
  - Seule exception, étroite et nommée : la **continuité institutionnelle** (responsable
    injoignable le matin de l'épreuve) — acte attribué, motivé et annoncé (ADR-0018 D3), pas une
    capacité permanente.

  ⚠️ **La colonne « Acteur » de ce catalogue énonce le modèle VOULU, pas ce que le code permet
  aujourd'hui.** Vérifié le 2026-07-31 : **72 points d'entrée en écriture** ont une garde effective
  nommant `SUPER_ADMIN`, dont **un seul** dans `auth-service` ; les 71 autres sont des actes
  pédagogiques (`ExamenController:36` garde toute la classe, et `changerStatut` — le lancement — n'a
  aucune garde de méthode). Sévérité honnête : **FAIBLE à MOYENNE, gouvernance et non
  vulnérabilité** — aucune escalade de privilège possible (c'est déjà le rôle le plus élevé) et
  **aucun écran ne l'expose** (toutes les routes `admin/*` sont des pages vides). L'atteindre exige
  une requête HTTP fabriquée à la main.
- **A2 n'est pas cloisonné par matière.** Sa légitimité vient de la rotation qui lui est
  affectée, pas de son périmètre disciplinaire — ADR-0007. C'est pourquoi les gardes de
  possession (#213, #218) interrogent la rotation et non le rôle.

### 2.2 Acteur du domaine (non authentifié)

| Réf. | Acteur | Nature |
|:--:|---|---|
| **A5** | Étudiant | **Acteur passif.** Aucun compte, aucun point d'entrée ne l'accepte comme principal. |

⚠️ **À défendre explicitement devant le jury**, car la question sera posée : la réclamation
n'est **pas** déposée par l'étudiant. `POST /api/reclamations` est gardé
`hasAnyRole('SUPER_ADMIN','RESPONSABLE_MATIERE')` — le registre est tenu **pour le compte** de
l'étudiant par l'encadrement. C'est un choix, cohérent avec l'absence de compte étudiant, et non
un oubli. Sur le diagramme, ses liens sont en **pointillés** (« est évalué », « est concerné
par »), jamais des associations d'acteur.

### 2.3 Acteurs systèmes (secondaires)

| Réf. | Acteur | État |
|:--:|---|---|
| **A6** | Service de messagerie (SMTP) | ⚠️ intégré, **désactivé par défaut** (`app.mail.enabled`) |
| **A7** | Service d'analyse (`ai-service`) | 📐 **le service n'existe pas** : ni `microservices/ai-service`, ni `ai-modules/` — ADR-0008, ADR-0021 |
| **A8** | Horloge du serveur | ✅ zone épinglée `Africa/Tunis` — ADR-0010 |

---

## 3. Besoins fonctionnels

Douze domaines. Les huit premiers correspondent aux lignes déjà présentes dans le tableau de
synthèse du rapport ; les quatre derniers le complètent.

### D1 — Authentification et accès

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-01 | Le système doit authentifier un utilisateur par courriel et mot de passe et lui délivrer un jeton d'accès portant ses autorisations à portée. | UC-01 |
| FN-02 | Le système doit prolonger une session sans réauthentification, par rotation d'un jeton opaque, et détecter la réutilisation d'un jeton révoqué. | UC-02 |
| FN-03 | Le système doit permettre la clôture explicite d'une session et révoquer alors l'ensemble des jetons de l'utilisateur. | UC-03 |
| FN-04 | Le système doit permettre à un utilisateur de changer son mot de passe et de le réinitialiser en cas d'oubli, par jeton à usage unique et à durée limitée. | UC-05 → UC-07 |
| FN-05 | Le système doit verrouiller un compte après trois échecs consécutifs et n'offrir de levée que par un canal contrôlé. | UC-08 |

### D2 — Référentiel institutionnel

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-06 | Le système doit maintenir le **catalogue des matières** de la faculté comme unique source de vérité, consultable par tout utilisateur authentifié. | UC-09 |
| FN-07 | Le super-administrateur doit pouvoir **créer, modifier et retirer** une matière sans intervention en base. | UC-10 |
| FN-08 | Le système doit permettre l'**amorçage** d'une installation neuve : premier administrateur et premières matières. | UC-15 |

### D3 — Gestion des utilisateurs et délégation

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-09 | Le système doit permettre la création de comptes et l'affectation de rôles, **dans les limites de la portée de l'acteur qui délègue**. | UC-11, UC-12 |
| FN-10 | Le système doit permettre de désigner **plusieurs responsables sur une même matière** (co-responsabilité). | UC-12 |
| FN-11 | Le système doit permettre la **désactivation** d'un compte, sans perte des traces qu'il a produites. | UC-13 |
| FN-12 | Le système doit offrir un **annuaire** consultable des utilisateurs, filtré selon la portée du demandeur. | UC-14 |

### D4 — Conception de l'épreuve

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-13 | Le responsable doit pouvoir créer une épreuve et en décrire les paramètres de déroulement (date, heure, durée de station, taille de groupe, battement). | UC-16, UC-17 |
| FN-14 | Le responsable doit pouvoir composer le **circuit de stations** et affecter à chacune un ou plusieurs évaluateurs. | UC-20, UC-21 |
| FN-15 | Le responsable doit pouvoir bâtir la **grille d'évaluation** de chaque station : critères pondérés, sous-critères, et corrigé type. | UC-22 → UC-25 |
| FN-16 | Le système doit **garantir qu'un étudiant sans faute peut atteindre la note annoncée** de la station, et refuser le lancement sinon. | UC-26 |
| FN-17 | Le responsable doit pouvoir **capitaliser** son travail : modèles de grilles, duplication d'épreuve, export/import. | UC-27 → UC-31 |
| FN-18 | Le système doit permettre à **plusieurs responsables d'éditer** le même matériel sans perte silencieuse d'écriture. | UC-32 |
| FN-19 | Le responsable doit pouvoir joindre le **sujet PDF** à l'épreuve. | UC-19 |

### D5 — Inscriptions et groupes

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-20 | Le responsable doit pouvoir **importer un listing** d'étudiants et corriger les données manquantes sans réimporter le fichier. | UC-33, UC-34 |
| FN-21 | Le système doit inscrire un étudiant à une épreuve une **et une seule** fois. | UC-35 |
| FN-22 | Le responsable doit pouvoir inscrire des étudiants **en lot** depuis le référentiel existant. | UC-36 |
| FN-23 | Le système doit **répartir les étudiants en lots** (vagues de passage) en respectant l'ordre du listing fourni. | UC-38 |
| FN-24 | Le responsable doit pouvoir **corriger la répartition** : déplacer un étudiant, ajuster un lot. | UC-39, UC-40 |
| FN-25 | Le responsable doit pouvoir **étaler les lots sur plusieurs journées** et le plan doit être stocké, non déduit. | UC-41 |
| FN-26 | Le système doit permettre d'indiquer le **lieu de passage**. | UC-44 |

### D6 — Notation et rotations

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-27 | Le système doit **générer les rotations** d'un lot : quel groupe, à quelle station, devant quel évaluateur. | UC-42, UC-43 |
| FN-28 | L'évaluateur doit pouvoir consulter **uniquement** les sessions qui lui sont affectées. | UC-60, UC-61 |
| FN-29 | L'évaluateur doit pouvoir saisir une note **critère par critère** sur la grille figée de la station. | UC-62 |
| FN-30 | L'évaluateur doit pouvoir **valider** la prestation d'un étudiant sur sa station, puis **verrouiller** la notation. | UC-63, UC-64 |
| FN-31 | L'application mobile doit permettre de noter **sans réseau** et synchroniser ensuite, **sans jamais perdre une note**. | UC-65 |
| FN-32 | L'évaluateur doit pouvoir consulter le **corrigé type** pendant la notation. | UC-66 |

### D7 — Déroulement et suivi

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-33 | Le système doit vérifier et **afficher les pré-conditions de lancement avant le clic**, et refuser le lancement si l'une manque. | UC-48 |
| FN-34 | Le lancement doit **figer la définition de l'épreuve** : la modifier ensuite ne doit pas altérer une notation en cours. | UC-49 |
| FN-35 | Le responsable doit pouvoir **relever les présences** et **ouvrir une vague**. | UC-51, UC-52 |
| FN-36 | L'avancement d'un passage appartient à l'**évaluateur** ; l'avancement d'une vague appartient au **responsable**. | UC-53, UC-54 |
| FN-37 | Le responsable doit disposer d'un **suivi en direct** de l'avancement réel des passages. | UC-55 |
| FN-38 | Le responsable doit pouvoir **suspendre et reprendre** l'épreuve, et l'information doit atteindre les évaluateurs. | UC-56 |
| FN-39 | Le responsable doit pouvoir **remplacer un évaluateur** défaillant en cours d'épreuve, avec motif. | UC-57 |
| FN-40 | Le système doit **avertir l'évaluateur** de l'imminence du passage suivant. | UC-58 |
| FN-41 | La **fin** de l'épreuve doit refléter le travail réellement accompli et rester rattrapable. | UC-59 |

### D8 — Intégrité post-examen

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-42 | Une note verrouillée ne doit être modifiable que par un **canal audité, motivé et historisé**. | UC-67, UC-68 |
| FN-43 | L'évaluateur doit disposer d'une **voie de correction** de sa propre erreur, à sa portée. | UC-64 |
| FN-44 | Le système doit tenir un **registre des réclamations** et de leur résolution. | UC-69, UC-70 |
| FN-45 | La **clôture** doit geler effectivement les notes : aucune écriture ne doit aboutir après. | UC-71 |

### D9 — Communication

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-46 | Le système doit produire pour chaque étudiant sa **convocation** — jour, vague, heure de passage — **dérivée du plan**, jamais saisie à la main. | UC-45 |
| FN-47 | Le système doit pouvoir **envoyer** les convocations par courriel et rendre compte honnêtement de ce qui est parti. | UC-46 |
| FN-48 | Le système doit **notifier l'évaluateur** de son affectation. | UC-47 |

### D10 — Résultats et délibération

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-49 | Le responsable doit pouvoir consulter les **résultats consolidés** de l'épreuve et descendre au **critère** pour comprendre un échec. | UC-72, UC-73 |
| FN-50 | Le jury doit disposer d'un **écran de délibération** : décider, motiver, tracer. | UC-74 |
| FN-51 | Une modification de barème **après l'épreuve** doit être **versionnée** — le barème appliqué reste identifiable, la note brute reste intacte. | UC-75 |
| FN-52 | Les résultats doivent pouvoir être **communiqués aux étudiants**. | UC-76 |
| FN-53 | L'épreuve doit produire un **procès-verbal archivable**. | UC-77 |

### D11 — Analyse psychométrique et aide à la décision

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-54 | Le système doit calculer les **indices de qualité** de l'épreuve : difficulté, discrimination, corrélation item-total, fidélité. | UC-78 |
| FN-55 | Le système doit signaler les **anomalies** de notation. | UC-79 |
| FN-56 | Le système doit pouvoir comparer la **sévérité des évaluateurs**, **à l'intérieur d'une même station uniquement**. | UC-80 |
| FN-57 | À partir de ces constats, le système doit **proposer un barème révisé** motivé — et non livrer des chiffres bruts. | UC-81 |

### D12 — Exploitation et traçabilité

| Réf. | Besoin | UC |
|:--:|---|---|
| FN-58 | Toute opération modifiant un état sensible doit être **journalisée** de façon inaltérable. | UC-82 |
| FN-59 | Un changement de cycle de vie dans un service doit **atteindre** les services qui en dérivent un état. | UC-83 |
| FN-60 | L'exploitant doit disposer d'un **signal de santé** exploitable par service. | UC-84 |
| FN-61 | La pile doit se déployer **à l'identique** sur un poste de la faculté. | UC-85 |
| FN-62 | Les données d'examen doivent pouvoir être **sauvegardées et restaurées**. | UC-86 |

---

## 4. Catalogue des cas d'utilisation

### P1 — Accès et identité

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-01 | Se connecter | A1 | ✅ | `AuthController:27` · `login.component.ts` · `login_screen.dart` |
| UC-02 | Prolonger la session (rotation de jeton) | A1 | ✅ | `AuthController:39` · `auth.service.ts:44` |
| UC-03 | Se déconnecter | A2–A4 | ✅ | `AuthController:49` · `auth.service.ts:60` |
| UC-04 | Consulter son profil | A2–A4 | ⚠️ | `AuthController:97` ; mobile `profile_screen.dart` ✅, web `parametres/profil` = **page vide** (`app.routes.ts:151`) |
| UC-05 | Changer son mot de passe | A2–A4 | 🔶 | `AuthController:66` — **aucun appelant côté web** |
| UC-06 | Demander une réinitialisation | A1 | ⚠️ | `AuthController:108` ; mobile `forgot_password_screen.dart` ✅, **web absent** |
| UC-07 | Confirmer une réinitialisation | A1 | ⚠️ | `AuthController:120` ; idem |
| UC-08 | Verrouillage de compte après échecs | A8 | ⚠️ | implémenté ; **#217** (contournable via `/refresh`, jamais levé par réinitialisation), **#194**, **#16**, **#18** |

### P2 — Référentiel institutionnel *(portée faculté — ADR-0018)*

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-09 | Consulter le catalogue des matières | A2–A4 | ✅ | `MatiereController:27` · `directory-api.service.ts:22` |
| UC-10 | Créer / modifier / retirer une matière | A4 | ❌ | `MatiereController` ne contient **qu'un `@GetMapping`** → **#134** |
| UC-11 | Créer un compte utilisateur | A3, A4 | 🔶 | `UserController:59` gardé ; `admin/utilisateurs` = page vide |
| UC-12 | Affecter / révoquer des rôles (dont co-responsable) | A3, A4 | 🔶 | `UserController:81,102` ; `equipe/co-responsables` = page vide |
| UC-13 | Désactiver un utilisateur | A4 | 🔶 | `UserController:119` |
| UC-14 | Consulter l'annuaire des utilisateurs | A3, A4 | ⚠️ | `UserController:40` ; `directory-api.service.ts:14` appelé **uniquement** pour peupler le sélecteur d'évaluateurs — pas d'écran d'annuaire |
| UC-15 | Amorcer une installation neuve | A4 | ❌ | seul `init.sql` amorce ; aucun parcours |

### P3 — Conception de l'épreuve

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-16 | Créer une épreuve | A3 | ✅ | `ExamenController:40` · `examen-create.component.ts` |
| UC-17 | Modifier une épreuve | A3 | ✅ | `ExamenController:91` |
| UC-18 | Supprimer une épreuve | A3 | ⚠️ | `ExamenController:166` ; **#249** — lots/rotations orphelins, `invalidateExam` **sans appelant** → ADR-0020 |
| UC-19 | Joindre / télécharger le sujet PDF | A3 | ✅ | `ExamenController:174,185` · `exam-api.service.ts:180,194` |
| UC-20 | Composer le circuit de stations | A3 | ✅ | `StationController:30,67,91` · `stations-grilles.component.ts` |
| UC-21 | Affecter des évaluateurs à une station | A3 | ⚠️ | `StationController:78` ; **#242** (accepte un évaluateur inexistant — 12 références pendantes), **ADR-0017** (la réaffectation n'atteint pas les rotations déjà figées) |
| UC-22 | Créer / remplacer la grille d'une station | A3 | ✅ | `GrilleController:34,48` · `grille-editor.component.ts` |
| UC-23 | Définir les critères et leurs pondérations | A3 | ✅ | `GrilleController:98,125,137` |
| UC-24 | Décomposer un critère en sous-critères | A3 | ✅ | `GrilleController:146` |
| UC-25 | Renseigner le corrigé type | A3 | ✅ | `ItemEvaluation.valeurAttendue`/`conditionsAttendues:63-69` |
| UC-26 | Vérifier la complétude du barème | A3 | ⚠️ | `GrilleEvaluation.getMaxAtteignable:88` + refus au lancement ✅ ; **`GET /examens/{id}/baremes-incomplets` n'a aucun appelant** — **#280**, §6.1 |
| UC-27 | Enregistrer une grille comme modèle | A3 | ✅ | `GrilleTemplateController:46` · `bibliotheque.component.ts` |
| UC-28 | Appliquer un modèle à une station | A3 | ✅ | `GrilleTemplateController:77` · `exam-api.service.ts:405` |
| UC-29 | Administrer le catalogue global de modèles | A4 | 🔶 | `GrilleTemplateController:37,69` (`hasRole('SUPER_ADMIN')`) ; `admin/templates` = page vide |
| UC-30 | Dupliquer une épreuve | A3 | 🔶 | `GrilleTemplateController:89` — aucun appelant |
| UC-31 | Exporter / importer une grille (JSON) | A3 | 🔶 ⚠️ | `GrilleTemplateController:102,119` — aucun appelant ; **#220** (entrée malformée → 500, validation d'items contournée) |
| UC-32 | Éditer le même matériel à plusieurs | A3 | 📐 | **ADR-0019** ; `@Version` **absent de toutes les entités** → **#133**, **#92** |

### P4 — Population étudiante

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-33 | Importer un listing d'étudiants | A3 | ✅ | `EtudiantController:68` · `etudiants.component.ts` |
| UC-34 | Créer / corriger un étudiant (dont le courriel) | A3 | ✅ | `EtudiantController:46,90` ; édition en ligne du courriel (#227) |
| UC-35 | Inscrire un étudiant à une épreuve | A3 | ⚠️ | `ExamenParticipationController:54` ; **#214** — unicité `(participation, station)` absente |
| UC-36 | Inscrire des étudiants en masse | A3 | ❌ | **#186** |
| UC-37 | Retirer / supprimer un étudiant | A3, A4 | 🔶 | `EtudiantController:115` (`SUPER_ADMIN`) — aucun appelant |

### P5 — Organisation des passages

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-38 | Répartir les étudiants en lots | A3 | ⚠️ | `LotAssignmentController:42` · `lots.component.ts` ; **#256** — conserver l'ordre du listing (demande encadrant) |
| UC-39 | Créer / modifier / supprimer un lot | A3 | 🔶 | `LotController:61,75,87` — l'IHM passe par la répartition |
| UC-40 | Déplacer un étudiant entre lots | A3 | ✅ | `LotController:139` · `scoring-api.service.ts:265` |
| UC-41 | Étaler les lots sur plusieurs journées | A3 | ⚠️ | `LotController:154` (`PATCH /jour`, #147) ✅ ; `Lot.jour:31` existe ; route `planning` = **page vide** ; **ADR-0011** encore *Proposed* |
| UC-42 | Générer les rotations d'un lot | A3 | ✅ | `RotationGenerationController:35` |
| UC-43 | Réinitialiser les rotations | A3 | ✅ | `RotationGenerationController:52` |
| UC-44 | Indiquer le lieu / la salle de passage | A3 | ❌ | **`Examen` n'a aucun champ `lieu`** ni `Lot` de `salle` — constaté dans `ConvocationDTO:10` |

### P6 — Convocation et communication

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-45 | Consulter les convocations dérivées | A3 | ✅ | `ConvocationController:37` · `ConvocationService` (dérivation serveur) · `convocations.component.ts` |
| UC-46 | Envoyer les convocations par courriel | A3 | ⚠️ | `ConvocationController:53` ; expéditeur réel conditionné à `app.mail.enabled`, **désactivé par défaut** — compte SMTP institutionnel non arbitré |
| UC-47 | Notifier un évaluateur de son affectation | A6 | ❌ | aucun code, aucun ADR |

### P7 — Lancement

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-48 | Vérifier les pré-conditions de lancement | A3 | ⚠️ | `examen-workspace.store.ts:~200-296` — **6 lignes**, dont *Évaluateurs disponibles* (#265) ; **la ligne « Barèmes complets » manque** — **#280**, §6.1 |
| UC-49 | Lancer l'épreuve (et figer sa définition) | A3 | ✅ | `ExamenController:124` · `ExamDefinitionSnapshotService` · **ADR-0015** |
| UC-50 | Réinitialiser une épreuve lancée | A3 | ✅ | `ExamenController:155` · `exam-api.service.ts:163` |

### P8 — Déroulement du jour J

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-51 | Relever les présences | A3 | ✅ | `LotAssignmentController:57,66` · `suivi.component.ts` |
| UC-52 | Ouvrir une vague (lot) | A3 | ✅ | `LotController:107` · `scoring-api.service.ts:332` |
| UC-53 | Avancer le groupe au passage suivant | A2 | ⚠️ | `EvaluateurDashboardController:79` ; **#250** — aucun garde-fou plancher (avancer avant la durée due n'avertit pas) |
| UC-54 | Valider la vague (poignée de main) | A3 | 🔶 | `EvaluateurDashboardController:138` gardé responsable ; **aucun appelant web** — seule la moitié évaluateur d'**ADR-0014-B** est utilisable |
| UC-55 | Suivre la progression en direct | A3 | ⚠️ | `LotController:125` · `suivi.component.ts` (scrutation 5 s) ; **#139** (pas de poussée), **#252**, **#243** |
| UC-56 | Suspendre / reprendre l'épreuve | A3 | ⚠️ | `ExamenController:136,146` · **ADR-0009** ; **#199** — désynchronisation mobile |
| UC-57 | Remplacer un évaluateur en cours d'épreuve | A3 | 🔶 | `EvaluateurSubstitutionController:34` · **ADR-0017** ; **aucun appelant web** |
| UC-58 | Avertir de l'imminence du passage suivant | A2 | 📐 | **ADR-0012** ; `Examen.tempsBattementMin:66`, `avertissementLeadSec:75` en place ; **#151** |
| UC-59 | Terminer l'épreuve | A3 | ⚠️ | `ExamenController:124` ; **#257** — `TERMINE → EN_COURS` inexistant, donc **irréversible** ; **ADR-0016** veut la clôture *dérivée* |

### P9 — Notation

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-60 | Consulter son tableau de bord | A2 | ⚠️ | `EvaluateurDashboardController:52` · `home_screen.dart` ; **#241** (ouverture non bornée), **#209** (moitié mobile d'ADR-0014) |
| UC-61 | Consulter le détail d'un groupe | A2 | ✅ | `EvaluateurDashboardController:67` · `student_detail_screen.dart` |
| UC-62 | Saisir une note critère par critère | A2 | ✅ | `EvaluateurDashboardController:103` · `grading_screen.dart` ; possession vérifiée (#213) |
| UC-63 | Valider un étudiant sur une station | A2 | ⚠️ | `EvaluateurDashboardController:119` ; **#212** — écrase la ligne de participation partagée entre stations |
| UC-64 | Verrouiller une notation | A2 | ⚠️ | `NotationController:113` — **le responsable ne peut pas verrouiller** (ADR-0013, séparation des pouvoirs) ; **#251** — irréversible pour l'évaluateur |
| UC-65 | Noter hors-ligne puis synchroniser | A2 | ⚠️ | `core/offline/{sync_service,offline_storage_service}.dart` ; **#198** — note **supprimée** après 3 échecs ; **#244** — grille lue en direct, écran inatteignable en panne |
| UC-66 | Consulter le corrigé pendant la notation | A2 | ❌ | **#195** — le service l'envoie déjà, l'écran ne l'affiche pas |
| UC-87 | Noter un candidat anonymisé | A2 | ❌ | aucun code, aucun ADR — l'évaluateur voit le **nom** à chaque écran ; voir §6.5 |

### P10 — Intégrité post-examen

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-67 | Réajuster une note verrouillée (audité) | A3 | ✅ | `NotationController:132` — **l'évaluateur ne peut pas réajuster** · **ADR-0013 P2** |
| UC-68 | Consulter l'historique des réajustements | A3 | ✅ | `NotationController:142` · `resultats.component.ts` |
| UC-69 | Enregistrer une réclamation | A3 | ✅ | `ReclamationController:33` · `reclamations-panel.component.ts` |
| UC-70 | Résoudre une réclamation | A3 | ✅ | `ReclamationController:55` |
| UC-71 | Clôturer l'épreuve et geler les notes | A3 | 📐 ⚠️ | **ADR-0016** (*Proposed*) ; **#236** — la clôture **n'empêche rien** : notation et validation aboutissent encore sur un examen `TERMINE` |

### P11 — Résultats et délibération

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-72 | Consulter les résultats de l'épreuve | A3, A4 *(lecture)* | ✅ | `NotationController:80` · `resultats.component.ts` |
| UC-73 | Analyser un critère en profondeur | A3, A4 *(lecture)* | ✅ | `NotationItemController:33` · `resultats.component.html:263-270` |
| UC-74 | Délibérer en jury | A3 | 📐 | **ADR-0021 D4** — « l'écran de délibération précède toute statistique » |
| UC-75 | Versionner le barème après délibération | A3 | 📐 | **ADR-0021 D9** · **#135** · seconde moitié de **#276** |
| UC-76 | Publier les résultats aux étudiants | A5 | ❌ | aucun code, aucun ADR — voir §6.2 |
| UC-77 | Produire un procès-verbal archivable | A3 | ❌ | aucun code, aucun ADR — voir §6.3 |

### P12 — Analyse et aide à la décision

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-78 | Calculer les indices psychométriques | A7 | 📐 | **ADR-0008**, **ADR-0021** ; **le service `ai-service` n'existe pas dans le dépôt** ; route `analyses-ia` = page vide |
| UC-79 | Détecter les anomalies de notation | A7 | 📐 | **ADR-0008** |
| UC-80 | Comparer la sévérité des évaluateurs | A3, A4 | 📐 | **ADR-0021 D1/D2** — comparaison **intra-station obligatoire**, descriptive et jamais automatique (D3) |
| UC-81 | Proposer un barème révisé motivé | A7 | 📐 | **ADR-0021 partie 2** — la finalité affichée du volet IA |

### P13 — Exploitation

| UC | Cas d'utilisation | Acteur | État | Preuve / référence |
|:--:|---|:--:|:--:|---|
| UC-82 | Journaliser les actions sensibles | A8 | ⚠️ | `auth-service/audit/**` ✅ ; **exam et scoring : aucune piste d'audit** → **#64** |
| UC-83 | Propager les changements de cycle de vie | A8 | 📐 | **ADR-0020** — boîte d'envoi transactionnelle ; aucun code |
| UC-84 | Superviser la santé des services | A8 | ✅ | actuateurs + `healthcheck` corrigé (#255) |
| UC-85 | Déployer la pile à l'identique | A8 | ✅ | `infrastructure/docker-compose.yml` ; déploiement faculté éprouvé |
| UC-86 | Sauvegarder / restaurer les données d'examen | A8 | ❌ | aucune procédure — voir §6.6 |

---

## 5. Synthèse de couverture

| État | Nombre | Part |
|---|:--:|:--:|
| ✅ Réalisé | 34 | 39 % |
| ⚠️ Réalisé avec réserve | 22 | 25 % |
| 🔶 API sans IHM | 11 | 13 % |
| 📐 Décidé, non réalisé | 10 | 12 % |
| ❌ Non couvert | 10 | 11 % |
| **Total** | **87** | |

*Deux cas portent deux états (UC-31 🔶⚠️, UC-71 📐⚠️) ; ils sont comptés une seule fois, sur
l'état dominant.*

**Réponse à la question « a-t-on tout couvert ? » : non**, et le détail est plus instructif que le
verdict.

1. **Le cœur du métier est en place.** Les phases P3 à P11 — concevoir, peupler, organiser,
   convoquer, lancer, dérouler, noter, réajuster — sont réalisées de bout en bout et ont été
   exercées sur des examens réels de démonstration. C'est le chemin critique, et il tient.
2. **La deuxième catégorie la plus nombreuse n'est pas « manquant » mais « avec réserve ».**
   Vingt-deux cas fonctionnent tout en portant un défaut connu, référencé et souvent déjà
   arbitré. C'est ce que le rapport doit dire, plutôt que de cocher des cases : cela donne le
   chapitre *Limites et perspectives* sans le fabriquer.
3. **Onze cas ont un service complet et aucun écran.** Toute la **P2** (gestion des utilisateurs
   et des rôles) est dans ce cas : le service sait créer un compte, nommer un co-responsable,
   désactiver un utilisateur — et **sept routes de l'application web sont des pages vides**
   (`app.routes.ts:142,143,146,164,165,166,167`). C'est le plus grand écart entre ce que le
   système *peut* et ce qu'un enseignant *peut faire*.
4. **Le plan administratif est entièrement arbitré et entièrement à construire** : ADR-0018 à
   ADR-0021 sont fusionnés, aucun n'a de code.
5. **Le volet IA — l'argument de valeur du projet — n'a aucune surface.** Le service `ai-service`
   **n'existe pas dans le dépôt** (`microservices/` en compte six, sans lui ; `ai-modules/` a
   disparu). ⚠️ Or `chap2.tex:340` le liste dans le tableau d'architecture **avec son port 8084**,
   et `chap2.tex:258` annonce Python/FastAPI dans la pile technique — voir §6.8.

---

## 6. Les manques que ni le code ni un ADR ne couvrent

C'est la section qui justifie l'inversion méthodologique du §1.2 : aucun relevé de points
d'entrée ne pouvait la produire.

⚠️ À distinguer des cas ❌ **déjà suivis par une issue** (UC-36/#186, UC-66/#195) : ceux-là sont
identifiés et priorisés. Les manques ci-dessous ne sont **ni codés, ni arbitrés par un ADR, ni
ouverts en issue** — ce sont des angles morts, et c'est ce qui les rend dignes d'une section.

### 6.1 La ligne de pré-vol manquante — **#280** *(écart constaté ce jour, désormais suivi)*

`GET /api/examens/{id}/baremes-incomplets` (`ExamenController:116`) a été livré par la PR #277.
La liste de contrôle de lancement (`examen-workspace.store.ts`) compte **six lignes** et
**aucune ne concerne le barème**. Le garde autoritaire de `changerStatut` refuse bien le
lancement — mais le responsable l'apprend en **erreur rouge après le clic**, exactement le
défaut que la doctrine #185 et le commentaire de `BaremeIncompletResponse:11-13` interdisent.
Le service existe et n'a pas de consommateur : c'est un raccordement, pas une fonctionnalité.

### 6.2 L'étudiant ne reçoit jamais son résultat

`GET /api/notations/examen/{id}/results` est gardé `SUPER_ADMIN | RESPONSABLE_MATIERE`. Aucun
point d'entrée ne communique un résultat à un étudiant, et aucun ADR ne tranche la question.
**Ce n'est pas forcément à construire — mais il faut le décider.** Un examen de pharmacie dont
la plateforme ne notifie pas le candidat est un choix défendable (la faculté a ses canaux
officiels) ; l'omettre en silence n'en est pas un. À arbitrer par ADR avant la soutenance.

### 6.3 Aucun procès-verbal, aucun archivage

Un examen pratique produit un document à valeur institutionnelle : liste des candidats, notes
arrêtées, décisions du jury, signatures. Rien dans le système ne le produit ni ne le conserve.
Lié à UC-74/UC-75 : une délibération sans trace formelle n'a pas de portée.

### 6.4 Le lieu de passage n'existe pas dans le modèle

`Examen` ne porte **ni `lieu` ni `salle`**, `Lot` non plus (constat inscrit dans
`ConvocationDTO:10`). La convocation annonce donc **jour, vague et heure, mais pas où se
présenter**. Sur un circuit multi-salles, c'est une information que l'étudiant devra obtenir
ailleurs.

### 6.5 Aucune anonymisation de la notation

L'évaluateur voit le **nom** de l'étudiant qu'il note, à chaque écran. Dans un examen sensible,
l'anonymat du candidat est un dispositif d'équité classique — et le projet dispose déjà de tout
ce qu'il faut pour l'offrir (`Etudiant.numero`, les groupes). Aucune décision n'a été prise dans
un sens ni dans l'autre. À arbitrer : c'est aussi le pendant naturel de l'analyse de sévérité
des évaluateurs (UC-80).

### 6.6 Aucune procédure de sauvegarde

La pile tourne sur **un poste de la faculté**. Aucune sauvegarde des volumes PostgreSQL n'est
définie. Une panne disque le lendemain d'un examen fait perdre les notes.

### 6.7 L'amorçage n'a aucun parcours

Le premier `SUPER_ADMIN` et les premières matières ne viennent que de `init.sql`. Sur une
installation neuve sans ce jeu d'amorçage, la plateforme est inutilisable et rien ne permet d'en
sortir par l'interface. Lié à UC-10 (#134).

---

### 6.8 Le rapport annonce un service qui n'existe pas

Vérifié ce jour, et à corriger **dans le rapport** autant que dans le code :

- `microservices/` contient **six** répertoires : `api-gateway`, `auth-service`,
  `discovery-server`, `epos-common`, `exam-service`, `scoring-service`. **Pas d'`ai-service`.**
  Le répertoire `ai-modules/` mentionné dans d'anciennes notes a également disparu.
- Or `rapport/chapters/chap2.tex:340` fait figurer **`ai-service` … 8084** dans le tableau des
  services, et `chap2.tex:258` annonce **Python / FastAPI** dans la pile technique. Le tableau
  `tab:besoins-fonctionnels` (`chap2.tex:67`) et le backlog (`:170`) présentent l'analyse
  psychométrique comme un besoin couvert, et `chap2.tex:211` l'attribue au sprint 5.

✅ **À la décharge du rapport :** les chapitres 5 et 6 sont **honnêtes**. `chap5.tex:44` porte un
commentaire explicite « *STATUT HONNÊTE : verrouillage par notation existe ; publication /
clôture n'existent pas* », et `chap6.tex` n'est encore qu'un squelette de commentaires. **Il n'y a
donc aucune fausse affirmation rédigée en prose** — l'exposition est concentrée dans les
**tableaux du chapitre 2**, qui décrivent une cible comme un état.

Deux corrections suffisent à la lever : soit construire, soit **déplacer ces lignes vers les
perspectives** et retirer le port 8084 du tableau d'architecture.

---

## 7. Besoins non fonctionnels

### 7.1 Sécurité

| Réf. | Exigence | État |
|:--:|---|:--:|
| NFR-01 | Authentification par jeton signé, autorisations **à portée** dans le jeton (`ROLE_X:matiereId`) | ✅ |
| NFR-02 | Jeton de rafraîchissement **opaque**, stocké haché (SHA-256), tourné à chaque usage, avec détection de réutilisation par famille | ✅ |
| NFR-03 | Contrôle d'accès par rôle **et par portée de matière** dans chaque service consommateur | ⚠️ **#86** — scoring ne vérifie que le rôle |
| NFR-04 | Possession vérifiée à l'écriture : l'évaluateur n'écrit que sur ses rotations | ⚠️ #213 traité ; **#274** — les deux portes divergent encore |
| NFR-05 | Verrouillage de compte, limitation de débit sur les points d'entrée d'authentification | ⚠️ **#16**, **#217**, **#194** |
| NFR-06 | Piste d'audit inaltérable des opérations sensibles | ⚠️ auth ✅ / **#64** |
| NFR-07 | Mot de passe haché en bcrypt, coût fixé à 12 | ✅ |

### 7.2 Fiabilité et intégrité des données

| Réf. | Exigence | État |
|:--:|---|:--:|
| NFR-08 | Une note validée n'est modifiable que par un canal audité et motivé | ✅ ADR-0013 |
| NFR-09 | La définition de l'épreuve est **figée au lancement** ; l'éditer ensuite n'altère aucune notation en cours | ✅ ADR-0015 |
| NFR-10 | Écriture en mode strict, lecture **dégradée par session** : un instantané manquant échoue bruyamment à l'écriture, jamais silencieusement | ✅ ADR-0015 |
| NFR-11 | Aucune écriture concurrente ne doit être perdue silencieusement | 📐 ADR-0019 — `@Version` absent partout |
| NFR-12 | Un changement d'état ne doit pas laisser de données orphelines dans un autre service | 📐 ADR-0020 · ⚠️ #249 |
| NFR-13 | Une suppression en cascade ne doit jamais atteindre une notation verrouillée | ⚠️ **#219** |

### 7.3 Disponibilité et fonctionnement dégradé

| Réf. | Exigence | État |
|:--:|---|:--:|
| NFR-14 | L'évaluateur doit pouvoir noter **sans réseau** et synchroniser ensuite | ⚠️ ADR-0002 ; **#198**, **#244** |
| NFR-15 | Une note saisie hors-ligne ne doit **jamais** être perdue, quel que soit le nombre d'échecs de synchronisation | ⚠️ **#198** (défaut ouvert, HIGH) |
| NFR-16 | Une dépendance indisponible ne doit pas vider un tableau de bord : ouverture en cas de doute, mais **bornée** | ⚠️ **#241** |
| NFR-17 | La profondeur du contrat hors-ligne est **définie par acteur** : profonde pour le mobile, superficielle pour la PWA web | ✅ ADR-0002 |

### 7.4 Temps réel et cohérence temporelle

| Réf. | Exigence | État |
|:--:|---|:--:|
| NFR-18 | Le suivi doit refléter l'état courant des passages avec une latence faible | ⚠️ scrutation 5 s ; **#139** (poussée) |
| NFR-19 | Une seule zone horaire de référence côté serveur (`Africa/Tunis`) | ✅ ADR-0010 |
| NFR-20 | Aucun affichage de durée ne doit pouvoir devenir absurde ; en cas de doute, **ne rien afficher** | ⚠️ **#243**, **#252** |

### 7.5 Ergonomie — critères de Scapin & Bastien

⚠️ Section importante : ces critères ne sont pas cités pour l'ornement, chacun a **déclenché une
correction traçable** dans le projet.

| Réf. | Critère | Exigence dérivée | Trace |
|:--:|---|---|---|
| NFR-21 | **Guidage** | Le parcours de préparation doit indiquer l'étape suivante ; l'utilisateur ne doit pas deviner l'ordre des onglets | ✅ #185 — parcours guidé + panneau conducteur du jour J |
| NFR-22 | **Charge de travail** | Aucune consigne ne doit exiger de l'enseignant un détour hors de l'écran. La consigne « ajoutez une colonne au fichier et réimportez-le » a été **remplacée** par l'édition en ligne du courriel | ✅ #227 |
| NFR-23 | **Contrôle explicite** | Les transitions d'état techniques doivent être **invisibles** : elles se produisent à l'intérieur de l'acte que l'utilisateur a voulu, jamais comme une étape à valider (« Finaliser la configuration » a été supprimé) | ✅ #185 |
| NFR-24 | **Gestion des erreurs** | Une pré-condition bloquante doit s'afficher **avant** le clic, pas en erreur après | ⚠️ **respecté 5 fois sur 6** — **#280** |
| NFR-25 | **Signifiance des codes et dénominations** | Le vocabulaire de l'interface est celui de l'enseignant, jamais celui du modèle. Aucun message ne doit exposer un détail d'implémentation | ⚠️ « Envoi simulé » réécrit (#227) ; **#253** — « Lot N/M » désigne en réalité un **groupe** |
| NFR-26 | **Homogénéité / cohérence** | Une même action porte le même nom et le même geste partout ; conventions de structure documentées | ✅ `frontend-web/STRUCTURE.md` |
| NFR-27 | **Adaptabilité** | Le responsable doit pouvoir corriger le plan sans repartir de zéro (déplacer un étudiant, changer le jour d'un lot, remplacer un évaluateur) | ✅ UC-40, UC-41 · 🔶 UC-57 |
| NFR-28 | **Compatibilité** | L'ordre des étudiants à l'écran doit être celui du listing fourni par l'administration | ⚠️ **#256** (demande de l'encadrante) |

### 7.6 Maintenabilité, portabilité, qualité

| Réf. | Exigence | État |
|:--:|---|:--:|
| NFR-29 | Services faiblement couplés ; la dépendance inter-services reste **unidirectionnelle** (scoring → exam) | ✅ ADR-0020 §Contexte |
| NFR-30 | Enveloppe de réponse unifiée entre tous les services | ✅ ADR-0004 |
| NFR-31 | Toute décision structurante est consignée en ADR daté et statué | ✅ 21 ADR |
| NFR-32 | Intégration continue + analyse de qualité automatisée bloquante | ✅ GitHub Actions + SonarCloud |
| NFR-33 | La pile entière se lance à l'identique par conteneurisation | ✅ |
| NFR-34 | Documentation d'installation exploitable par un tiers | ⚠️ **#34** |
| NFR-35 | Tests d'intégration contre une base réelle | ❌ **#28** |

---

## 8. Fiches détaillées des cas d'utilisation critiques

Dix fiches, choisies parce qu'elles portent une règle métier que le code seul ne dit pas.

### Fiche UC-49 — Lancer l'épreuve

| | |
|---|---|
| **Acteur principal** | A3 Responsable de matière |
| **Objectif** | Rendre l'épreuve opérationnelle et **figer sa définition** |
| **Préconditions** | Épreuve en `BROUILLON`/`CONFIGURE` ; ≥ 1 station, chacune avec grille et évaluateur ; ≥ 1 étudiant inscrit ; lots répartis ; jour courant = un jour de l'épreuve ; aucun évaluateur engagé dans une autre épreuve en cours ; barème atteignable = note annoncée sur chaque station |
| **Postconditions** | Statut `EN_COURS` ; `launched_at` horodaté ; **instantané de définition écrit** (stations, grilles, items) |
| **Scénario nominal** | 1. Ouvrir l'onglet *Lancement*. 2. Lire la liste de pré-vol. 3. Cliquer *Lancer l'examen*. 4. Le service valide la transition, écrit l'instantané, horodate. 5. Le tableau de bord des évaluateurs sert désormais les sessions. |
| **Alternatif A1** | Épreuve encore en `BROUILLON` → la transition `BROUILLON→CONFIGURE` est **enchaînée en silence** (NFR-23), sans étape visible. |
| **Exception E1** | Un évaluateur tient déjà une station dans une épreuve en cours → refus (#265). Annoncé **avant** le clic. |
| **Exception E2** | Une station expose moins de points que sa note annoncée → refus (#276). ⚠️ **Non annoncé avant le clic** — **#280**. |
| **Exception E3** | Le jour courant n'est pas un jour de l'épreuve → refus. |
| **Règle métier** | **ADR-0015** : après cette action, éditer une grille n'altère aucune notation en cours. C'est ce qui sépare le *plan de contrôle* du *plan de données*. |

### Fiche UC-53 / UC-54 — Avancer un passage et valider une vague

| | |
|---|---|
| **Acteurs** | A2 Évaluateur (UC-53) · A3 Responsable (UC-54) |
| **Objectif** | Faire progresser l'épreuve **au rythme réel du travail** |
| **Précondition** | Vague ouverte ; l'évaluateur est affecté à la station de la rotation |
| **Postcondition UC-53** | Le groupe suivant est en position devant cette station |
| **Postcondition UC-54** | La vague est close ; la suivante devient ouvrable |
| **Règle métier — la plus contre-intuitive du projet** | **ADR-0014-B** : l'avancement d'un **passage** appartient à l'évaluateur, l'avancement d'une **vague** appartient au responsable. Aucune horloge ne fait progresser quoi que ce soit : le temps affiché est une **indication**, jamais un état. |
| **Exception E1** | Évaluateur non affecté à la station → 403 (`verifierAffectationStation`, #213). |
| **Réserve** | ⚠️ Aucun garde-fou **plancher** : avancer avant la durée due n'avertit pas (#250). |
| **Réserve** | 🔶 UC-54 n'a **aucun appelant web** : la moitié responsable de la poignée de main n'est pas utilisable. |

### Fiche UC-62 — Saisir une note

| | |
|---|---|
| **Acteur principal** | A2 Évaluateur (mobile) |
| **Préconditions** | Session servie par le tableau de bord ; notation non verrouillée ; **l'évaluateur est affecté à la station** |
| **Postconditions** | Valeurs enregistrées par critère ; `saisi_par` renseigné ; note de station recalculée |
| **Scénario nominal** | 1. Choisir l'étudiant. 2. Parcourir les critères de la grille **figée**. 3. Saisir binaire ou numérique. 4. Enregistrer — localement si hors-ligne. 5. Synchronisation à la reconnexion. |
| **Alternatif A1** | Hors-ligne → file locale, bandeau de connectivité, synchronisation différée (ADR-0002). |
| **Exception E1** | Évaluateur non affecté → 403. |
| **Exception E2** | Notation verrouillée → refus (ADR-0013 partie 1 ; la porte par item a été fermée). |
| **Réserve** | ⚠️ **#198** : après trois échecs de synchronisation, la note locale est **supprimée**. Défaut ouvert, priorité haute. |
| **Réserve** | ⚠️ **#244** : la grille est lue en direct dans exam-service → écran inatteignable en panne, angle mort d'ADR-0015. |

### Fiche UC-64 — Verrouiller une notation

| | |
|---|---|
| **Acteurs autorisés** | **A2 Évaluateur seulement** — jamais A3, et pas A4 non plus (ADR-0018 D5 : noter et verrouiller sont des actes d'examinateur). ⚠️ Le code admet encore A4 : sur-attribution, cf. §2.1. |
| **Postcondition** | `verouillee = true`, `verrouille_par` renseigné ; toute écriture ultérieure passe par UC-67 |
| **Règle métier** | **Séparation des pouvoirs (ADR-0013)** : celui qui note verrouille ; celui qui pilote la matière ne peut que **réajuster de façon auditée**. Une simplification d'affichage qui fusionnerait les deux détruirait la garantie. |
| **Réserve** | ⚠️ **#251** — irréversible pour l'évaluateur : sa propre erreur devient hors de sa portée. Décision prise (2026-07-29) : la voie de correction reste le canal audité du responsable. |

### Fiche UC-67 — Réajuster une note verrouillée

| | |
|---|---|
| **Acteurs autorisés** | **A3 Responsable seulement** — jamais A2 (ADR-0013), et pas A4 (ADR-0018 D5 : le responsable signe le barème). ⚠️ Le code admet encore A4 : sur-attribution, cf. §2.1. |
| **Préconditions** | Notation verrouillée ; **motif obligatoire** |
| **Postconditions** | Nouvelle valeur appliquée ; **ligne d'ajustement créée** (ancienne valeur, nouvelle, motif, auteur, horodatage) ; la note brute d'origine reste lisible |
| **Règle métier** | C'est **l'unique** canal de modification sanctionné après verrouillage. Sa valeur est l'irréversibilité de sa trace, pas la modification elle-même. |

### Fiche UC-71 — Clôturer l'épreuve

| | |
|---|---|
| **Acteur principal** | A3 Responsable |
| **État** | 📐 décidé (ADR-0016) · ⚠️ #236 |
| **Règle métier décidée** | La clôture est **dérivée du travail accompli**, non déclarée par un bouton. `NON_EVALUE` est un état **accepté** : un étudiant absent ou non évalué ne bloque pas la clôture. Le lancement est **imposé**, la clôture est **constatée**. |
| **Réserve majeure** | ⚠️ **#236** — aujourd'hui la clôture **n'empêche rien** : notation et validation aboutissent encore sur une épreuve `TERMINE`. La sévérité doit rester mesurée : l'interface **empêche déjà** le responsable d'y arriver ; le défaut est atteignable par appel direct au service, non par un parcours utilisateur. |
| **Réserve** | ⚠️ **#257** — `TERMINE → EN_COURS` n'existe pas : une clôture prématurée est définitive. |

### Fiche UC-74 — Délibérer en jury

| | |
|---|---|
| **Acteurs** | **A3 Responsable et co-responsables** — le jury de la matière. A4 en **lecture** seulement (ADR-0018 D5) : il consulte la délibération, il ne délibère pas. |
| **État** | 📐 ADR-0021 D4 |
| **Précondition** | Épreuve clôturée ; résultats consolidés disponibles |
| **Postconditions** | Décisions consignées et motivées ; barème appliqué identifié ; trace exploitable pour UC-77 |
| **Règle métier** | **ADR-0021 D4 : l'écran de délibération précède toute statistique.** Une analyse psychométrique sans lieu de décision produit des chiffres que personne n'utilise. L'ordre de construction est une décision d'architecture, pas une préférence. |

### Fiche UC-75 — Versionner le barème après délibération

| | |
|---|---|
| **Acteurs** | **A3 Responsable seulement.** Modifier un barème est un acte d'autorité pédagogique : A4 en est exclu (ADR-0018 D5). |
| **État** | 📐 ADR-0021 D6/D7/D9 · #135 · seconde moitié de #276 |
| **Ce que « changer le barème » signifie réellement** | Multiplier toutes les notes par un facteur ne change **rien** au classement : c'est le point qui a fait corriger l'ADR. Trois actes ont un effet réel : **(a) retirer un critère** de l'assiette — l'épreuve est alors notée sur moins, ce qui relève tout le monde sans toucher une valeur brute ; **(b) redistribuer les pondérations** entre critères — cela change le classement, donc c'est un acte de jury ; **(c) requalifier un critère** en non discriminant. |
| **Contrainte** | La **note brute reste intacte**. Seuls le barème appliqué et son dénominateur sont versionnés. La séparation *note brute / barème appliqué* est le cœur de la fiche. |
| **Précondition** | Décision de jury enregistrée (UC-74) |

### Fiche UC-80 — Comparer la sévérité des évaluateurs

| | |
|---|---|
| **Acteurs** | A3 dans sa matière, A4 à l'échelle de la faculté (ADR-0021 D5) |
| **État** | 📐 |
| **Contrainte porteuse** | ⚠️ **ADR-0021 D2 : la comparaison est INTRA-STATION, jamais une moyenne globale.** Deux évaluateurs de stations différentes n'ont pas noté la même chose ; les comparer produirait un classement d'enseignants sans signification, avec un coût humain réel. |
| **Contrainte** | **D3 : descriptif, jamais punitif, jamais automatique.** Le système signale un écart ; il ne conclut pas. |

### Fiche UC-81 — Proposer un barème révisé

| | |
|---|---|
| **Acteur principal** | A7 Service d'analyse, **au service** de A3 |
| **État** | 📐 ADR-0021 partie 2 |
| **Objectif** | Ne pas livrer des constats bruts, mais une **proposition argumentée** : « le critère 3 a été raté par 78 % des candidats et ne discrimine pas (corrélation item-total 0,04) ; le retirer de l'assiette porte la moyenne de 8,4 à 11,2 — décision du jury. » |
| **Postcondition** | Une proposition, jamais une application. L'acte reste celui du jury (UC-74, UC-75). |
| **Règle métier** | C'est la finalité affichée du volet IA : transformer une mesure en **aide à la décision**. Une IA qui appliquerait elle-même le barème sortirait du cadre de D3. |

---

## 9. Spécification des diagrammes de cas d'utilisation

> **Rappel de convention :** toutes les figures du rapport sont réalisées à la main par Nada
> (draw.io, ou PlantUML pour les diagrammes UML). Cette section fournit la **spécification** à
> dessiner, pas un rendu.

### 9.1 Diagramme global (`fig:usecase-global`)

- **Acteurs à gauche :** A1 Visiteur, A2 Évaluateur. **À droite :** A3 Responsable de matière,
  A4 Super-administrateur.
- ⚠️ **AUCUNE généralisation.** *Corrigé le 2026-07-31.* Ne pas dessiner
  `A4 --|> A3` : une généralisation sur un diagramme de cas d'utilisation **affirme** « il exécute
  aussi tous ces cas », ce qu'ADR-0018 D5 récuse. A3 et A4 sont deux acteurs **pairs**.
- **Les 4 bulles propres à A4**, et ce sont les mots de Nada : « Gérer les comptes et les rôles » ·
  « Gérer le catalogue des matières » · « Configurer la plateforme » · « **Consulter les données de
  toutes les matières** ». Cette dernière porte « il voit tout » **sans** prétendre qu'il rédige.
- **A5 Étudiant** en bas, relié en **pointillés** aux paquets *Notation* et *Résultats*
  (« est évalué », « est concerné par »). ⚠️ **Ne pas dessiner « Étudiant → déposer une
  réclamation »** : la réclamation est créée par le responsable.
- **A6/A7** en acteurs systèmes à droite, reliés à UC-46 et UC-78→UC-81.
- **Regrouper en paquets** correspondant aux phases P1 à P13 ; 87 bulles en une colonne seraient
  illisibles. Si la hauteur reste excessive, scinder en un diagramme global par paquets + les
  diagrammes par acteur du §9.2.
- **Deux séparations que le diagramme ne doit pas perdre**, sous peine de contredire ADR-0013 :
  - UC-64 *Verrouiller* → relié à **A2 et A4 seulement**
  - UC-67 *Réajuster* → relié à **A3 et A4 seulement**

### 9.2 Diagrammes par acteur

- **A2 Évaluateur** (surface mobile) : UC-01→UC-04, UC-60→UC-66, UC-53. Une relation
  `<<include>>` de UC-62 vers *Vérifier l'affectation à la station*.
- **A3 Responsable** : le plus dense — P3 à P11. `<<include>>` de UC-49 vers UC-48
  (*Vérifier les pré-conditions*) ; `<<extend>>` de UC-57 (*Remplacer un évaluateur*) sur UC-55
  (*Suivre la progression*), puisque c'est une déviation exceptionnelle du déroulement.
- **A4 Super-administrateur** : P2 en entier + ses 4 bulles ci-dessus. **Sans généralisation
  vers A3** — ses associations vont à ses propres cas, plus une association de lecture vers les
  résultats (UC-72/73) et les analyses agrégées (UC-80, ADR-0021 D5).

### 9.3 Convention de couleur pour la lecture de couverture *(optionnel, utile en soutenance)*

Un même diagramme, teinté selon les cinq états du §1.3, montre d'un coup d'œil ce qui est livré,
ce qui est réservé et ce qui reste. C'est un support de la section *Perspectives* plus honnête
qu'une liste.

---

## 10. Matrice de traçabilité

Lecture : **besoin → cas d'utilisation → décision qui l'arbitre → défaut qui le limite**. Un
besoin sans ADR ni issue et non réalisé est un manque du §6.

| Besoin | UC | ADR | Issues ouvertes | État dominant |
|---|---|---|---|:--:|
| FN-01 → FN-03 | UC-01 → UC-03 | — | — | ✅ |
| FN-04 | UC-05 → UC-07 | — | — | 🔶 web |
| FN-05 | UC-08 | — | #217, #194, #16, #18 | ⚠️ |
| FN-06 | UC-09 | 0006 | — | ✅ |
| FN-07, FN-08 | UC-10, UC-15 | — | #134 | ❌ |
| FN-09 → FN-12 | UC-11 → UC-14 | 0018 | — | 🔶 |
| FN-13, FN-19 | UC-16, UC-17, UC-19 | — | — | ✅ |
| FN-14 | UC-20, UC-21 | 0017 | #242 | ⚠️ |
| FN-15 | UC-22 → UC-25 | — | #195 (mobile) | ✅ |
| FN-16 | UC-26, UC-48 | — | #276, **#280** | ⚠️ |
| FN-17 | UC-27 → UC-31 | — | #220 | 🔶 |
| FN-18 | UC-32 | **0019** | #133, #92 | 📐 |
| FN-20 | UC-33, UC-34 | — | — | ✅ |
| FN-21 | UC-35 | — | #214 | ⚠️ |
| FN-22 | UC-36 | — | #186 | ❌ |
| FN-23, FN-24 | UC-38 → UC-40 | 0014-A | #256 | ⚠️ |
| FN-25 | UC-41 | **0011** | #147 | ⚠️ |
| FN-26 | UC-44 | — | — | ❌ |
| FN-27 | UC-42, UC-43 | — | — | ✅ |
| FN-28 → FN-30 | UC-60 → UC-64 | 0007, **0013** | #241, #212, #251, #209 | ⚠️ |
| FN-31 | UC-65 | 0002 | **#198**, #244 | ⚠️ |
| FN-32 | UC-66 | — | #195 | ❌ |
| *(non exprimé)* | UC-87 | — | — | ❌ §6.5 |
| FN-33, FN-34 | UC-48, UC-49 | **0015** | #276, **#280** | ⚠️ |
| FN-35 | UC-51, UC-52 | 0014-A | — | ✅ |
| FN-36 | UC-53, UC-54 | **0014, 0014-B** | #250, #207–#210 | ⚠️ 🔶 |
| FN-37 | UC-55 | — | #139, #252, #243 | ⚠️ |
| FN-38 | UC-56 | **0009** | #199 | ⚠️ |
| FN-39 | UC-57 | **0017** | — | 🔶 |
| FN-40 | UC-58 | **0012** | #151 | 📐 |
| FN-41 | UC-59 | **0016** | #257 | ⚠️ |
| FN-42, FN-43 | UC-67, UC-68 | **0013** | #251, #274 | ✅ |
| FN-44 | UC-69, UC-70 | — | — | ✅ |
| FN-45 | UC-71 | **0016** | #236 | 📐 ⚠️ |
| FN-46, FN-47 | UC-45, UC-46 | 0014-A | #227 | ⚠️ |
| FN-48 | UC-47 | — | — | ❌ |
| FN-49 | UC-72, UC-73 | — | — | ✅ |
| FN-50 | UC-74 | **0021 D4** | — | 📐 |
| FN-51 | UC-75 | **0021 D6/D7/D9** | #135, #276 | 📐 |
| FN-52 | UC-76 | — | — | ❌ |
| FN-53 | UC-77 | — | — | ❌ |
| FN-54, FN-55 | UC-78, UC-79 | **0008** | — | 📐 |
| FN-56 | UC-80 | **0021 D1/D2/D3** | — | 📐 |
| FN-57 | UC-81 | **0021 partie 2** | — | 📐 |
| FN-58 | UC-82 | — | #64 | ⚠️ |
| FN-59 | UC-83 | **0020** | #249, #241 | 📐 |
| FN-60, FN-61 | UC-84, UC-85 | — | #34 | ✅ |
| FN-62 | UC-86 | — | — | ❌ |

### 10.1 Contrôle de non-régression doctrinale

⚠️ Le tableau sert aussi de garde-fou contre la réintroduction d'un **modèle abandonné**. Tout
besoin rédigé contre l'une des positions ci-dessous doit être rejeté :

| Modèle **abandonné** | Position en vigueur | Source |
|---|---|---|
| Une horloge fait progresser l'examen | La **progression** fait progresser l'examen ; le temps est une indication | ADR-0014 |
| L'avancement d'une vague est automatique | Il appartient au **responsable** (poignée de main) | ADR-0014-B *(supersède 0014-A §1)* |
| Un bouton « clôturer » déclare la fin | La clôture est **dérivée** ; `NON_EVALUE` est accepté | ADR-0016 |
| Le multi-jour se simule par une pause | Le multi-jour est un **plan stocké** (`Lot.jour`) | ADR-0011 **§1 seulement**, ADR-0014-A |
| Une horloge par journée corrige le trou de la nuit | ⛔ **ADR-0011 §2 est VOID** : il n'y a plus d'horloge d'état à réparer — le tableau montre la *progression* | ADR-0014, ADR-0011 §Réconciliation |
| Le responsable corrige une note directement | Il passe par le **canal audité** ; il ne verrouille pas | ADR-0013, #274 |
| Éditer une grille après lancement modifie l'épreuve en cours | La définition est **figée** au lancement | ADR-0015 |
| Une étape « Finaliser la configuration » | Les transitions techniques sont **invisibles** | #185, NFR-23 |
| Comparer les évaluateurs par moyenne globale | Comparaison **intra-station** uniquement | ADR-0021 D2 |
| Un verrou pessimiste sur le matériel partagé | Verrou **optimiste** ; la présence n'est qu'une courtoisie | ADR-0019 D1/D2/D3 |

---

## 11. Ce que ce document implique pour la suite

Par ordre d'effet sur la couverture, à titre indicatif :

1. **Raccorder les douze cas 🔶** — c'est du frontend sur des services déjà gardés. Le plus gros
   gain de couverture par unité d'effort, et cela rend la **co-responsabilité utilisable** pour
   la première fois (UC-12).
2. **La ligne de pré-vol du barème** (§6.1) — un raccordement, une demi-heure.
3. **`@Version`** (ADR-0019, UC-32) — à faire **juste après** le point 1 : c'est le point 1 qui
   rend le conflit d'écriture possible.
4. **Arbitrer par ADR les six manques du §6** — publication (6.2), procès-verbal (6.3), lieu
   (6.4), anonymisation (6.5), sauvegarde (6.6), amorçage (6.7). Décider, même « non retenu »,
   vaut mieux qu'omettre.
5. **UC-74 puis UC-78 → UC-81** — dans cet ordre, ADR-0021 D4 est explicite.
