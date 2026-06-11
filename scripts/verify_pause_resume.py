"""Throwaway verification for exam pause/resume (ADR-0009). Drives the gateway:
create exam -> CONFIGURE -> EN_COURS -> pause/resume + the guard rejections.
Run: python scripts/verify_pause_resume.py
Closes the exam to TERMINE at the end (EN_COURS exams can't be deleted by design).
"""
import json, sys, time, urllib.request, urllib.error

BASE = "http://localhost:8080/api/v1"


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
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


def ok(label, cond, extra=""):
    print(("PASS  " if cond else "FAIL  ") + label + (("  " + extra) if extra else ""))
    if not cond:
        ok.failed = True
ok.failed = False


def data(r):
    return r["data"] if isinstance(r, dict) else r


print("== login ==")
resp = login("resp@epos.tn", "Resp@1234")

print("== create exam (BROUILLON) ==")
st, r = call("POST", "/examens", resp, {
    "nom": "Verify Pause/Resume", "matiereId": 1, "dateExamen": "2026-06-25",
    "heureDebut": "09:00", "dureeStationMin": 10, "nbEtudiantsParStation": 4})
assert st == 201, f"create -> {st} {r}"
exam_id = data(r)["id"]
print("  exam_id =", exam_id)
ok("new exam not paused, totalPauseSec=0",
   data(r)["enPause"] is False and data(r)["totalPauseSec"] == 0)

print("== guard: cannot pause a BROUILLON exam ==")
st, r = call("PATCH", f"/examens/{exam_id}/pause", resp)
ok("pause at BROUILLON -> 400", st == 400, f"got {st}")

print("== CONFIGURE ==")
st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=CONFIGURE", resp)
assert st == 200, f"configure -> {st} {r}"

print("== guard: cannot pause a CONFIGURE exam ==")
st, r = call("PATCH", f"/examens/{exam_id}/pause", resp)
ok("pause at CONFIGURE -> 400", st == 400, f"got {st}")

print("== launch EN_COURS ==")
st, r = call("PATCH", f"/examens/{exam_id}/statut?statut=EN_COURS", resp)
assert st == 200, f"launch -> {st} {r}"

print("== guard: cannot resume an exam that is not paused ==")
st, r = call("PATCH", f"/examens/{exam_id}/reprendre", resp)
ok("resume when not paused -> 400", st == 400, f"got {st}")

print("== pause ==")
st, r = call("PATCH", f"/examens/{exam_id}/pause", resp)
ok("pause -> 200", st == 200, f"got {st}")
d = data(r)
ok("enPause = true", d["enPause"] is True)
ok("pausedAt set", d["pausedAt"] is not None, str(d.get("pausedAt")))
ok("statut still EN_COURS (pause is orthogonal)", d["statut"] == "EN_COURS")

print("== guard: cannot pause an already-paused exam ==")
st, r = call("PATCH", f"/examens/{exam_id}/pause", resp)
ok("double pause -> 400", st == 400, f"got {st}")

print("== wait ~2s, resume ==")
time.sleep(2.2)
st, r = call("PATCH", f"/examens/{exam_id}/reprendre", resp)
ok("resume -> 200", st == 200, f"got {st}")
d = data(r)
ok("enPause = false", d["enPause"] is False)
ok("pausedAt cleared", d["pausedAt"] is None)
ok("totalPauseSec accrued (>=1)", d["totalPauseSec"] >= 1, f"got {d['totalPauseSec']}")

print("== persistence: GET reflects accrued pause ==")
st, r = call("GET", f"/examens/{exam_id}", resp)
d = data(r)
ok("GET enPause=false", d["enPause"] is False)
ok("GET totalPauseSec persisted", d["totalPauseSec"] >= 1, f"got {d['totalPauseSec']}")

print("== second pause/resume accumulates ==")
first = d["totalPauseSec"]
call("PATCH", f"/examens/{exam_id}/pause", resp)
time.sleep(1.2)
st, r = call("PATCH", f"/examens/{exam_id}/reprendre", resp)
ok("totalPauseSec accumulates across pauses", data(r)["totalPauseSec"] > first,
   f"{first} -> {data(r)['totalPauseSec']}")

print("== cleanup: close to TERMINE ==")
call("PATCH", f"/examens/{exam_id}/statut?statut=TERMINE", resp)

print("\nRESULT:", "ALL PASS" if not ok.failed else "SOME FAILURES")
sys.exit(1 if ok.failed else 0)
