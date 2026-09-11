"""EPOS — session LIVE pour la démo : préparer un examen réaliste, puis noter
les stations « des collègues » pendant que le responsable conduit l'épreuve
(web) et que l'évaluateur note SA station sur le téléphone.

Complément de generate-cohorte-ia.py (dont il réutilise les briques : stations
+ grilles, étudiants + habileté latente theta, notation via les endpoints du
mobile). Différences voulues :

  * `preparer` s'ARRÊTE à CONFIGURE + lots répartis : le lancement, la présence,
    l'ouverture des vagues et la clôture sont des ACTES du responsable, faits
    à l'écran (conducteur jour J, tableau de suivi, remplacement, Terminer).
  * `noter` cible un examen EXISTANT et ne note que les stations demandées,
    avec le compte de l'évaluateur que les rotations désignent réellement
    (donc une suppléance ADR-0017 faite à l'écran est suivie d'elle-même,
    pourvu que le remplaçant soit dans le pool).

Aucun INSERT SQL : tout passe par le gateway, mêmes gardes qu'en production.

Usage :
    # répétition courte (2 stations, 3 min par station, groupes de 3, 6 étudiants) :
    python scripts/session-live.py preparer --nom "Répétition — recette mobile" \
        --date 2026-09-06 --n 6 --stations 2 --duree 3 --par-station 3 \
        --etat scratchpad/repetition.json

    python scripts/session-live.py preparer \
        --nom "Examen pratique de chimie thérapeutique — session de septembre 2026" \
        --date 2026-09-05 --n 36 --prefixe 2026 --theta-mu 0.15 \
        --eval-titrimetrie eval@epos.tn --eval-identification leila.kacem@epos.tn \
        --eval-tampon sami.marzouki@epos.tn --etat scratchpad/session-live.json

    # pendant l'épreuve, à la demande (répétable, s'arrête quand plus rien n'est EN_COURS) :
    python scripts/session-live.py noter --etat scratchpad/session-live.json \
        --stations "Identification d'un principe actif,Préparation d'une solution tampon"
    python scripts/session-live.py noter --etat scratchpad/session-live.json --toutes
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import random
import sys
import time
from pathlib import Path

# generate-cohorte-ia.py a un tiret dans son nom : import par chemin.
_GEN = Path(__file__).with_name("generate-cohorte-ia.py")
_spec = importlib.util.spec_from_file_location("generate_cohorte_ia", _GEN)
gen = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = gen  # requis par @dataclass (résolution des annotations)
_spec.loader.exec_module(gen)

EVAL_PASSWORD = "Eval@1234"

# Trois stations plausibles de TP de chimie thérapeutique ; 2 BINAIRE (5) +
# 2 NUMERIQUE (5/5) = 20 par station, la forme que le générateur sait noter.
STATIONS = [
    ("Dosage par titrimétrie (vitamine C)", [
        ("Burette et réactifs préparés", "BINAIRE", 5.0, None),
        ("Point d'équivalence identifié", "BINAIRE", 5.0, None),
        ("Précision du geste", "NUMERIQUE", 5.0, 5.0),
        ("Sécurité et propreté du poste", "NUMERIQUE", 5.0, 5.0),
    ]),
    ("Identification d'un principe actif", [
        ("Réactif de caractérisation choisi", "BINAIRE", 5.0, None),
        ("Réaction observée et interprétée", "BINAIRE", 5.0, None),
        ("Rigueur du protocole", "NUMERIQUE", 5.0, 5.0),
        ("Interprétation du résultat", "NUMERIQUE", 5.0, 5.0),
    ]),
    ("Préparation d'une solution tampon", [
        ("Calcul des quantités exact", "BINAIRE", 5.0, None),
        ("pH vérifié au pH-mètre", "BINAIRE", 5.0, None),
        ("Qualité de la préparation", "NUMERIQUE", 5.0, 5.0),
        ("Sécurité et propreté du poste", "NUMERIQUE", 5.0, 5.0),
    ]),
]


def _login_pool(base: str, emails: list[str]) -> dict[int, tuple[str, str]]:
    """{user_id: (email, token)} — un seul essai par compte (verrou à 3 échecs)."""
    pool = {}
    for email in emails:
        st, r = gen.call(base, "POST", "/auth/login", None, {"email": email, "password": EVAL_PASSWORD})
        if st != 200:
            print(f"  ⚠️  {email} : login HTTP {st} — exclu du pool (mot de passe ≠ {EVAL_PASSWORD} ?)")
            continue
        token = gen.payload(r)["accessToken"]
        pool[gen.user_id_from_token(token)] = (email, token)
    return pool


def preparer(args):
    base = args.base_url
    rng = random.Random(args.seed)
    resp_token = gen.login(base, "resp@epos.tn", "Resp@1234")

    # --stations N : les N premières stations de STATIONS, tenues par les N premiers
    # évaluateurs (titrimétrie, identification, tampon) — répétition courte à 2 stations.
    stations_voulues = STATIONS[:args.stations]
    emails = [args.eval_titrimetrie, args.eval_identification, args.eval_tampon][:args.stations]
    pool = _login_pool(base, emails)
    id_par_email = {email: uid for uid, (email, _) in pool.items()}
    manquants = [e for e in emails if e not in id_par_email]
    if manquants:
        sys.exit(f"FATAL: évaluateur(s) sans login valide : {manquants}")

    examen = gen.must(
        base, "POST", "/examens", resp_token,
        {"nom": args.nom, "matiereId": 1, "dateExamen": args.date, "heureDebut": args.heure,
         "dureeStationMin": args.duree, "nbEtudiantsParStation": args.par_station},
        ok=(201,), what="créer l'examen",
    )
    examen_id = examen["id"]
    print(f"examen {examen_id} créé : {args.nom} ({args.date})")

    stations_etat = []
    for (nom_station, items), email in zip(stations_voulues, emails):
        s = gen.creer_station_avec_grille(base, resp_token, examen_id, nom_station,
                                          [id_par_email[email]], 20.0, items)
        stations_etat.append({
            "id": s.id, "nom": nom_station, "grille_id": s.grille_id,
            "items_par_libelle": s.items_par_libelle, "evaluateur_initial": email,
        })
        print(f"  station {s.id} « {nom_station} » → {email}")

    etudiants = gen.creer_etudiants_et_inscrire(base, resp_token, examen_id, args.n,
                                                args.prefixe, rng, theta_sigma=1.0,
                                                theta_mu=args.theta_mu)
    print(f"  {len(etudiants)} étudiants inscrits (matricules {args.prefixe}-0000…)")

    gen.must(base, "PATCH", f"/examens/{examen_id}/statut?statut=CONFIGURE", resp_token,
             what="passer en CONFIGURE")
    rep = gen.must(base, "POST", f"/lots/examens/{examen_id}/repartir", resp_token, ok=(201,),
                   what="répartir en lots")
    lots = sorted(rep["details"], key=lambda d: d["numeroLot"])
    print(f"  lots : {[(l['numeroLot'], l['taille']) for l in lots]}")

    incomplets = gen.must(base, "GET", f"/examens/{examen_id}/baremes-incomplets", resp_token,
                          what="barèmes incomplets")
    conflits = gen.must(base, "GET", f"/examens/{examen_id}/conflits-evaluateurs", resp_token,
                        what="conflits évaluateurs")
    print(f"  pré-vol : barèmes incomplets = {incomplets} · conflits = {conflits}")

    etat = {
        "base_url": base, "examen_id": examen_id, "nom": args.nom, "date": args.date,
        "stations": stations_etat,
        "etudiants": {str(e.id): {"nom": e.nom, "prenom": e.prenom, "theta": e.theta} for e in etudiants},
        "lots": lots,
        "pool": sorted(set(emails + args.pool_extra)),
        "seed": args.seed,
    }
    Path(args.etat).parent.mkdir(parents=True, exist_ok=True)
    Path(args.etat).write_text(json.dumps(etat, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nÉtat écrit : {args.etat}")
    print(f"→ Web : http://localhost:4200/examens/{examen_id}/lancement (resp@epos.tn)")
    print("  L'examen N'EST PAS lancé : c'est l'acte du responsable, à l'écran.")


def noter(args):
    etat = json.loads(Path(args.etat).read_text(encoding="utf-8"))
    base = etat["base_url"]
    rng = random.Random(etat["seed"] + 1)
    resp_token = gen.login(base, "resp@epos.tn", "Resp@1234")
    pool = _login_pool(base, etat["pool"])
    if not pool:
        sys.exit("FATAL: aucun compte du pool ne se connecte")

    etudiants_par_id = {
        int(k): gen.Etudiant(id=int(k), nom=v["nom"], prenom=v["prenom"], theta=v["theta"])
        for k, v in etat["etudiants"].items()
    }
    voulues = None if args.toutes else {s.strip() for s in args.stations.split(",")}
    stations = [s for s in etat["stations"] if voulues is None or s["nom"] in voulues]
    if not stations:
        sys.exit(f"FATAL: aucune station ne correspond à {args.stations!r} — "
                 f"connues : {[s['nom'] for s in etat['stations']]}")

    def note_normale(etu, item, libelle, r):
        if item["type"] == "BINAIRE":
            return gen.valeur_binaire_correlee(etu.theta, r)
        return gen.valeur_numerique_correlee(etu.theta, r, item["valeurMax"])

    rapport = gen.Rapport()
    total_avant = _nb_verrouillees(base, resp_token, etat)
    for s in stations:
        info = gen.StationInfo(id=s["id"], grille_id=s["grille_id"], items_par_libelle=s["items_par_libelle"])
        # Qui note cette station MAINTENANT ? Ce que disent les rotations, pas la config.
        rotations = gen.must(base, "GET", f"/rotations/station/{s['id']}", resp_token,
                             what="lister rotations station")
        en_cours = [r for r in rotations if r["statut"] == "EN_COURS"]
        if not en_cours:
            print(f"  « {s['nom']} » : aucune rotation EN_COURS — rien à noter (vague pas ouverte, ou finie).")
            continue
        eval_id = en_cours[0]["evaluateurId"]
        if eval_id not in pool:
            print(f"  ⚠️  « {s['nom']} » est tenue par l'évaluateur id {eval_id}, absent du pool "
                  f"{sorted(pool)} — ajoutez son e-mail dans l'état (« pool ») puis relancez.")
            continue
        email, token = pool[eval_id]
        print(f"  « {s['nom']} » — notée par {email} (id {eval_id})…")
        gen.grader_station_lot_courant(base, token, eval_id, info, etudiants_par_id,
                                       note_normale, rapport, rng)
    total_apres = _nb_verrouillees(base, resp_token, etat)
    print(f"\nRotations avant : {total_avant}\nRotations après : {total_apres}")
    if args.attendre:
        print("(--attendre) Nouvelle passe dans 20 s — Ctrl+C pour arrêter.")
        time.sleep(20)
        return noter(args)


def _nb_verrouillees(base, resp_token, etat) -> str:
    """État des rotations par station (EN_ATTENTE / EN_COURS / TERMINE) — ce que
    le tableau de suivi montre, lu à la même source (scoring)."""
    parts = []
    for s in etat["stations"]:
        st, r = gen.call(base, "GET", f"/rotations/station/{s['id']}", resp_token)
        if st != 200:
            parts.append(f"{s['nom']}: HTTP {st}")
            continue
        comptes: dict[str, int] = {}
        for rot in gen.payload(r):
            comptes[rot["statut"]] = comptes.get(rot["statut"], 0) + 1
        parts.append(f"{s['nom']}: " + ", ".join(f"{k} {v}" for k, v in sorted(comptes.items())))
    return " | ".join(parts)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("preparer", help="créer l'examen réaliste jusqu'à CONFIGURE + lots (pas de lancement)")
    a.add_argument("--base-url", default="http://localhost:8080/api/v1")
    a.add_argument("--nom", required=True)
    a.add_argument("--date", required=True, help="AAAA-MM-JJ — le jour J (le bouton « Lancer » du web l'exige)")
    a.add_argument("--heure", default="09:00")
    a.add_argument("--n", type=int, default=36)
    a.add_argument("--stations", type=int, default=len(STATIONS), choices=range(1, len(STATIONS) + 1),
                   help="nombre de stations (les premières de STATIONS) — 2 pour une répétition courte")
    a.add_argument("--duree", type=int, default=12, help="durée d'une station en minutes (dureeStationMin)")
    a.add_argument("--par-station", type=int, default=6,
                   help="étudiants par station et par vague (taille d'un groupe)")
    a.add_argument("--prefixe", default="2026")
    a.add_argument("--theta-mu", type=float, default=0.15)
    a.add_argument("--seed", type=int, default=20260905)
    a.add_argument("--eval-titrimetrie", default="eval@epos.tn")
    a.add_argument("--eval-identification", default="leila.kacem@epos.tn")
    a.add_argument("--eval-tampon", default="sami.marzouki@epos.tn")
    a.add_argument("--pool-extra", nargs="*", default=["rim.ayadi@epos.tn"],
                   help="remplaçants possibles (suppléance à l'écran) que `noter` saura incarner")
    a.add_argument("--etat", required=True, help="fichier JSON d'état (ids, theta, pool)")
    a.set_defaults(func=preparer)

    b = sub.add_parser("noter", help="noter les stations demandées pour la vague ouverte")
    b.add_argument("--etat", required=True)
    g = b.add_mutually_exclusive_group(required=True)
    g.add_argument("--stations", help="noms exacts séparés par des virgules")
    g.add_argument("--toutes", action="store_true")
    b.add_argument("--attendre", action="store_true", help="repasser toutes les 20 s")
    b.set_defaults(func=noter)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
