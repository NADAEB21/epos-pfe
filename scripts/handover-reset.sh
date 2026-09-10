#!/usr/bin/env bash
# Remise à zéro de la pile cloud avant la PASSATION à la faculté.
#
# Résultat : une base VIERGE — aucun examen, aucune station, aucun étudiant,
# aucune note, aucun compte de test — avec UN SEUL compte, SUPER_ADMIN, dont
# l'adresse est passée en argument. Le catalogue des matières (référentiel
# d'init.sql) est conservé : il est nécessaire pour créer les responsables.
#
# À exécuter SUR la VM (ou via ssh), là où tourne la pile :
#   bash /opt/epos/app/scripts/handover-reset.sh --admin-email eposfphm@gmail.com --yes
#   bash /opt/epos/app/scripts/handover-reset.sh --admin-email ... --admin-password 'Xxxx1234' --yes
#
# Sans --admin-password, le compte est créé SANS mot de passe et reçoit le
# courriel « choisissez votre mot de passe » (#389, valable 7 jours) : la
# faculté choisit elle-même son mot de passe, personne d'autre ne le connaît.
# Ce mode exige MAIL_ENABLED=true dans /opt/epos/epos.env (vérifié AVANT
# toute destruction).
#
# Ce que fait le script, dans l'ordre :
#   1. sauvegarde des quatre bases dans /opt/epos/backups/handover-<horodatage>/
#      (format custom pg_dump, restaurable par infrastructure/sauvegarde/restore-epos.ps1
#      ou son portage bash) — la remise à zéro reste donc réversible ;
#   2. arrêt de la pile, suppression du SEUL volume Postgres (les volumes Caddy
#      — certificat TLS — sont conservés), redémarrage : init.sql rejoue le
#      schéma auth + les comptes de test + les matières, Flyway recrée
#      exam_db / scoring_db vides, init2-ai.sh recrée ai_db ;
#   3. création du compte de passation via l'API publique (mêmes gardes qu'en
#      production) en s'authentifiant avec le compte de test admin@epos.tn ;
#   4. suppression des trois comptes de test d'init.sql (rôles, jetons, puis
#      utilisateurs) ; le journal d'audit ne référence pas users et survit ;
#   5. vérification : exactement 1 utilisateur, 1 rôle SUPER_ADMIN, 0 examen,
#      0 station, 0 étudiant, 0 notation, matières > 0. Sortie 1 sinon.
set -euo pipefail

ADMIN_EMAIL=""; ADMIN_PASSWORD=""; YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --admin-email)    ADMIN_EMAIL="$2"; shift 2 ;;
    --admin-password) ADMIN_PASSWORD="$2"; shift 2 ;;
    --yes)            YES=1; shift ;;
    *) echo "argument inconnu : $1" >&2; exit 2 ;;
  esac
done
[ -n "$ADMIN_EMAIL" ] || { echo "--admin-email est obligatoire" >&2; exit 2; }

ENV=/opt/epos/epos.env
APP=/opt/epos/app/infrastructure
[ -r "$ENV" ] || sudo test -r "$ENV" || { echo "$ENV introuvable : cette machine n'est pas une VM EPOS amorcée" >&2; exit 1; }
envval() { sudo grep -E "^$1=" "$ENV" | head -1 | cut -d= -f2-; }
PGU=$(envval POSTGRES_USER)
DOMAIN=$(envval EPOS_DOMAIN)
MAIL_ON=$(envval MAIL_ENABLED)
BASE="https://$DOMAIN/api/v1"
COMPOSE=(sudo docker compose --env-file "$ENV" -f "$APP/docker-compose.prod.yml")
PSQL() { sudo docker exec epos-postgres psql -U "$PGU" -d "$1" -v ON_ERROR_STOP=1 -tA -c "$2"; }

if [ -z "$ADMIN_PASSWORD" ] && [ "$MAIL_ON" != "true" ]; then
  echo "Sans --admin-password, le compte est créé par invitation e-mail, or MAIL_ENABLED=$MAIL_ON." >&2
  echo "Activer la messagerie dans $ENV ou fournir --admin-password. Rien n'a été touché." >&2
  exit 1
fi

echo "Cette opération EFFACE toutes les données de https://$DOMAIN et ne laisse que"
echo "le compte SUPER_ADMIN $ADMIN_EMAIL. Une sauvegarde est faite d'abord."
if [ "$YES" -ne 1 ]; then
  echo "Relancer avec --yes pour exécuter. Rien n'a été touché."
  exit 0
fi

# 1. Sauvegarde préalable ------------------------------------------------------
STAMP=$(date +%Y-%m-%d_%H%M%S)
DEST=/opt/epos/backups/handover-$STAMP
sudo mkdir -p "$DEST"
for db in auth_db exam_db scoring_db ai_db; do
  sudo docker exec epos-postgres pg_dump -U "$PGU" -Fc --no-owner -f "/tmp/hb_$db.dump" "$db"
  sudo docker cp "epos-postgres:/tmp/hb_$db.dump" "$DEST/$db.dump"
  sudo docker exec epos-postgres rm -f "/tmp/hb_$db.dump"
  size=$(sudo stat -c %s "$DEST/$db.dump")
  [ "$size" -ge 1024 ] || { echo "sauvegarde suspecte pour $db ($size octets) — abandon AVANT destruction" >&2; exit 1; }
  printf '  sauvegarde %-11s %10s octets\n' "$db" "$size"
