"""EPOS — épreuve de DÉMONSTRATION pour les captures du rapport (chapitre 4).

Crée, via la passerelle uniquement, une épreuve « à venir » qui ressemble aux
captures que le rapport décrit :
  · station « Dosage par titrimétrie (vitamine C) » avec la GRILLE RÉELLE du
    tableau 4.2 (critère 1 « Préparation du dosage » noté via deux sous-critères
    1a/1b, critère 6 numérique sur 6 avec corrigé type) ;
  · une seconde station à quatre critères (pour des lots de 12 = 2 × 6) ;
  · 24 étudiants, dont 2 sans adresse électronique ;
  · état CONFIGURE, deux lots de 12, le second sur une seconde journée.

Usage :
    python scripts/demo-exam-captures.py --date 2027-01-15 --prefixe 2027
Puis les captures Playwright (pwverify/capture-chap4-103.js), puis PURGER l'épreuve.
"""
from __future__ import annotations

import argparse
import importlib.util
import random
import sys
from pathlib import Path

_GEN = Path(__file__).with_name("generate-cohorte-ia.py")
_spec = importlib.util.spec_from_file_location("generate_cohorte_ia", _GEN)
gen = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = gen
_spec.loader.exec_module(gen)

# Grille de référence (tableau 4.2 du rapport) : (libelle, type, pondération, valeurMax, sous-critères)
GRILLE_TITRIMETRIE = [
    ("Préparation du dosage", "BINAIRE", 3.0, None,
     [("Choix de l'indicateur coloré", "BINAIRE", 2.0, None),
      ("Vérification du titre de la solution de soude", "BINAIRE", 1.0, None)]),
    ("Utilisation du bon indicateur", "BINAIRE", 2.0, None, []),
    ("Utilisation correcte de la burette", "BINAIRE", 3.0, None, []),
    ("Prise du volume lors du virage", "BINAIRE", 2.0, None, []),
    ("Propreté de manipulation", "BINAIRE", 2.0, None, []),
    ("Calcul de la masse (démarche et résultat)", "NUMERIQUE", 6.0, 6.0, []),
    ("Lavage du matériel et rinçage de la burette", "BINAIRE", 2.0, None, []),
]
STATION_2 = ("Identification d'un principe actif", [
    ("Réactif de caractérisation choisi", "BINAIRE", 5.0, None),
    ("Réaction observée et interprétée", "BINAIRE", 5.0, None),
    ("Rigueur du protocole", "NUMERIQUE", 5.0, 5.0),
    ("Interprétation du résultat", "NUMERIQUE", 5.0, 5.0),
])


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default="http://localhost:8080/api/v1")
    p.add_argument("--nom", default="Examen pratique de chimie thérapeutique — session de janvier 2027")
    p.add_argument("--date", required=True)
    p.add_argument("--prefixe", default="2027")
    p.add_argument("--n", type=int, default=24)
    p.add_argument("--sans-adresse", type=int, default=2)
    p.add_argument("--eval-titrimetrie", default="eval@epos.tn")
    p.add_argument("--eval-identification", default="leila.kacem@epos.tn")
    a = p.parse_args()
    base = a.base_url
    rng = random.Random(20270115)
    resp = gen.login(base, "resp@epos.tn", "Resp@1234")

    ids = {}
    for email in (a.eval_titrimetrie, a.eval_identification):
        st, r = gen.call(base, "POST", "/auth/login", None, {"email": email, "password": "Eval@1234"})
        if st != 200:
            sys.exit(f"FATAL: login {email} → HTTP {st}")
        ids[email] = gen.user_id_from_token(gen.payload(r)["accessToken"])

    ex = gen.must(base, "POST", "/examens", resp,
                  {"nom": a.nom, "matiereId": 1, "dateExamen": a.date, "heureDebut": "09:00",
                   "dureeStationMin": 12, "nbEtudiantsParStation": 6,
                   "description": "Épreuve pratique objective structurée (OSPE) — deux stations, "
                                  "deux lots de douze candidats sur deux journées."},
                  ok=(201,), what="créer l'examen")
    exid = ex["id"]
    print(f"examen {exid} créé : {a.nom} ({a.date})")

    # Station 1 : grille réelle, sous-critères ajoutés après coup (un seul niveau, #160).
    items_plat = [(lib, typ, pond, vmax) for lib, typ, pond, vmax, _ in GRILLE_TITRIMETRIE]
    s1 = gen.creer_station_avec_grille(base, resp, exid, "Dosage par titrimétrie (vitamine C)",
                                       [ids[a.eval_titrimetrie]], 20.0, items_plat)
    for lib, typ, pond, vmax, sous in GRILLE_TITRIMETRIE:
        parent = s1.items_par_libelle[lib]
        for slib, styp, spond, svmax in sous:
            body = {"libelle": slib, "type": styp, "ponderation": spond}
            if svmax is not None:
                body["valeurMax"] = svmax
            gen.must(base, "POST", f"/items/{parent['id']}/sous-criteres", resp, body,
                     ok=(201,), what=f"sous-critère {slib}")
        if lib.startswith("Calcul de la masse"):
            gen.must(base, "PUT", f"/items/{parent['id']}", resp,
                     {"libelle": lib, "type": typ, "ponderation": pond, "valeurMax": vmax,
                      "valeurAttendue": 0.5, "conditionsAttendues": "tolérance ± 5 % sur la masse"},
                     what="corrigé type du critère 6")
    print(f"  station {s1.id} « Dosage par titrimétrie » — grille réelle (7 critères, 2 sous-critères)")
    s2 = gen.creer_station_avec_grille(base, resp, exid, STATION_2[0], [ids[a.eval_identification]],
                                       20.0, STATION_2[1])
    print(f"  station {s2.id} « {STATION_2[0]} »")

    etus = gen.creer_etudiants_et_inscrire(base, resp, exid, a.n, a.prefixe, rng)
    print(f"  {len(etus)} étudiants inscrits (matricules {a.prefixe}-0000…)")

    gen.must(base, "PATCH", f"/examens/{exid}/statut?statut=CONFIGURE", resp, what="CONFIGURE")
    rep = gen.must(base, "POST", f"/lots/examens/{exid}/repartir", resp, ok=(201,), what="répartir")
    lots = sorted(rep["details"], key=lambda d: d["numeroLot"])
    print(f"  lots : {[(l['numeroLot'], l['taille']) for l in lots]}")
    print(f"\nÉTUDIANTS_IDS={[e.id for e in etus]}")
    print(f"LOTS={[l.get('lotId') or l.get('id') for l in lots]}")
    print(f"→ http://localhost:4200/examens/{exid}/stations-grilles")


if __name__ == "__main__":
    main()
