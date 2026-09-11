#!/bin/bash
# =============================================================
# EPOS — amorçage du plan de données IA (#353, ADR-0029 D2)
#
# Sur un volume NEUF : exécuté automatiquement par docker-entrypoint-initdb.d
# (après init.sql — l'ordre alphabétique fait foi).
#
# Sur un volume EXISTANT : initdb.d ne rejoue JAMAIS (même piège que
# init.sql) — exécuter À LA MAIN, une fois :
#   docker exec epos-postgres bash /docker-entrypoint-initdb.d/init2-ai.sh
# (AI_READER_PASSWORD est déjà dans l'environnement du conteneur via compose.)
#
# Idempotent : re-exécutable sans dégât.
#
# Périmètre VOLONTAIREMENT réduit : la base ai_db + les rôles ai_reader et
# ai_writer. Les vues v_ai_* et leurs GRANT vivent dans les migrations Flyway
# des services propriétaires (scoring V20/V23, exam V10/V12) — sur un volume
# neuf les tables n'existent pas encore au moment où ce script tourne.
# Le SCHÉMA d'ai_db (cache + journal), lui, est posé par ai-service (#359).
# =============================================================
set -euo pipefail

: "${AI_READER_PASSWORD:?AI_READER_PASSWORD manquant — le renseigner dans infrastructure/.env}"
: "${AI_WRITER_PASSWORD:?AI_WRITER_PASSWORD manquant — le renseigner dans infrastructure/.env}"
: "${POSTGRES_USER:?}"

PSQL="psql -v ON_ERROR_STOP=1 -U ${POSTGRES_USER} -d postgres"

# 1. ai_db — cache des indices + journal de suggestions (schéma posé par
#    ai-service à partir de #359 ; la base doit exister avant).
if ! $PSQL -tAc "SELECT 1 FROM pg_database WHERE datname = 'ai_db'" | grep -q 1; then
    $PSQL -c "CREATE DATABASE ai_db"
fi

# 2. ai_reader — LECTURE SEULE structurelle : read-only par défaut au niveau
#    rôle, timeout serveur. Le mot de passe est realigné à chaque exécution
#    (rotation possible en changeant .env puis en rejouant ce script).
#    NB : pas d'apostrophe dans AI_READER_PASSWORD (interpolation SQL simple).
$PSQL <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_reader') THEN
        CREATE ROLE ai_reader LOGIN;
    END IF;
END \$\$;
ALTER ROLE ai_reader PASSWORD '${AI_READER_PASSWORD}';
ALTER ROLE ai_reader SET default_transaction_read_only = on;
ALTER ROLE ai_reader SET statement_timeout = '5s';
SQL

# 3. ai_writer (#359, ADR-0029 D3) — le rôle qui ÉCRIT dans ai_db (cache des
#    indices + journal), et NULLE PART AILLEURS. La propriété de robustesse
#    D2 (« un module qui ne peut pas écrire ne peut pas corrompre le cœur
#    gelé ») tient par les GRANTs : ai_writer ne reçoit AUCUN droit sur
#    scoring_db ni exam_db — pas de CONNECT, rien. Sentinelle du protocole
#    live : un SELECT d'ai_writer sur scoring_db doit ÉCHOUER.
$PSQL <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_writer') THEN
        CREATE ROLE ai_writer LOGIN;
    END IF;
END \$\$;
ALTER ROLE ai_writer PASSWORD '${AI_WRITER_PASSWORD}';
ALTER ROLE ai_writer SET statement_timeout = '5s';
GRANT CONNECT ON DATABASE ai_db TO ai_writer;
SQL

# Droits de schéma DANS ai_db : CREATE (poser les tables au premier démarrage
# d'ai-service) + USAGE. Les tables créées par ai_writer lui appartiennent.
psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d ai_db <<SQL
GRANT USAGE, CREATE ON SCHEMA public TO ai_writer;
SQL

echo "init2-ai: ai_db + ai_reader + ai_writer prêts (vues : scoring V20/V23 / exam V10/V12 au démarrage des services ; schéma ai_db posé par ai-service)."
