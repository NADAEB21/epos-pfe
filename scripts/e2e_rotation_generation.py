"""Throwaway e2e for Phase C rotation generation. Drives the gateway end-to-end.
Run: python scripts/e2e_rotation_generation.py
Cleans up the exam + roster it creates at the end (pass --keep to leave it).
"""
import json, sys, base64, urllib.request, urllib.error

BASE = "http://localhost:8080/api/v1"
KEEP = "--keep" in sys.argv


def call(method, path, token=None, body=None, raw_array=None):
    url = BASE + path
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode()
    elif raw_array is not None:
        data = json.dumps(raw_array).encode()
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
    "nom": "E2E Rotation Gen", "matiereId": 1, "dateExamen": "2026-06-20",
    "heureDebut": "08:30", "dureeStationMin": 10, "nbEtudiantsParStation": 4,
    "description": "throwaway e2e"})
assert st == 201, f"create exam -> {st} {r}"
exam = r["data"]
exam_id = exam["id"]
ok("exam created in BROUILLON", exam["statut"] == "BROUILLON", f"id={exam_id}")
ok("heureDebut round-trips as 08:30", exam.get("heureDebut") == "08:30", f"got={exam.get('heureDebut')}")

print("== 3 stations bound to the evaluateur ==")
station_ids = []
for i in range(3):
    st, r = call("POST", f"/examens/{exam_id}/stations", resp, {
        "nom": f"Station {i+1}", "type": "PRATIQUE", "description": "",
        "evaluateurIds": [eval_uid]})
    assert st == 201, f"station -> {st} {r}"
    station_ids.append(r["data"]["id"])
print("stations:", station_ids)

print("== roster: 6 present + 1 absent ==")
st, r = call("GET", "/etudiants", resp)
etu = (r["data"] if isinstance(r, dict) else r)[:7]
assert len(etu) >= 7, f"need >=7 etudiants, got {len(etu)}"
present_ids, absent_id = [e["id"] for e in etu[:6]], etu[6]["id"]
for eid in present_ids:
    st, r = call("POST", "/participations", resp,
                 {"examen_id": exam_id, "etudiantId": eid, "est_present": True})
    assert st in (200, 201), f"participation -> {st} {r}"
st, r = call("POST", "/participations", resp,
             {"examen_id": exam_id, "etudiantId": absent_id, "est_present": False})
assert st in (200, 201), f"absent participation -> {st} {r}"
print("enrolled 7 (6 present, 1 absent)")

print("== composite unique constraint (409 on duplicate enrol) ==")
st, r = call("POST", "/participations", resp,
             {"examen_id": exam_id, "etudiantId": present_ids[0], "est_present": True})
ok("duplicate (examen,etudiant) -> 409", st == 409, f"got {st}")

print("== generate BEFORE configure should be rejected (gate) ==")
st, r = call("POST", f"/rotations/examens/{exam_id}/generer", resp)
ok("generate at BROUILLON -> 400", st == 400, f"got {st}: {r.get('message') if isinstance(r,dict) else r}")

print("== finalize BROUILLON -> CONFIGURE ==")
st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=CONFIGURE", resp)
assert st == 200, f"configure -> {st} {r}"
ok("exam now CONFIGURE", r["data"]["statut"] == "CONFIGURE")

print("== generate rotations ==")
st, r = call("POST", f"/rotations/examens/{exam_id}/generer", resp)
assert st == 201, f"generate -> {st} {r}"
g = r["data"]
print("  result:", g)
ok("3 groupes", g["groupes"] == 3)
ok("3 creneaux", g["creneaux"] == 3)
ok("9 rotations (K^2)", g["rotations"] == 9)
ok("18 assignments (6 present x 3)", g["assignments"] == 18)
ok("6 presents", g["etudiantsPresents"] == 6)
ok("1 absent skipped", g["etudiantsAbsents"] == 1)

print("== re-run (idempotent) ==")
st, r = call("POST", f"/rotations/examens/{exam_id}/generer", resp)
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
print("STATION_IDS=" + ",".join(map(str, station_ids)))

if not KEEP:
    print("== cleanup ==")
    st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=BROUILLON", resp)
    st, r = call("DELETE", f"/examens/{exam_id}", resp)
    print("  delete exam ->", st)
    # participations + scoring rows for this exam are throwaway; leave generation
    # rows (no cascade across services) — they reference the deleted exam id only.

print("\nRESULT:", "ALL PASS" if not ok.failed else "SOME FAILURES")
sys.exit(1 if ok.failed else 0)
