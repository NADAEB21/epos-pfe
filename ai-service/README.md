# ai-service — module IA/BI (psychométrie & délibération)

Socle posé par #352/#353 sur **ADR-0029** (runtime, plan de données, exposition)
et **ADR-0021** (ce que le module calcule et propose). Épic : #352–#368.

## Ce que le socle contient (et rien de plus)

- `GET /ai/health` — sonde de santé (healthcheck compose).
- `GET /ai/examens/{id}/indices` — **la garde seulement** : périmètre matière
  identique à scoring (#274), calcul livré par #359 (répond 501 honnête d'ici là).
- Parsing de `X-User-Authorities` (contrat gateway ADR-0005) + garde matière
  résolue sur `v_ai_exam_matiere` (le snapshot #274 de scoring).

## Contrat de sécurité

- **Aucun port hôte** : le service n'est joignable qu'à travers le gateway
  (`/api/v1/ai/**`, route statique), qui valide le JWT et propage l'identité.
- **Lecture seule structurelle** : les accès scoring_db/exam_db passent par le
  rôle `ai_reader` (SELECT sur des vues nommées `v_ai_*`, transactions
  read-only, `statement_timeout` 5 s). Le module **ne peut pas** écrire dans le
  cœur — c'est prouvé par test live, pas promis.

## Les vues `v_ai_*`

Elles vivent dans les migrations Flyway du service PROPRIÉTAIRE des tables
(scoring `V20__ai_read_views.sql`, exam `V10__ai_read_views.sql`) : une
migration future qui casse une vue échoue **fort** dans le service qui l'a
cassée, au lieu de pourrir silencieusement côté IA. Les **GRANT** (Postgres
pur — `DO $$`, `pg_roles`) sont dans le dossier `{vendor}` de chaque service
(`db/vendor/postgresql/` : scoring V21, exam V11 — hors de `db/migration`,
que Flyway scanne récursivement) que le H2 des tests ne voit jamais. Postgres ne joint pas entre bases : les croisements scoring×exam
se font ici, en Python.

## Amorçage d'une installation

1. `infrastructure/init-db/init2-ai.sh` crée `ai_db` + le rôle `ai_reader`
   (automatique sur volume NEUF ; à rejouer À LA MAIN sur un volume existant —
   voir l'en-tête du script). `AI_READER_PASSWORD` vient de `infrastructure/.env`.
2. Les migrations posent vues (V20 scoring / V10 exam) + GRANT (V21 / V11,
   dossier postgresql) au premier démarrage des services — le GRANT échoue
   avec un message clair si le rôle n'existe pas encore (l'ordre 1 → 2 est
   obligatoire).

## Dev local

```powershell
cd ai-service
python -m venv .venv ; .\.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
pytest            # tests unitaires (aucune DB requise)
```
