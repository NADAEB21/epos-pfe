"""F5/P3 (#364) — recette adversariale de l'étage C : barème de délibération.

Épic IA/BI, tâche F5 (owner Feten). Le ticket liste "Dépendances : N7, N8, N9
livrés", mais les 4 scénarios exigés n'exercent QUE l'unique porte d'écriture
livrée par N7 : POST /api/notations/examen/{id}/bareme-deliberation
(BaremeDeliberationService.creer). N8 (moteur de proposition IA) et N9 (UI web)
ne font que PRODUIRE des payloads vers cette même porte — ils n'ajoutent aucune
garde. Ce script teste donc la garde réelle, aujourd'hui, contre du code déjà
mergé — pas une anticipation de N8/N9.

Ce que ce script NE couvre PAS (à rejouer une fois N8/N9 livrés) :
  - le chemin "proposition IA acceptée -> écriture" (N8/#362)
  - le scénario UI de bout en bout (N9/#363)

Scénarios (ADR-0030 D1/D2/D5, protocole "sentinelle DB" du dépôt — voir la
description de PR de N7) :
  1. créer un barème sur un examen NON CLOS               -> 409 nominatif
  2. créer un barème HORS MATIÈRE (responsable étranger)  -> 403 nominatif
  3. DOUBLE APPLICATION du même barème (mêmes opérations) -> 409 nominatif
  4. MOTIF VIDE                                            -> 400 nominatif

Chaque refus est vérifié par (a) le message nominatif ET (b) une sentinelle
SQL directe (COUNT(*) avant/après, via `docker exec` sur postgres) — un
403/400 qui écrirait quand même ne prouverait rien (leçon S37, citée dans le
ticket). Les baselines AVANT sont rendues non vides en créant d'abord un VRAI
barème v1 valide, faute de quoi une sentinelle "vide == vide" passerait à tort.

Prérequis : `docker compose up -d` tourne, gateway sur :8080, conteneur
Postgres nommé `epos-postgres` (comme dans docker-compose.yml).

Run : python scripts/recette_f5_bareme_deliberation.py
"""
import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

# Piège S47 (scripts/README du dépôt) : sous Windows, stdout pipé bascule en
# cp1252 et le premier « é » d'un bilan tue le script APRÈS ses écritures.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080/api/v1"
PG_CONTAINER = "epos-postgres"
PG_USER = "admin"
PG_DB = "scoring_db"

# ── HTTP ──────────────────────────────────────────────────────────────────

