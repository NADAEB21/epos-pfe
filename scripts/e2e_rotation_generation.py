"""Throwaway e2e for the two-phase lot/rotation workflow. Drives the gateway
end-to-end: build exam -> répartir en lots (CONFIGURE) -> launch (EN_COURS) ->
mark a lot present (exam day) -> generate that lot's rotations -> evaluateur sees
the work list. Run: python scripts/e2e_rotation_generation.py
Cleans up the exam + roster it creates at the end (pass --keep to leave it).
"""
import json, sys, base64, urllib.request, urllib.error

BASE = "http://localhost:8080/api/v1"
KEEP = "--keep" in sys.argv


def call(method, path, token=None, body=None):
    url = BASE + path
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode()
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


def msg(r):
    return r.get("message") if isinstance(r, dict) else r


def ok(label, cond, extra=""):
    print(("PASS  " if cond else "FAIL  ") + label + (("  " + extra) if extra else ""))
    if not cond:
        ok.failed = True
ok.failed = False


print("== login ==")
resp = login("resp@epos.tn", "Resp@1234")
evaltok = login("eval@epos.tn", "Eval@1234")
eval_uid = userid_from(evaltok)
print("evaluateur userId =", eval_uid)

print("== create exam from scratch ==")
st, r = call("POST", "/examens", resp, {
    "nom": "E2E Two-Phase Lots", "matiereId": 1, "dateExamen": "2026-06-20",
    "heureDebut": "08:30", "dureeStationMin": 10, "nbEtudiantsParStation": 4,
    "description": "throwaway e2e"})
assert st == 201, f"create exam -> {st} {r}"
exam = r["data"]
exam_id = exam["id"]
ok("exam created in BROUILLON", exam["statut"] == "BROUILLON", f"id={exam_id}")

print("== 3 stations bound to the evaluateur ==")
station_ids = []
for i in range(3):
    st, r = call("POST", f"/examens/{exam_id}/stations", resp, {
        "nom": f"Station {i+1}", "type": "PRATIQUE", "description": "",
        "evaluateurIds": [eval_uid]})
    assert st == 201, f"station -> {st} {r}"
    station_ids.append(r["data"]["id"])
print("stations:", station_ids)

print("== roster: enrol 7 students ==")
st, r = call("GET", "/etudiants", resp)
etu = (r["data"] if isinstance(r, dict) else r)[:7]
assert len(etu) >= 7, f"need >=7 etudiants, got {len(etu)}"
enrolled_ids, absent_etu_id = [e["id"] for e in etu], etu[6]["id"]
for eid in enrolled_ids:
    st, r = call("POST", "/participations", resp, {"examen_id": exam_id, "etudiantId": eid})
    assert st in (200, 201), f"participation -> {st} {r}"
print("enrolled 7")

print("== composite unique constraint (409 on duplicate enrol) ==")
st, r = call("POST", "/participations", resp, {"examen_id": exam_id, "etudiantId": enrolled_ids[0]})
ok("duplicate (examen,etudiant) -> 409", st == 409, f"got {st}")

print("== Phase 1 gate: répartir BEFORE configure is rejected ==")
st, r = call("POST", f"/lots/examens/{exam_id}/repartir", resp)
ok("répartir at BROUILLON -> 400", st == 400, f"got {st}: {msg(r)}")

print("== finalize BROUILLON -> CONFIGURE ==")
st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=CONFIGURE", resp)
assert st == 200, f"configure -> {st} {r}"
ok("exam now CONFIGURE", r["data"]["statut"] == "CONFIGURE")

print("== Phase 1: répartir en lots ==")
st, r = call("POST", f"/lots/examens/{exam_id}/repartir", resp)
assert st == 201, f"répartir -> {st} {r}"
rep = r["data"]
print("  result:", rep)
ok("lotSize = K*cap = 12", rep["lotSize"] == 12)
ok("1 lot for 7 students", rep["lots"] == 1)
ok("7 répartis", rep["etudiantsRepartis"] == 7)
lot_id = rep["details"][0]["lotId"]
print("  lot_id =", lot_id)

