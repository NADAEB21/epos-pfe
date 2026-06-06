#!/usr/bin/env python3
"""EPOS — demo data seeder.

Drives the running stack at $BASE_URL (default http://localhost:8080) through the
gateway and creates a believable, cross-service-consistent dataset for the
Responsable workspace demo:

  auth     : a handful of evaluateurs (so the Stations & Grilles picker is real)
  exam     : one Examen "TP Chimie Therapeutique 2024-2025" (matiereId=1) with
             4 stations + a weighted grille per station, evaluateurs assigned
  scoring  : ~18 etudiants (real inscription numbers 481+) each enrolled in the
             exam via an ExamenParticipation (examen_id consistent with exam-svc)

The station/grille content mirrors the real OSCE in docs/data-samples/
(EPOS CHIMIE THERAPEUTIQUE 2024_2025.docx). Pure stdlib — no jq, no requests.

Prereqs: stack up (docker compose up -d), JWT_SECRET matching the running stack.
Re-runnable: tolerates 409 (email already exists) on evaluateur creation.

Usage:
  python scripts/seed-demo.py
  BASE_URL=http://host:8080 python scripts/seed-demo.py
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")


def _req(method, path, token=None, body=None):
    url = BASE_URL + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def login(email, password):
    status, payload = _req("POST", "/api/v1/auth/login",
                           body={"email": email, "password": password})
    if status != 200:
        sys.exit(f"FATAL: login failed for {email} -> HTTP {status}: {payload}")
    return payload["data"]["accessToken"]


def must(status, payload, ok, what):
    if status not in ok:
        sys.exit(f"FATAL: {what} -> HTTP {status}: {payload}")
    return payload


# ---------------------------------------------------------------- data tables

EVALUATEURS = [
    # eval@epos.tn already exists from init.sql; these are the extra graders.
    ("sonia.bouaziz@epos.tn", "Bouaziz", "Sonia"),
    ("mehdi.trabelsi@epos.tn", "Trabelsi", "Mehdi"),
    ("ahlem.jaziri@epos.tn", "Jaziri", "Ahlem"),
    ("nizar.bensalah@epos.tn", "Ben Salah", "Nizar"),
]

# Stations mirror the real TP de Chimie Therapeutique OSCE. Each grille sums its
# ponderations to noteMax (20). NUMERIQUE items carry valeurMax (<= ponderation);
# BINAIRE items are Fait/Non-fait.
STATIONS = [
    {
        "nom": "Identification d'un principe actif (colorimetrie + point de fusion)",
        "type": "PRATIQUE",
        "description": "Identifier le PA d'un echantillon par reaction colorimetrique et banc Kofler.",
        "grille": ("Grille - Identification PA", [
            ("Reaction colorimetrique realisee correctement", "BINAIRE", 5, None),
            ("Mesure du point de fusion (banc Kofler)", "BINAIRE", 5, None),
            ("Identification correcte du principe actif", "NUMERIQUE", 6, 6),
            ("Demarche expliquee et justifiee", "NUMERIQUE", 4, 4),
        ]),
    },
    {
        "nom": "Dosage de l'antipyrine par iodometrie",
        "type": "PRATIQUE",
        "description": "Doser l'antipyrine d'une specialite par titrimetrie (iodometrie) et interpreter.",
        "grille": ("Grille - Dosage antipyrine", [
            ("Protocole de titrage respecte", "BINAIRE", 5, None),
            ("Calcul de la teneur correct", "NUMERIQUE", 6, 6),
            ("Interpretation du resultat", "NUMERIQUE", 5, 5),
            ("Conclusion sur la conformite", "BINAIRE", 4, None),
        ]),
    },
    {
        "nom": "Dosage du paracetamol (generique comprime 300 mg)",
        "type": "PRATIQUE",
        "description": "Controler la teneur d'un generique de paracetamol dose a 300 mg.",
        "grille": ("Grille - Dosage paracetamol", [
            ("Preparation de l'echantillon", "BINAIRE", 4, None),
            ("Realisation du dosage", "NUMERIQUE", 6, 6),
            ("Calcul du titre (cible 300 mg)", "NUMERIQUE", 6, 6),
            ("Conclusion sur la conformite", "BINAIRE", 4, None),
        ]),
    },
    {
        "nom": "Dosage de la vitamine C par pH-metrie (acide ascorbique)",
        "type": "PRATIQUE",
        "description": "Doser l'acide ascorbique d'une gelule par NaOH 0,1 M et exploiter la courbe pH = f(V).",
        "grille": ("Grille - Dosage vitamine C", [
            ("Trace de la courbe pH = f(V)", "BINAIRE", 5, None),
            ("Determination du point d'equivalence", "NUMERIQUE", 5, 5),
            ("Calcul de la teneur en acide ascorbique", "NUMERIQUE", 6, 6),
            ("Interpretation et conclusion", "NUMERIQUE", 4, 4),
        ]),
    },
]

# 18 etudiants. Inscription numbers start at 481 to match docimology_analysis.xlsx.
ETUDIANTS = [
    ("Khelifi", "Yassine"), ("Gharbi", "Ines"), ("Hammami", "Oussama"),
    ("Chaabane", "Rania"), ("Maaloul", "Hatem"), ("Ben Romdhane", "Sirine"),
    ("Dridi", "Wael"), ("Khemiri", "Maryem"), ("Aloui", "Skander"),
    ("Saidi", "Nour"), ("Mahjoubi", "Firas"), ("Ben Amor", "Dorra"),
    ("Tlili", "Aymen"), ("Guesmi", "Emna"), ("Ferchichi", "Bilel"),
    ("Najjar", "Salma"), ("Ouali", "Zied"), ("Belhadj", "Cyrine"),
]

# A couple of absentees keep the roster's presence column believable.
ABSENT_INDEXES = {6, 13}


def main():
    print(f"Seeding EPOS demo data against {BASE_URL}\n")
    admin = login("admin@epos.tn", "Admin@1234")
    resp = login("resp@epos.tn", "Resp@1234")
    print("logged in as admin + resp (RESPONSABLE_MATIERE, matiere 1)")

    # --- evaluateurs (auth-service) --------------------------------------
    for email, nom, prenom in EVALUATEURS:
        status, payload = _req("POST", "/api/v1/users", token=admin, body={
            "email": email, "password": "Eval@1234", "nom": nom, "prenom": prenom,
            "roles": [{"role": "EVALUATEUR", "matiereId": None}],
        })
        if status == 201:
            print(f"  + evaluateur {prenom} {nom}")
        elif status == 409:
            print(f"  = evaluateur {prenom} {nom} (already exists)")
        else:
            must(status, payload, {201, 409}, f"create evaluateur {email}")

    # Resolve evaluateur ids for station assignment.
    _, users = _req("GET", "/api/v1/users?role=EVALUATEUR", token=admin)
    eval_ids = [u["id"] for u in users["data"]]
    print(f"  evaluateur pool: {eval_ids}")

    # --- examen (exam-service) -------------------------------------------
    exam = must(*_req("POST", "/api/v1/examens", token=resp, body={
        "nom": "TP Chimie Therapeutique 2024-2025",
        "matiereId": 1,
        "dateExamen": "2026-06-20",
        "dureeStationMin": 15,
        "nbEtudiantsParStation": 4,
        "description": "Examen pratique objectif structure - Chimie therapeutique (Faculte de Pharmacie de Monastir).",
    }), {201}, "create examen")["data"]
    exam_id = exam["id"]
    print(f"\n  + examen #{exam_id} '{exam['nom']}'")

    # --- stations + grilles (exam-service) -------------------------------
    for i, st in enumerate(STATIONS):
        assigned = [eval_ids[i % len(eval_ids)]] if eval_ids else []
        station = must(*_req("POST", f"/api/v1/examens/{exam_id}/stations", token=resp, body={
            "nom": st["nom"], "type": st["type"],
            "description": st["description"], "evaluateurIds": assigned,
        }), {201}, f"create station '{st['nom']}'")["data"]
        sid = station["id"]
        gname, items = st["grille"]
        grille = must(*_req("POST", f"/api/v1/stations/{sid}/grille", token=resp, body={
            "nom": gname, "noteMax": 20.0,
            "description": "Grille de ponderation - lecture seule (demo).",
            "items": [
                {"libelle": lib, "type": typ, "ponderation": float(pond),
                 **({"valeurMax": float(vmax)} if vmax is not None else {})}
                for (lib, typ, pond, vmax) in items
            ],
        }), {201}, f"create grille for station {sid}")["data"]
        print(f"    station #{sid} '{st['nom'][:40]}...' "
              f"-> {len(assigned)} eval, grille #{grille.get('id')} ({len(items)} items)")

    # --- etudiants + participations (scoring-service) --------------------
    print()
    enrolled = 0
    for idx, (nom, prenom) in enumerate(ETUDIANTS):
        numero = str(481 + idx)
        et = must(*_req("POST", "/api/v1/etudiants", token=resp, body={
            "nom": nom, "prenom": prenom, "numero_inscription": numero,
        }), {201}, f"create etudiant {numero}")["data"]
        must(*_req("POST", "/api/v1/participations", token=resp, body={
            "examen_id": exam_id,
            "num_echantillon": f"E-{idx + 1:02d}",
            "est_present": False if idx in ABSENT_INDEXES else True,
            "note": None,
            "etudiantId": et["id"],
        }), {201}, f"enrol etudiant {et['id']}")
        enrolled += 1
    print(f"  + {enrolled} etudiants enrolled "
          f"({len(ABSENT_INDEXES)} marked absent)")

    print(f"\nDone. Demo exam id = {exam_id}. "
          f"Open /examens/{exam_id}/vue-ensemble in the web app.")


if __name__ == "__main__":
    main()