def call(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt


def login(email, pw):
    st, r = call("POST", "/auth/login", body={"email": email, "password": pw})
    assert st == 200, f"login {email} -> {st} {r}"
    return r["accessToken"] if "accessToken" in r else r["data"]["accessToken"]


def userid_from(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["userId"]


def data(r):
    return r["data"] if isinstance(r, dict) else r


def msg(r):
    return r.get("message") if isinstance(r, dict) else r


# ── sentinelle SQL directe (pas l'API — le protocole du dépôt) ─────────────

def psql_count(sql):
    result = subprocess.run(
        ["docker", "exec", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB, "-tAc", sql],
        capture_output=True, text=True, check=True,
    )
    return int(result.stdout.strip())


def count_baremes(examen_id=None):
    if examen_id is None:
        return psql_count("SELECT COUNT(*) FROM bareme_deliberation")
    return psql_count(f"SELECT COUNT(*) FROM bareme_deliberation WHERE examen_id = {examen_id}")


def count_ops():
    return psql_count("SELECT COUNT(*) FROM bareme_deliberation_operation")


results = []

def ok(label, cond, extra=""):
    line = ("PASS  " if cond else "FAIL  ") + label + (("  — " + extra) if extra else "")
    print(line)
    results.append(cond)


def bareme_path(examen_id):
    return f"/notations/examen/{examen_id}/bareme-deliberation"


# ── construction d'un examen CLOS avec snapshot réel (préalable des tests) ─

def build_exam_clos(resp_token, eval_token, eval_uid, matiere_id, suffix):
    """Construit un examen minimal, le note, le verrouille (déclenche le
    snapshot ADR-0015 en persist-on-first-touch), puis le clôt. Retourne
    (examen_id, item_id) — item_id sert de cible EXCLURE_CRITERE."""
    st, r = call("POST", "/examens", resp_token, {
        "nom": f"F5 recette bareme {suffix}", "matiereId": matiere_id,
        "dateExamen": "2026-08-25", "heureDebut": "09:00",
        "dureeStationMin": 10, "nbEtudiantsParStation": 1})
    assert st == 201, f"create exam -> {st} {r}"
    exam_id = data(r)["id"]

    st, r = call("POST", f"/examens/{exam_id}/stations", resp_token, {
        "nom": "Station unique", "type": "PRATIQUE", "description": "",
        "evaluateurIds": [eval_uid]})
    assert st == 201, f"station -> {st} {r}"
    station_id = data(r)["id"]

    # Un seul critère BINAIRE : achievable == ponderation, pas besoin de
    # valeurMax pour qu'EXCLURE_CRITERE résolve un maximum (moteur ADR-0030).
    st, r = call("POST", f"/stations/{station_id}/grille", resp_token, {
        "nom": "Grille F5", "noteMax": 20.0, "description": "",
        "items": [{"libelle": "Geste unique", "type": "BINAIRE", "ponderation": 20.0}]})
    assert st == 201, f"grille -> {st} {r}"
    grille = data(r)
    item_id = grille["items"][0]["id"]

    st, r = call("POST", "/etudiants", resp_token, {
        "nom": "Testeur", "prenom": f"F5-{suffix}",
        "numero_inscription": f"F5-{suffix}-{int(time.time())}"})
    assert st == 201, f"etudiant -> {st} {r}"
    etudiant_id = data(r)["id"]

    st, r = call("POST", "/participations", resp_token,
                 {"examen_id": exam_id, "etudiantId": etudiant_id})
    assert st in (200, 201), f"participation -> {st} {r}"

    st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=CONFIGURE", resp_token)
    assert st == 200, f"configure -> {st} {r}"

    st, r = call("POST", f"/lots/examens/{exam_id}/repartir", resp_token)
    assert st == 201, f"repartir -> {st} {r}"
    lot_id = data(r)["details"][0]["lotId"]

    st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=EN_COURS", resp_token)
    assert st == 200, f"launch -> {st} {r}"

    st, r = call("POST", f"/lots/{lot_id}/presence-et-demarrer", resp_token, {})
    assert st == 200, f"presence-et-demarrer -> {st} {r}"

    # Note + verrouille en tant qu'évaluateur — déclenche
    # ExamDefinitionSnapshotService.resolveItems (ADR-0015), condition sine
    # qua non pour qu'une cible EXCLURE_CRITERE soit valide côté ADR-0030.
    # Le mobile récupère (et fait matérialiser) la grille AVANT de noter :
    # GET /evaluateur/stations/{id}/grille écrit exam_grille_snapshot (nom,
    # noteMax, itemsJson par STATION) — une table DISTINCTE de
    # exam_item_snapshot. assertCompletudeAvantVerrouillage(resolveItems)
    # matérialise bien exam_item_snapshot (preuve : verrouiller réussit
    # plus bas), mais PAS exam_grille_snapshot, que BaremeDeliberationEngine
    # utilise pour résoudre grille -> station. Sans cet appel, la cible
    # EXCLURE_CRITERE est refusée par ADR-0030 D2 ("station jamais notée
    # par le flux d'évaluation") — pas un problème de délai, un appel
    # manquant : rien n'est asynchrone ici.
    st, r = call("GET", f"/evaluateur/stations/{station_id}/grille", eval_token)
    assert st == 200, f"get grille station (déclenche exam_grille_snapshot) -> {st} {r}"

    st, r = call("POST", "/evaluateur/notations/saisir", eval_token, {
        "etudiantId": etudiant_id, "stationId": station_id,
        "grilleId": grille["id"], "itemId": item_id, "valeur": 1})
    assert st == 200, f"saisir notation -> {st} {r}"

    # ValiderEtudiantRequest : grilleId est @NotNull (sert à créer la Notation
    # si absente), absent=False ici (présent, noté normalement), commentaire
    # optionnel — vérifié sur le DTO réel.
    st, r = call("POST", f"/evaluateur/etudiants/{etudiant_id}/stations/{station_id}/valider",
                 eval_token, {"grilleId": grille["id"], "absent": False, "commentaire": None})
    assert st == 200, f"valider etudiant (verrouillage) -> {st} {r}"

    st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=TERMINE", resp_token)
    assert st == 200, f"close -> {st} {r}"

    return exam_id, item_id


def main():
    print("== login ==")
    admin = login("admin@epos.tn", "Admin@1234")
    resp = login("resp@epos.tn", "Resp@1234")          # RESPONSABLE_MATIERE:1
    eval_tok = login("eval@epos.tn", "Eval@1234")
    eval_uid = userid_from(eval_tok)

    print("== responsable HORS MATIÈRE (scénario 2) ==")
    resp2_email = f"resp2.f5.{int(time.time())}@epos.tn"
    st, r = call("POST", "/users", admin, {
        "email": resp2_email, "password": "Resp2@1234", "nom": "Etranger", "prenom": "Resp",
        "roles": [{"role": "RESPONSABLE_MATIERE", "matiereId": 2}]})
    assert st == 201, f"create resp2 -> {st} {r}"
    resp2 = login(resp2_email, "Resp2@1234")

    print("== examen A (matière 1) : construit, noté, verrouillé, CLOS ==")
    exam_a, item_a = build_exam_clos(resp, eval_tok, eval_uid, 1, "A")
    print(f"  exam_a = {exam_a}, item_a = {item_a}")

    print("== examen B (matière 1) : NON CLOS — pour le scénario 1 ==")
    st, r = call("POST", "/examens", resp, {
        "nom": "F5 recette — non clos", "matiereId": 1,
        "dateExamen": "2026-08-26", "heureDebut": "09:00"})
    assert st == 201, f"create exam B -> {st} {r}"
    exam_b = data(r)["id"]
    st, r = call("PATCH", f"/examens/{exam_b}/statut?statut=CONFIGURE", resp)
    assert st == 200, f"configure exam B -> {st} {r}"
    print(f"  exam_b = {exam_b} (CONFIGURE, jamais lancé)")

    ops_v1 = [{"type": "EXCLURE_CRITERE", "cibleItemId": item_a,
               "cibleStationId": None, "nouvelleEchelle": None}]

    print("\n== baseline : créer un VRAI barème v1 sur l'examen A ==")
    total_avant_global = count_baremes()
    st, r = call("POST", bareme_path(exam_a), resp,
                 {"motif": "F5 — baseline v1 pour rendre les sentinelles non triviales",
                  "operations": ops_v1})
    ok("création v1 valide -> 201", st == 201, f"got {st}: {msg(r)}")
    apres_v1_global = count_baremes()
    apres_v1_a = count_baremes(exam_a)
    ops_apres_v1 = count_ops()
    ok("sentinelle : table globale a grandi de +1 après v1",
       apres_v1_global == total_avant_global + 1,
       f"{total_avant_global} -> {apres_v1_global}")
    ok("baseline AVANT les scénarios adverses est NON VIDE (acceptance F5)",
       apres_v1_global > 0, f"total = {apres_v1_global}")

    # ── Scénario 1 — examen NON CLOS ────────────────────────────────────────
    print("\n== scénario 1 : examen NON CLOS ==")
    avant_b = count_baremes(exam_b)
    avant_global = count_baremes()
    st, r = call("POST", bareme_path(exam_b), resp,
                 {"motif": "F5 — tentative sur examen non clos", "operations": []})
    ok("refus 409 (examen non clos)", st == 409, f"got {st}")
    ok("message NOMINATIF (nomme le statut)",
       isinstance(msg(r), str) and ("CONFIGURE" in msg(r) or "clos" in msg(r).lower()),
       str(msg(r)))
    ok("sentinelle : 0 ligne écrite pour l'examen B", count_baremes(exam_b) == avant_b)
    ok("sentinelle : table globale inchangée", count_baremes() == avant_global)

    # ── Scénario 2 — HORS MATIÈRE ───────────────────────────────────────────
    print("\n== scénario 2 : responsable HORS MATIÈRE ==")
    avant_a = count_baremes(exam_a)
    avant_global = count_baremes()
    st, r = call("POST", bareme_path(exam_a), resp2,
                 {"motif": "F5 — tentative hors matière", "operations": ops_v1})
    ok("refus 403 (hors périmètre matière)", st == 403, f"got {st}")
    # ⚠️ PAS de message nominatif ici, et c'est VOULU : les trois
    # GlobalExceptionHandler génèrisent AccessDeniedException (« Access
    # denied »), comportement épinglé par leurs tests — un 403 ne détaille
    # jamais le périmètre au client. Le texte nominatif du guard #274
    # (« matière hors périmètre (matiere_id=N) ») vit dans les LOGS ; les
    # refus nominatifs voyagent en 400/409 (BusinessException), cf.
    # scénarios 1 et 3. On épingle le contrat réel pour que sa disparition
    # (un futur handler qui laisserait fuir le détail) se voie.
    ok("corps 403 générique (convention anti-fuite des 3 services)",
       msg(r) == "Access denied", str(msg(r)))
    ok("sentinelle : aucune ligne écrite pour l'examen A", count_baremes(exam_a) == avant_a)
    ok("sentinelle : table globale inchangée", count_baremes() == avant_global)

    # ── Scénario 3 — DOUBLE APPLICATION ─────────────────────────────────────
    print("\n== scénario 3 : double application (mêmes opérations que v1) ==")
    avant_a = count_baremes(exam_a)
    avant_ops = count_ops()
    avant_global = count_baremes()
    st, r = call("POST", bareme_path(exam_a), resp,
                 {"motif": "F5 — tentative de double application", "operations": ops_v1})
    ok("refus 409 (double application, ADR-0030 D5)", st == 409, f"got {st}")
    ok("message NOMINATIF (nomme la version courante)",
       isinstance(msg(r), str) and "version" in msg(r).lower(), str(msg(r)))
    ok("sentinelle : examen A toujours à 1 version (pas 2)", count_baremes(exam_a) == avant_a)
    ok("sentinelle : aucune opération fille ajoutée", count_ops() == avant_ops)
    ok("sentinelle : table globale inchangée", count_baremes() == avant_global)

    # ── Scénario 4 — MOTIF VIDE ─────────────────────────────────────────────
    print("\n== scénario 4 : motif vide ==")
    avant_a = count_baremes(exam_a)
    avant_global = count_baremes()
    st, r = call("POST", bareme_path(exam_a), resp,
                 {"motif": "", "operations": []})
    ok("refus 400 (motif @NotBlank)", st == 400, f"got {st}")
    ok("sentinelle : aucune ligne écrite (motif vide)", count_baremes(exam_a) == avant_a)
    ok("sentinelle : table globale inchangée", count_baremes() == avant_global)

    # ── nettoyage : tout ce que l'API PERMET de défaire ─────────────────────
    # L'examen B (CONFIGURE) se supprime ; le compte resp2 se supprime
    # (SUPER_ADMIN). L'examen A est TERMINE : indélébile PAR DESIGN (trace
    # institutionnelle, DELETE réservé à BROUILLON/CONFIGURE) — il RESTE en
    # base avec son barème v1, son étudiant et sa notation. On le dit plutôt
    # que de le laisser découvrir dans la liste des examens.
    print("\n== nettoyage (ce que l'API permet) ==")
    st, r = call("DELETE", f"/examens/{exam_b}", resp)
    ok("examen B (CONFIGURE) supprimé", st == 200, f"got {st}: {msg(r)}")
    # Pas de DELETE /users — la désactivation auditée est le seul retrait (#289).
    st, r = call("POST", f"/users/{userid_from(resp2)}/desactivation", admin,
                 {"motif": "Compte jetable de la recette F5 (#364) — rôle épuisé"})
    ok("compte resp2 (hors matière) désactivé", st == 200, f"got {st}: {msg(r)}")
    print(f"⚠️  RÉSIDU VOLONTAIRE : l'examen {exam_a} « F5 recette bareme A » (TERMINE)")
    print("    reste en base avec barème v1 + 1 étudiant + 1 notation — non")
    print("    supprimable par l'API. À purger en SQL si le dev DB doit rester net.")

    print(f"\nEXAM_A(clos)={exam_a}  EXAM_B(non-clos, supprimé)={exam_b}  ITEM_A={item_a}")
    print("\nRÉSULTAT :", "TOUT PASSE" if all(results) else f"{results.count(False)} ÉCHEC(S)")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()