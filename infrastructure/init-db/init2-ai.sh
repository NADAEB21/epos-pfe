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
# Périmètre VOLONTAIREMENT réduit : la base ai_db + le rôle ai_reader.
# Les vues v_ai_* et leurs GRANT vivent dans les migrations Flyway des
# services propriétaires (scoring V20, exam V10) — sur un volume neuf les
# tables n'existent pas encore au moment où ce script tourne.
# =============================================================
set -euo pipefail

: "${AI_READER_PASSWORD:?AI_READER_PASSWORD manquant — le renseigner dans infrastructure/.env}"
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

echo "init2-ai: ai_db + ai_reader prêts (vues : scoring V20 / exam V10 au démarrage des services)."
