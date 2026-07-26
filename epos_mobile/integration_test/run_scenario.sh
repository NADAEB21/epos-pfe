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
#
# ⛔ 2026-07-21 — CETTE FIXTURE EST MORTE POUR MOITIÉ. La table `exam_db.examens`
# a été vidée : l'examen 2 N'EXISTE PLUS (0 examen, 0 station côté exam-service).
# Les rotations 141-156 subsistent dans scoring_db, mais en ORPHELINES (#249) :
# tout scénario qui s'appuie dessus teste désormais une session fantôme, pas un
# examen. Les scénarios historiques ci-dessous sont donc à considérer comme
# NON FIABLES tant qu'ils n'ont pas été repointés.
# La fixture vivante est l'**examen 33** (voir restore_exam33 / scénario
# `groupe-suivant`), reconstruite via l'API le 2026-07-21.
EXAM_SERVICE_STOPPED=0

# Restore par défaut (fixture historique). Les scénarios qui touchent une autre
# fixture posent RESTORE_FN pour ne pas écrire dans un jeu de données qui n'est
# pas le leur — sans quoi le trap EXIT « restaure » l'examen 2 après un test qui
# n'y a jamais touché, et masque l'état réel.
RESTORE_FN=restore_legacy

restore_legacy() {
  echo ">>> RESTORE (fixture historique — exam 2 / lot 28)"
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
    UPDATE examen_participations SET note=NULL, commentaire=NULL WHERE etudiant_id IN (19,22,23,27);
    DELETE FROM exam_item_snapshot;
    DELETE FROM exam_station_snapshot;"
  $PSQL_EXAM "UPDATE examens SET statut='EN_COURS' WHERE id=2;"   # launched_at NON touché
  echo ">>> verify:"
  $PSQL "SELECT id, debut_creneau, statut FROM rotation WHERE id IN (141,148,151,154) ORDER BY id;"
  # ⚠️ Surface de nettoyage AJOUTÉE en session 22 : les scénarios ADR-0015
  # matérialisent des lignes de snapshot DURABLES (write-once). Les laisser en
  # place fait passer le scénario suivant pour « déjà chaud » et masque
  # justement la fenêtre d'avant-première-matérialisation qu'on veut tester.
  $PSQL "SELECT '    snapshot restant: '||(SELECT count(*) FROM exam_station_snapshot)||' station(s), '
                ||(SELECT count(*) FROM exam_item_snapshot)||' item(s)';"
}

# ── fixture VIVANTE — examen 35 « exam test 2 » (construit PAR NADA le 2026-07-23 ;
# l'examen 33 du harnais précédent a été balayé par sa reconstruction).
# Stations 62 (ev6 = eval2@epos.tn) et 63 (ev3). Durée de station : 2 min.
# ⚠️ Le cas-piège du carré latin (groupe 2 au rang 1) est ici sur la station 63
# (ev3) alors que la session Chrome persistée est ev2 → le scénario pilote la
# station 62 (ordre 1 puis 2). Le piège reste épinglé par le test unitaire
# `groupeSuivant_drapeauIndependantDuNumeroDeGroupe` ; le rôle du E2E est la
# NAVIGATION (POST avancer), l'écran intact, et l'ANCRE du minuteur (#209).
restore_exam35() {
  echo ">>> RESTORE (exam 35 — état laissé par Nada : tout TERMINE)"
  $PSQL "UPDATE rotation SET statut='TERMINE' WHERE id IN (229,230,231,232);"
  echo ">>> verify:"
  $PSQL "SELECT '    rot '||id||' -> '||statut||' debut_reel '||coalesce(debut_reel::text,'NULL')
         FROM rotation WHERE id BETWEEN 229 AND 232 ORDER BY id;"
}

trap 'eval "$RESTORE_FN"' EXIT

