# ADR-0029 — Le socle du module IA/BI : Python lecture-seule derrière le gateway

**Statut : Proposé (2026-08-21) — l'acceptation est le merge de cette PR par Nada.**
**Décideuse : Nada. Préparé en S43, sur le plan IA/BI de S42 (réunion Nada–Feten du 21/08).**
**Exécute : ADR-0008 (pivot psychométrie, accepté) + ADR-0021 (D1–D10). Clôt les deux points
que ADR-0021 laissait explicitement ouverts : où tourne le calcul, et la forme du modèle de
lecture. Épic GitHub : #352–#368.**

## Contexte

ADR-0021 a défini QUOI calculer (indices avec incertitude, contrat de refus, sévérité
intra-station) et QUOI en faire (proposer, jamais appliquer — D10), mais pas OÙ ni COMMENT.
Les verrous qu'il posait sont levés : le prédicat de matière existe dans scoring (#274),
l'attribution des notes existe (`saisi_par`, V15), le barème atteignable est gaté au
lancement (#277), le P0 produit est vide. Contraintes non négociables héritées du terrain :
le poste facultaire tourne **hors ligne sur LAN** (déploiement prouvé 2026-07-23), la
soutenance est le 2026-09-01, et le cœur (exam/scoring) est **gelé** — le module ne doit
pouvoir ni le bloquer ni le corrompre.

## Décision

### D1 — Runtime : Python 3.12 + FastAPI, service `ai-service` (port 8084)

Dépendances **minimales et épinglées par lockfile** : `fastapi`, `uvicorn`, `psycopg`,
`numpy`, `pandas`, `scipy`. Les indices d'ADR-0008 s'écrivent **à la main** et se testent
contre un calcul manuel (fixtures F3) — devant le jury, « voici la formule, voici notre
implémentation vérifiée au tableur » vaut plus qu'un import opaque.

Écartés, une fois pour toutes :
- **XGBoost / BART-T5 / tout LLM** — déjà écartés par ADR-0008 (affamés de données,
  indéfendables sur du synthétique) ; et aucun appel réseau sortant au runtime n'est
  possible (invariant hors-ligne). L'anomalie non supervisée reste un *stretch*, jamais un
  livrable de soutenance.
- **Calculer dans scoring (Java)** — ADR-0021 laissait la question ouverte. Python gagne :
  l'écosystème stats est la norme du domaine (défendable), l'isolement de panne (le module
  peut mourir sans toucher le cœur — miroir d'ADR-0015 : l'onglet Analyses se dégrade, la
  notation continue), et le module IA est un livrable distinct attendu du PFE.

### D2 — Plan de données : rôle PostgreSQL `ai_reader`, LECTURE SEULE, double GRANT

Un rôle `ai_reader` (SELECT uniquement, `default_transaction_read_only=on`,
`statement_timeout` 5 s) sur **scoring_db ET exam_db** — même instance, un rôle, deux GRANT.

Le double GRANT n'est pas un confort : **vérifié en base (S42), `exam_item_snapshot` ne
porte ni `valeur_max` ni `libelle`**, or la difficulté d'un critère numérique est
`moyenne(valeur)/valeur_max` — scoring_db seule ne suffit pas. L'alternative « étendre le
snapshot » toucherait le chemin d'écriture gelé de scoring pour un besoin de lecture :
refusée. Précédent : les FK logiques inter-services d'ADR-0006.

Le calcul ne porte que sur des examens **CLOS** (v1) — aucune contention avec un examen en
cours, le timeout en ceinture.

**C'est la propriété de robustesse n°1 : un module qui ne peut pas écrire ne peut pas
corrompre le cœur gelé.** La preuve (un INSERT/UPDATE via `ai_reader` échoue) fait partie
des critères de #353.

### D3 — Modèle de lecture : cache `ai_db`, clé `(examen_id, hash_des_entrées)`

Les indices d'un examen clos sont calculés une fois puis mis en cache dans `ai_db` (la base
du module). Le hash des entrées donne deux garanties : recalcul automatique si les données
bougent (réajustement ADR-0013), et **reproductibilité** — une suggestion affichée est
rejouable devant le jury. Jamais de re-calcul par requête sur le chemin chaud. C'est le
« read model » qu'ADR-0021 §Conséquences exigeait sans le dessiner.