done
echo "==> sauvegarde complète : $DEST"

# 2. Volume Postgres neuf -------------------------------------------------------
echo "==> arrêt de la pile"
"${COMPOSE[@]}" down
VOL=$(sudo docker volume ls --format '{{.Name}}' | grep -E '(^|_)postgres_data$' | head -1)
[ -n "$VOL" ] || { echo "volume postgres introuvable" >&2; exit 1; }
echo "==> suppression du volume $VOL (les volumes Caddy sont conservés)"
sudo docker volume rm "$VOL" >/dev/null
echo "==> redémarrage (init.sql + Flyway sur base vierge, ~2 min)"
"${COMPOSE[@]}" up -d
SERVICES=(epos-postgres epos-discovery-server epos-api-gateway epos-auth-service epos-exam-service epos-scoring-service epos-ai-service)
for i in $(seq 1 60); do
  sleep 5
  ok=0
  for s in "${SERVICES[@]}"; do
    [ "$(sudo docker inspect --format '{{.State.Health.Status}}' "$s" 2>/dev/null)" = healthy ] && ok=$((ok+1))
  done
  [ "$ok" -eq "${#SERVICES[@]}" ] && break
done
[ "$ok" -eq "${#SERVICES[@]}" ] || { echo "services sains : $ok/${#SERVICES[@]} après 5 min — voir docker ps" >&2; exit 1; }
echo "==> ${#SERVICES[@]} services sains"

# 3. Compte de passation via l'API ---------------------------------------------
# Le gateway met ~1 min à voir auth-service dans Eureka après un redémarrage.
TOKEN=""
for i in $(seq 1 24); do
  TOKEN=$(curl -s --max-time 15 -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d '{"email":"admin@epos.tn","password":"Admin@1234"}' \
    | python3 -c 'import sys,json
try: print(json.load(sys.stdin)["data"]["accessToken"])
except Exception: print("")' 2>/dev/null || true)
  [ -n "$TOKEN" ] && break
  sleep 5
done
[ -n "$TOKEN" ] || { echo "impossible de se connecter avec le compte d'amorçage via $BASE" >&2; exit 1; }

BODY=$(python3 - "$ADMIN_EMAIL" "$ADMIN_PASSWORD" <<'PY'
import json, sys
email, pw = sys.argv[1], sys.argv[2]
d = {"email": email, "nom": "Administration", "prenom": "EPOS", "roles": [{"role": "SUPER_ADMIN"}]}
if pw: d["password"] = pw
print(json.dumps(d))
PY
)
RESP=$(curl -s --max-time 40 -w '\n%{http_code}' -X POST "$BASE/users" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$BODY")
CODE=${RESP##*$'\n'}
[ "$CODE" = 201 ] || { echo "création du compte refusée (HTTP $CODE) : ${RESP%$'\n'*}" >&2; exit 1; }
echo "==> compte $ADMIN_EMAIL créé (SUPER_ADMIN)"
if [ -z "$ADMIN_PASSWORD" ]; then
  echo "    invitation : $(printf '%s' "${RESP%$'\n'*}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["invitation"])')"
fi

# 4. Suppression des comptes de test d'init.sql --------------------------------
SEED="('admin@epos.tn','resp@epos.tn','eval@epos.tn')"
PSQL auth_db "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE email IN $SEED);" >/dev/null
PSQL auth_db "DELETE FROM password_reset_tokens WHERE user_id IN (SELECT id FROM users WHERE email IN $SEED);" >/dev/null
PSQL auth_db "DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email IN $SEED);" >/dev/null
PSQL auth_db "DELETE FROM users WHERE email IN $SEED;" >/dev/null
echo "==> comptes de test supprimés"

# 5. Vérification ---------------------------------------------------------------
fail=0
check() { # libellé, valeur, attendu
  if [ "$2" = "$3" ]; then printf '  ok   %-28s %s\n' "$1" "$2"; else printf '  ÉCHEC %-28s %s (attendu %s)\n' "$1" "$2" "$3"; fail=1; fi
}
check "utilisateurs"        "$(PSQL auth_db 'SELECT count(*) FROM users;')" 1
check "e-mail du seul compte" "$(PSQL auth_db 'SELECT email FROM users;')" "$ADMIN_EMAIL"
check "rôles"               "$(PSQL auth_db "SELECT string_agg(role, ',') FROM user_roles;")" SUPER_ADMIN
check "examens"             "$(PSQL exam_db 'SELECT count(*) FROM examens;')" 0
check "stations"            "$(PSQL exam_db 'SELECT count(*) FROM stations;')" 0
check "étudiants"           "$(PSQL scoring_db 'SELECT count(*) FROM etudiants;')" 0
check "notations"           "$(PSQL scoring_db 'SELECT count(*) FROM notations;')" 0
m=$(PSQL auth_db 'SELECT count(*) FROM matieres;')
if [ "$m" -ge 1 ]; then printf '  ok   %-28s %s\n' "matières (référentiel)" "$m"; else echo "  ÉCHEC matières : $m"; fail=1; fi
if [ "$fail" -ne 0 ]; then
  echo "Remise à zéro terminée AVEC ÉCARTS — la sauvegarde $DEST permet de revenir en arrière." >&2
  exit 1
fi
echo
echo "Remise à zéro terminée : https://$DOMAIN ne contient plus que le compte $ADMIN_EMAIL."
echo "Sauvegarde de l'état précédent : $DEST"