# ── fixtures par scénario ───────────────────────────────────────────────────
# NB: toujours via (now() AT TIME ZONE 'Africa/Tunis') — le Clock backend est
# épinglé Africa/Tunis (UTC+1) alors que l'hôte dev est UTC+2. Utiliser l'heure
# murale de l'hôte produit un faux 'A_VENIR'.
#
# ⚠️ #207 — DEUX RÉGLAGES DISTINCTS, ne pas les confondre :
#   • statut='EN_COURS'  → rend la session JOIGNABLE. C'est désormais la SEULE
#     chose qui l'ouvre : le dashboard lit l'état stocké et ne déduit plus rien
#     de l'heure. Avant #207, poser debut_creneau dans la fenêtre suffisait.
#   • debut_creneau      → ancre le COMPTE À REBOURS (le PLANCHER). Toujours
#     nécessaire, et c'est ce que les assertions de minuteur mesurent.
# Poser l'un sans l'autre donne un faux échec : bon minuteur / session fermée,
# ou session ouverte / minuteur ancré n'importe où.
case "$SCENARIO" in
  S2-1-derive)   # ⛔ OBSOLÈTE depuis #207 — l'impasse « A » ne peut plus se produire.
    # Ce scénario reproduisait la dérive au-delà de duree+GRACE, qui retirait la
    # session toute seule. Ce plafond est supprimé : une rotation EN_COURS le
    # reste quelle que soit l'heure (assertion backend
    # EvaluateurDashboardServiceTest#statut_enCours_survitALaDerive). Laissé pour
    # la trace ; sa cible dead_end_test.dart n'a de toute façon jamais été écrite.
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '46 minutes' WHERE id=141;"
    TARGET=integration_test/dead_end_test.dart ;;
  S2-2-entre-groupes)  # groupe 1 terminé en avance, groupe 2 pas encore dû
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '5 minutes'  WHERE id=141;
           UPDATE rotation SET statut='TERMINE' WHERE id=141;
           UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') + interval '20 minutes' WHERE id=148;"
    TARGET=integration_test/dead_end_test.dart ;;
  timer-anchor)  # finding #1 — passage commencé il y a 6 min, station de 15 min
    # La session est joignable parce qu'elle est STOCKÉE EN_COURS (#207) — la
    # règle « 6 min < 45 min (duree+GRACE) » qui figurait ici n'existe plus.
    # On teste le MINUTEUR, pas l'impasse #238 : il doit rester ~9 min.
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '6 minutes'  WHERE id=141;
           UPDATE rotation SET statut='EN_COURS' WHERE id=141;   -- #207 : ce qui OUVRE la session
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
           UPDATE rotation SET statut='EN_COURS' WHERE id=141;   -- #207 : ce qui OUVRE la session
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
  grading-nominal)  # session 22 — l'écran de NOTATION, jamais piloté jusqu'ici
    # Passage commencé il y a 6 min sur une station de 15 min. La session est
    # joignable parce qu'elle est STOCKÉE EN_COURS (#207, plus de fenêtre de
    # grâce), et le minuteur doit ouvrir vers ~9 min : c'est debut_creneau, le
    # PLANCHER, qui l'ancre. Avec le `;` de #239 il ouvrait à 15:00.
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '6 minutes'  WHERE id=141;
           UPDATE rotation SET statut='EN_COURS' WHERE id=141;   -- #207 : ce qui OUVRE la session
           UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') + interval '20 minutes' WHERE id=148;"
    TARGET=integration_test/grading_screen_test.dart ;;
  grading-outage)   # session 22 — LE PAYOFF d'ADR-0015, vu depuis l'UI
    # Même ordre d'amorçage que render-audit, et cet ordre EST le test : on
    # chauffe le snapshot pendant qu'exam-service est UP, PUIS on coupe. Couper
    # avec 0 snapshot ne testerait que la fenêtre d'avant-matérialisation et
    # ferait tout dégrader — on perdrait la promesse à vérifier (« la station
    # déjà figée reste notable pendant la panne »).
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '6 minutes'  WHERE id=141;
           UPDATE rotation SET statut='EN_COURS' WHERE id=141;   -- #207 : ce qui OUVRE la session
           UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') + interval '20 minutes' WHERE id=148;"
    echo ">>> chauffage du snapshot (exam-service UP)"
    TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
      -H 'Content-Type: application/json' \
      -d '{"email":"eval@epos.tn","password":"Eval@1234"}' \
      | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
    curl -s http://localhost:8080/api/v1/evaluateur/dashboard -H "Authorization: Bearer $TOKEN" > /dev/null
    # ⚠️ `success:true` n'est PAS une sonde de disponibilité : le dashboard est
    # fail-open et répond 200 pendant une panne. On vérifie la MATÉRIALISATION.
    SNAP=$($PSQL "SELECT count(*) FROM exam_station_snapshot;" | tr -d ' ')
    echo "    snapshot: $SNAP station(s) figée(s)"
    if [ "$SNAP" = "0" ]; then
      echo "!!! ABANDON : le snapshot n'a pas chauffé — couper maintenant testerait"
      echo "    la mauvaise chose (tout dégraderait). Vérifier exam-service."
      exit 1
    fi
    echo ">>> coupure exam-service"
    docker stop epos-exam-service > /dev/null
    EXAM_SERVICE_STOPPED=1
    TARGET=integration_test/grading_screen_test.dart ;;
  grading-save)     # session 22 — LE CHEMIN D'ÉCRITURE, piloté depuis l'UI
    # Même fenêtre que grading-nominal. La différence est ce qu'on prouve :
    # non pas « l'écran s'affiche » mais « la note traverse l'UI et PERSISTE ».
    $PSQL "UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') - interval '6 minutes'  WHERE id=141;
           UPDATE rotation SET statut='EN_COURS' WHERE id=141;   -- #207 : ce qui OUVRE la session
           UPDATE rotation SET debut_creneau=(now() AT TIME ZONE 'Africa/Tunis') + interval '20 minutes' WHERE id=148;"
    VERIFIER_ECRITURE=1
    TARGET=integration_test/grading_save_test.dart ;;
  groupe-suivant)  # #248/#209 — navigation « Groupe suivant » + ancre du minuteur, depuis l'UI
    # Fixture : examen 35, station 62 (ev2). Rang 1 rouvert, minuteur VIERGE
    # (debut_reel NULL → le serveur l'horodate au premier accès : le compte à
    # rebours doit ouvrir à ~02:00 PILE, plus jamais au créneau planifié).
    RESTORE_FN=restore_exam35
    $PSQL "UPDATE rotation SET statut='EN_COURS',  debut_reel=NULL WHERE id=229;
           UPDATE rotation SET statut='EN_ATTENTE', debut_reel=NULL WHERE id=232;"
    echo ">>> état de la fixture :"
    $PSQL "SELECT '    rot '||r.id||' stn '||r.station_id||' rang '||r.ordre_passage
                  ||' groupe '||sg.numero_groupe||' -> '||r.statut
           FROM rotation r JOIN student_group sg ON r.student_group_id=sg.id
           WHERE r.id IN (229,232) ORDER BY r.id;"
    TARGET=integration_test/groupe_suivant_test.dart ;;
  smoke)
    TARGET=integration_test/smoke_test.dart ;;
  *)
    echo "usage: run_scenario.sh {smoke|S1-nominal|S2-1-derive|S2-2-entre-groupes|timer-anchor|render-audit|grading-nominal|grading-outage|grading-save|groupe-suivant}"; exit 1 ;;
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
DRIVE_RC=$?