`ai_db` porte aussi le **journal de suggestions** (entrées hashées, indices, proposition,
effet projeté, décision accepter/refuser) : un cache est reconstructible, ce journal est
une **trace d'audit** — il rejoint le drill de sauvegarde W9 (#366).

### D4 — Exposition : route gateway STATIQUE, périmètres hérités de scoring

`/api/v1/ai/**` → `http://ai-service:8084` en route **statique** : pas de client Eureka
Python (une dépendance de moins, le DNS compose est stable, le gateway reste l'unique
porte — aucun mapping de port hôte pour :8084). Le JWT est validé au gateway comme
aujourd'hui ; `ai-service` lit `X-User-Authorities` et applique les règles de scoring :
un responsable ne voit que SA matière (#274), le SUPER_ADMIN voit **agrégé d'abord**
(ADR-0021 D5 — jamais de vue par étudiant hors matière via l'analytics), l'évaluateur n'a
pas d'accès v1.

### D5 — BI dans l'application : Angular + ngx-echarts, pas d'outil externe

Metabase/Superset écartés : 1–2 Go de RAM sur le poste facultaire, une deuxième
authentification où notre périmètre matière n'existe pas (le tableau de bord deviendrait
la plus grande fuite de données de la plateforme — l'avertissement littéral d'ADR-0021 D5),
et deux produits devant le jury au lieu d'un. Le BI est la face transversale des trois
étages : mêmes agrégats, mêmes endpoints, mêmes droits, hors ligne par construction.

### D6 — Le texte : gabarits FR déterministes, jamais du génératif

Les lectures (« Ce critère n'a séparé personne ») et les refus (« comparaison non
concluante — effectif insuffisant ») sont des **gabarits pilotés par seuils**. Un module
qui parle à un jury de pharmacie ne doit jamais pouvoir halluciner. Le contrat de refus
d'ADR-0021 D2 fait partie du texte : sous les effectifs minimaux, l'interface dit
*pourquoi* elle ne conclut pas.

### D7 — Exploitation

Image `python:3.12-slim` (~250 Mo), healthcheck HTTP, `mem_limit 512m`, CPU only,
démarrage < 5 s — budget tenu pour le poste facultaire ET l'AWS t3. Panne d'`ai-service` →
l'onglet Analyses affiche un état dégradé **nominatif** ; l'écran de délibération (étage A,
servi par scoring — ADR-0021 D4) reste utilisable. **Fail-soft en lecture, jamais de
fail-open** : pas de repli qui fabrique des données (leçon du 403 avalé). CI Python aux
règles maison (actions épinglées SHA, permissions minimales, lockfile).

## Conséquences

- #352 (scaffold), #353 (rôle + vues), #359 (cache + endpoints B), #365 (BI), #366 (CI +
  sauvegarde) implémentent cet ADR ; leurs critères d'acceptation en sont les preuves.
- ADR-0021 passe **Proposed → Accepted** avec cette PR : ses deux « non décidés » sont clos
  (le runtime ici, le barème versionné dans ADR-0030).
- Le mobile n'est pas touché : la v1 du module est responsable-web (l'évaluateur n'est pas
  son utilisateur).

## Explicitement hors périmètre (pour ne pas re-débattre)

Prédiction (réussite future) — pas de données, pas de légitimité · application automatique
d'un barème — interdite par D10, pour toujours · entrepôt de données / ETL — surdimensionnés
à l'échelle facultaire · rang public des évaluateurs — ADR-0021 D2/D3 : signal de
délibération, jamais un palmarès.