print("== participation.lot is now wired ==")
st, r = call("GET", f"/participations?examenId={exam_id}", resp)
parts = r["data"] if isinstance(r, dict) else r
ok("all participations carry a lotId", all(p["lotId"] == lot_id for p in parts), f"{len(parts)} parts")
absent_part_id = next(p["id"] for p in parts if p["etudiantId"] == absent_etu_id)

print("== Phase 2 gate: generate BEFORE launch is rejected (EN_COURS required) ==")
st, r = call("POST", f"/rotations/lots/{lot_id}/generer", resp)
ok("generate at CONFIGURE -> 400", st == 400, f"got {st}: {msg(r)}")

print("== launch: CONFIGURE -> EN_COURS ==")
st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=EN_COURS", resp)
assert st == 200, f"launch -> {st} {r}"
ok("exam now EN_COURS", r["data"]["statut"] == "EN_COURS")

print("== Phase 2 gate: generate BEFORE presence is rejected ==")
st, r = call("POST", f"/rotations/lots/{lot_id}/generer", resp)
ok("generate before presence -> 400", st == 400, f"got {st}: {msg(r)}")

print("== Phase 2: mark the lot present (1 absent) ==")
st, r = call("PATCH", f"/lots/{lot_id}/presence", resp, {"absents": [absent_part_id]})
assert st == 200, f"presence -> {st} {r}"
pres = r["data"]
print("  result:", pres)
ok("6 présents", pres["presents"] == 6)
ok("1 absent", pres["absents"] == 1)

print("== Phase 2: generate the lot's rotations ==")
st, r = call("POST", f"/rotations/lots/{lot_id}/generer", resp)
assert st == 201, f"generate -> {st} {r}"
g = r["data"]
print("  result:", g)
ok("3 groupes", g["groupes"] == 3)
ok("3 creneaux", g["creneaux"] == 3)
ok("9 rotations (K^2)", g["rotations"] == 9)
ok("18 assignments (6 present x 3)", g["assignments"] == 18)
ok("6 presents", g["etudiantsPresents"] == 6)
ok("1 absent skipped", g["etudiantsAbsents"] == 1)

print("== re-run (idempotent per lot) ==")
st, r = call("POST", f"/rotations/lots/{lot_id}/generer", resp)
ok("re-generate still 9 rotations / 18 assignments",
   st == 201 and r["data"]["rotations"] == 9 and r["data"]["assignments"] == 18, f"got {st}")

print("== evaluateur sees a work list ==")
total = 0
for sid in station_ids:
    st, r = call("GET", f"/rotations/station/{sid}", evaltok)
    rows = r["data"] if isinstance(r, dict) else r
    total += len(rows)
ok("evaluateur GET /rotations/station/* non-empty", total > 0, f"{total} rotations visible")

print("\nEXAM_ID=" + str(exam_id))
print("LOT_ID=" + str(lot_id))
print("STATION_IDS=" + ",".join(map(str, station_ids)))

if not KEEP:
    print("== cleanup ==")
    # NOTE: a launched (EN_COURS) exam cannot be deleted via the API by design —
    # exam-service only allows DELETE at BROUILLON/CONFIGURE, and EN_COURS only
    # transitions forward to TERMINE (ExamenServiceImpl.validerTransitionStatut).
    # So this throwaway exam can't be removed through the gateway once generated.
    # We close it to TERMINE to get it out of the active set; full removal needs a
    # DB delete (examens + scoring lot/group/rotation/assignment/participation rows
    # for this examen_id). The exam id is printed above for that.
    st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=TERMINE", resp)
    print("  close exam -> TERMINE", st, "(DB cleanup needed for full removal — see EXAM_ID above)")

print("\nRESULT:", "ALL PASS" if not ok.failed else "SOME FAILURES")
sys.exit(1 if ok.failed else 0)