# ── vérification de PERSISTANCE, avant le restore ───────────────────────────
# Le test Dart tourne dans Chrome : il ne peut pas interroger la base. Il prouve
# que l'UI a accepté la saisie ; c'est ICI qu'on prouve qu'elle a persisté — et
# obligatoirement AVANT le trap EXIT, qui efface justement ces notations.
if [ "${VERIFIER_ECRITURE:-0}" = "1" ]; then
  echo ">>> PERSISTANCE (rotation 141, avant restore)"
  $PSQL "
    SELECT '    notations: '||count(*) FROM notations n
      JOIN rotation_assignment ra ON ra.id=n.assignment_id WHERE ra.rotation_id=141;
    SELECT '    items notés: '||count(*) FROM notation_items ni
      JOIN notations n ON n.id=ni.notation_id
      JOIN rotation_assignment ra ON ra.id=n.assignment_id WHERE ra.rotation_id=141;"
  echo "    détail (etudiant | score_final | verrouillee) :"
  $PSQL "
    SELECT '    '||ra.participation_id||' | '||coalesce(n.score_final::text,'NULL')
           ||' | '||coalesce(n.verouillee::text,'NULL')
    FROM notations n JOIN rotation_assignment ra ON ra.id=n.assignment_id
    WHERE ra.rotation_id=141 ORDER BY ra.participation_id;"
  echo "    valeurs saisies :"
  $PSQL "
    SELECT '    item '||ni.item_id||' = '||ni.valeur
    FROM notation_items ni JOIN notations n ON n.id=ni.notation_id
    JOIN rotation_assignment ra ON ra.id=n.assignment_id
    WHERE ra.rotation_id=141 ORDER BY ni.item_id;"
fi
exit ${DRIVE_RC:-0}
