#!/bin/bash
# Orchestrateur de scénarios E2E évaluateur.
#   bash integration_test/run_scenario.sh <scenario>
#
# Un test Dart tournant dans Chrome ne peut pas piloter la base : la fixture est
# donc posée ICI (psql), les assertions UI dans integration_test/*.dart.
#
# ⚠️ Toujours exécuté avec restore en sortie, même en cas d'échec (trap EXIT).
set -uo pipefail
PSQL="docker exec epos-postgres psql -U admin -d scoring_db -qtc"
PSQL_EXAM="docker exec epos-postgres psql -U admin -d exam_db -qtc"
SCENARIO="${1:-}"

# ── fixture d'origine — lot 28 / exam 2 / eval3 station 5 ────────────────────
EXAM_SERVICE_STOPPED=0
restore() {
  echo ">>> RESTORE"
  if [ "$EXAM_SERVICE_STOPPED" = "1" ]; then
    echo "    relance exam-service"
    docker start epos-exam-service > /dev/null
  fi
  $PSQL "
    UPDATE rotation SET debut_creneau='2026-07-15 06:02:48' WHERE id IN (141,142,143,144);
    UPDATE rotation SET debut_creneau='2026-07-15 06:17:48' WHERE id IN (145,146,147,148);
    UPDATE rotation SET debut_creneau='2026-07-15 06:32:48' WHERE id IN (149,150,151,152);
    UPDATE rotation SET debut_creneau='2026-07-15 06:47:48' WHERE id IN (153,154,155,156);
    UPDATE rotation SET statut='EN_ATTENTE' WHERE id BETWEEN 141 AND 156;
    DELETE FROM notation_items WHERE notation_id IN (
      SELECT n.id FROM notations n JOIN rotation_assignment ra ON ra.id=n.assignment_id
      WHERE ra.rotation_id BETWEEN 141 AND 156);
    DELETE FROM notations WHERE assignment_id IN (
      SELECT id FROM rotation_assignment WHERE rotation_id BETWEEN 141 AND 156);
    UPDATE lot SET statut='EN_COURS' WHERE id=28;
    UPDATE lot SET statut='EN_ATTENTE' WHERE id=29;
    UPDATE examen_participations SET note=NULL, commentaire=NULL WHERE etudiant_id IN (19,22,23,27);"
  $PSQL_EXAM "UPDATE examens SET statut='EN_COURS' WHERE id=2;"   # launched_at NON touché
  echo ">>> verify:"
  $PSQL "SELECT id, debut_creneau, statut FROM rotation WHERE id IN (141,148,151,154) ORDER BY id;"
}
trap restore EXIT

# ── fixtures par scénario ───────────────────────────────────────────────────
# NB: toujours via (now() AT TIME ZONE 'Africa/Tunis') — le Clock backend est
# épinglé Africa/Tunis (UTC+1) alors que l'hôte dev est UTC+2. Utiliser l'heure
# murale de l'hôte produit un faux 'A_VENIR'.
case "$SCENARIO" in
  S2-1-derive)   # fenêtre dépassée, rotation toujours EN_ATTENTE
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '46 minutes' WHERE id=141;"
    TARGET=integration_test/dead_end_test.dart ;;
  S2-2-entre-groupes)  # groupe 1 terminé en avance, groupe 2 pas encore dû
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '5 minutes'  WHERE id=141;
           UPDATE rotation SET statut='TERMINE' WHERE id=141;
           UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') + interval '20 minutes' WHERE id=148;"
    TARGET=integration_test/dead_end_test.dart ;;
  timer-anchor)  # finding #1 — passage commencé il y a 6 min, station de 15 min
    # 6 min < 45 min (duree+GRACE) ⇒ la session reste EN_COURS et joignable :
    # on teste le MINUTEUR, pas l'impasse #238. Il doit rester ~9 min.
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '6 minutes'  WHERE id=141;
           UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') + interval '20 minutes' WHERE id=148;"
    TARGET=integration_test/timer_anchor_test.dart ;;
  S1-nominal)    # une session réellement en cours
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '2 minutes'  WHERE id=141;
           UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') + interval '15 minutes' WHERE id=148;"
    TARGET=integration_test/nominal_test.dart ;;
  render-audit)  # session 21 — que voit VRAIMENT l'évaluateur pendant une panne exam-service ?
    # Une session dans la fenêtre, puis : on CHAUFFE le snapshot ADR-0015 (dashboard
    # appelé pendant qu'exam-service est UP → station 5 figée), et SEULEMENT ensuite
    # on coupe exam-service. C'est le seul ordre qui teste la promesse de l'ADR
    # (« après le premier succès, une panne est sans effet ») plutôt que la fenêtre
    # étroite d'avant-première-matérialisation.
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '6 minutes'  WHERE id=141;
           UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') + interval '20 minutes' WHERE id=148;"
    echo ">>> chauffage du snapshot (exam-service UP)"
    TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
      -H 'Content-Type: application/json' \
      -d '{"email":"eval@epos.tn","password":"Eval@1234"}' \
      | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
    curl -s http://localhost:8080/api/v1/evaluateur/dashboard -H "Authorization: Bearer $TOKEN" > /dev/null
    docker exec epos-postgres psql -U admin -d scoring_db -qtc \
      "SELECT '    snapshot: '||count(*)||' station(s) figée(s)' FROM exam_station_snapshot;"
    echo ">>> coupure exam-service"
    docker stop epos-exam-service > /dev/null
    EXAM_SERVICE_STOPPED=1
    TARGET=integration_test/render_audit_test.dart ;;
  smoke)
    TARGET=integration_test/smoke_test.dart ;;
  *)
    echo "usage: run_scenario.sh {smoke|S1-nominal|S2-1-derive|S2-2-entre-groupes|timer-anchor}"; exit 1 ;;
esac

echo ">>> fixture posée pour $SCENARIO ; lancement de $TARGET"
# ⚠️ --web-port=4300 est OBLIGATOIRE, pas cosmétique.
# `flutter drive` choisit sinon un port ALÉATOIRE, et la passerelle n'autorise
# que 4200/4300 (docker-compose.yml:88 CORS_ALLOWED_ORIGINS). Sur tout autre
# port, le login échoue en CORS et l'app reste sur l'écran de connexion — ce
# qui ressemble à un bug applicatif et n'en est pas (même piège qu'en session 18
# avec le port 4400). Le smoke test ne l'avait pas révélé : il ne fait aucun
# appel authentifié.
flutter drive --driver=test_driver/integration_test.dart --target="$TARGET" -d chrome --web-port=4300
