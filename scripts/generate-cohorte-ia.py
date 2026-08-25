#!/usr/bin/env python3
"""EPOS — générateur de cohorte synthétique pour le module IA/BI (#354, F1/P0).

Produit N examens CLOS avec des distributions réalistes, dont UN examen de
référence porte 3 défauts psychométriques PLANTÉS et documentés (voir
README-cohorte-ia.md) :

  1. un critère où (presque) personne ne réussit  → difficulté p ≈ 0.05
  2. un critère dont la note est indépendante du niveau de l'étudiant
     → discrimination r ≈ 0 (bruit pur)
  3. un évaluateur décalé de +2 points/critère sur UNE MÊME station,
     comparé à son collègue qui note la même grille sur un autre lot
     du même examen → sévérité intra-station (ADR-0021 D2)

C'est la parade au risque n°1 du plan IA/BI (29 notations réelles en base
au 20/08) : la démo jury montre le moteur RETROUVER les défauts semés.

Contrat de la tâche F1
-----------------------
- Passe UNIQUEMENT par l'API publique (aucun INSERT SQL direct) : les
  données traversent les mêmes gardes qu'en production (#274, ADR-0015,
  ADR-0018...).
- Rejouable d'une seule commande sur une pile fraîche (idempotent au sens
  "ne casse rien" — chaque run crée de nouveaux examens/étudiants nommés
  avec un horodatage, il ne modifie jamais rien d'existant).
- Une graine RNG fixe (--seed) rend les distributions REPRODUCTIBLES :
  les valeurs annoncées dans le README sont donc vérifiables au rejeu.

Prérequis : `docker compose up -d` (infrastructure/), comptes seed d'init.sql
présents (admin@epos.tn / resp@epos.tn).

Usage :
    python scripts/generate-cohorte-ia.py
    python scripts/generate-cohorte-ia.py --base-url http://host:8080/api/v1 --n-control 3
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import random
import sys
import urllib.error
import urllib.request
import secrets
from dataclasses import dataclass, field

# Console Windows = cp1252 par défaut : incapable d'encoder « ≈ », « α », « → ».
# Sans ceci, le bilan final — la raison d'être du run — plantait en
# UnicodeEncodeError APRÈS avoir écrit toutes les données (prouvé au premier
# run réel, 2026-08-25), et chaque accent sortait en mojibake. `errors="replace"`
# garde le bilan vivant même sur une console exotique.
for _stream in (sys.stdout, sys.stderr):
    if _stream.encoding and _stream.encoding.lower().replace("-", "") != "utf8":
        _stream.reconfigure(encoding="utf-8", errors="replace")

# ─────────────────────────────────────────────────────────────────────────
# Client HTTP minimal — même idiome que seed-demo.py / e2e_rotation_generation.py
# (stdlib pur, aucune dépendance).
# ─────────────────────────────────────────────────────────────────────────


def call(base: str, method: str, path: str, token: str | None = None, body=None):
    url = base + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def payload(resp):
    """Extrait `data` de l'enveloppe ApiResponse (ADR-0004).

    ⚠️ `.get("data")`, jamais `["data"]` : @JsonInclude(NON_NULL) fait qu'un
    endpoint "acte sans retour" (ApiResponse.ok(String) seul — ex.
    POST .../notations/saisir, POST .../rotations/{id}/valider) OMET la clé
    `data` du JSON plutôt que d'envoyer `null`. Ce n'est pas une erreur de
    protocole ; ces appels n'ont simplement rien à en tirer.
    """
    return resp.get("data") if isinstance(resp, dict) else resp


def must(base, method, path, token=None, body=None, ok=(200, 201), what=""):
    st, r = call(base, method, path, token, body)
    if st not in ok:
        sys.exit(f"FATAL: {what or (method + ' ' + path)} -> HTTP {st}: {r}")
    return payload(r)


def login(base: str, email: str, password: str) -> str:
    st, r = call(base, "POST", "/auth/login", body={"email": email, "password": password})
    if st != 200:
        sys.exit(f"FATAL: login {email} -> HTTP {st}: {r}")
    return payload(r)["accessToken"]


def user_id_from_token(token: str) -> int:
    payload_b64 = token.split(".")[1]
    payload_b64 += "=" * (-len(payload_b64) % 4)
    return json.loads(base64.urlsafe_b64decode(payload_b64))["userId"]


# ─────────────────────────────────────────────────────────────────────────
# Comptes évaluateurs dédiés au générateur (créés une fois, réutilisés)
# ─────────────────────────────────────────────────────────────────────────


def ensure_evaluateur(base: str, admin_token: str, email: str, nom: str, prenom: str) -> int:
    st, r = call(
        base, "POST", "/users", admin_token,
        {
            "email": email, "password": "Eval@1234", "nom": nom, "prenom": prenom,
            "roles": [{"role": "EVALUATEUR", "matiereId": None}],
        },
    )
    if st == 201:
        return payload(r)["id"]
    if st == 409:
        # Déjà créé lors d'un run précédent — on retrouve son id (email non
        # modifiable, unique — auth-service #285).
        users = must(base, "GET", "/users?role=EVALUATEUR", admin_token, what="lister évaluateurs")
        for u in users:
            if u["email"].lower() == email.lower():
                return u["id"]
        sys.exit(f"FATAL: {email} annoncé 409 mais introuvable dans /users?role=EVALUATEUR")
    sys.exit(f"FATAL: création évaluateur {email} -> HTTP {st}: {r}")


# ─────────────────────────────────────────────────────────────────────────
# Formules de génération des valeurs — le cœur des 3 défauts.
#
# theta ~ N(0,1) est l'habileté latente de l'étudiant, tirée une fois et
# réutilisée sur tous les items "normaux" de tous ses passages : c'est ce
# qui rend un item "normal" DISCRIMINANT (corrélé au score total), par
# construction.
# ─────────────────────────────────────────────────────────────────────────


def sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))


def valeur_binaire_correlee(theta: float, rng: random.Random) -> float:
    """Item normal : la probabilité de réussite suit l'habileté de l'étudiant."""
    return 1.0 if rng.random() < sigmoid(theta) else 0.0


def valeur_numerique_correlee(theta: float, rng: random.Random, vmax: float) -> float:
    """Item normal : note centrée sur vmax/2, dispersée selon theta + un peu de bruit."""
    bruit = rng.gauss(0, vmax * 0.06)
    v = vmax * 0.5 + theta * vmax * 0.26 + bruit
    return float(max(0.0, min(vmax, round(v))))


# --- DÉFAUT 1 : personne ne réussit ----------------------------------------
P_CIBLE_IMPOSSIBLE = 0.05


def valeur_defaut_impossible(rng: random.Random) -> float:
    """PLANTÉ — indépendant de theta, presque toujours 0. p visé ≈ 0.05."""
    return 1.0 if rng.random() < P_CIBLE_IMPOSSIBLE else 0.0


# --- DÉFAUT 2 : ne discrimine pas ------------------------------------------
def valeur_defaut_aleatoire(rng: random.Random) -> float:
    """PLANTÉ — pile ou face, indépendant de theta. r visé ≈ 0."""
    return 1.0 if rng.random() < 0.5 else 0.0


# --- DÉFAUT 3 : évaluateur décalé -------------------------------------------
DECALAGE_SEVERITE = 2.0  # points par critère, visé


def valeur_severite(theta: float, rng: random.Random, vmax: float, decalage: float) -> float:
    bruit = rng.gauss(0, vmax * 0.05)
    v = vmax * 0.5 + theta * vmax * 0.22 + decalage + bruit
    return float(max(0.0, min(vmax, round(v))))


# ─────────────────────────────────────────────────────────────────────────
# Construction d'un examen : stations + grilles + roster + lots + notation
# ─────────────────────────────────────────────────────────────────────────


@dataclass
class Etudiant:
    id: int
    nom: str
    prenom: str
    theta: float


@dataclass
class StationInfo:
    id: int
    grille_id: int
    items_par_libelle: dict[str, dict]


@dataclass
class Rapport:
    """Ce que le générateur a réellement écrit — pour vérifier le README au rejeu.

    `theta_valeur_par_item` garde le COUPLE (habileté, valeur saisie) : c'est
    ce qui permet de calculer une vraie corrélation de contrôle en fin de run
    (proxy honnête de la discrimination — l'engine réel corrèle item vs
    total−item, pas item vs theta, mais theta EST la cause construite de tout
    le signal ; une corrélation nulle ici garantit une discrimination nulle
    là-bas, et réciproquement pour les items normaux).
    """
    theta_valeur_par_item: dict[str, list[tuple[float, float]]] = field(default_factory=dict)
    valeurs_severite_par_evaluateur: dict[str, list[float]] = field(default_factory=dict)

    def noter(self, libelle_item: str, theta: float, valeur: float):
        self.theta_valeur_par_item.setdefault(libelle_item, []).append((theta, valeur))

    def valeurs(self, libelle_item: str) -> list[float]:
        return [v for _, v in self.theta_valeur_par_item.get(libelle_item, [])]

    def noter_severite(self, evaluateur_label: str, valeur: float):
        self.valeurs_severite_par_evaluateur.setdefault(evaluateur_label, []).append(valeur)


NOMS = ["Ben Ali", "Trabelsi", "Jaziri", "Bouaziz", "Khelifi", "Gharbi", "Hammami",
        "Chaabane", "Maaloul", "Dridi", "Aloui", "Saidi", "Mahjoubi", "Tlili",
        "Guesmi", "Ferchichi", "Najjar", "Ouali", "Belhadj", "Sassi"]
PRENOMS = ["Yassine", "Ines", "Oussama", "Rania", "Hatem", "Sirine", "Wael",
           "Maryem", "Skander", "Nour", "Firas", "Dorra", "Aymen", "Emna"]


def creer_station_avec_grille(base, resp_token, examen_id, nom_station, evaluateur_ids,
                               note_max, items_spec):
    """items_spec: liste de (libelle, type, ponderation, valeur_max_ou_None)."""
    station = must(
        base, "POST", f"/examens/{examen_id}/stations", resp_token,
        {"nom": nom_station, "type": "PRATIQUE", "description": "", "evaluateurIds": evaluateur_ids},
        ok=(201,), what=f"créer station {nom_station}",
    )
    items_req = []
    for libelle, type_item, ponderation, vmax in items_spec:
        entry = {"libelle": libelle, "type": type_item, "ponderation": ponderation}
        if vmax is not None:
            entry["valeurMax"] = vmax
        items_req.append(entry)
    grille = must(
        base, "POST", f"/stations/{station['id']}/grille", resp_token,
        {"nom": f"Grille — {nom_station}", "noteMax": note_max, "description": "", "items": items_req},
        ok=(201,), what=f"créer grille {nom_station}",
    )
    items_par_libelle = {it["libelle"]: it for it in grille["items"]}
    return StationInfo(id=station["id"], grille_id=grille["id"], items_par_libelle=items_par_libelle)


def creer_etudiants_et_inscrire(base, resp_token, examen_id, n, prefixe, rng, theta_sigma=1.0):
    etudiants: list[Etudiant] = []
    for i in range(n):
        nom = rng.choice(NOMS)
        prenom = rng.choice(PRENOMS)
        matricule = f"{prefixe}-{i:04d}"
        etu = must(
            base, "POST", "/etudiants", resp_token,
            {"nom": nom, "prenom": prenom, "numero_inscription": matricule},
            ok=(201,), what=f"créer étudiant {matricule}",
        )
        theta = rng.gauss(0, theta_sigma)
        must(
            base, "POST", "/participations", resp_token,
            {"examen_id": examen_id, "etudiantId": etu["id"]},
            ok=(200, 201), what=f"inscrire étudiant {matricule}",
        )
        etudiants.append(Etudiant(id=etu["id"], nom=nom, prenom=prenom, theta=theta))
    return etudiants


def grader_station_lot_courant(base, eval_token, eval_id, station: StationInfo,
                                etudiants_par_id: dict[int, Etudiant],
                                fonction_valeur, rapport: Rapport, rng,
                                label_severite: str | None = None):
    """Note tous les groupes du lot actuellement ouvert pour CETTE station,
    tant que ce token d'évaluateur y a une rotation EN_COURS.

    fonction_valeur(etudiant, item_dict, rng) -> float
    """
    while True:
        rotations = must(base, "GET", f"/rotations/station/{station.id}", eval_token,
                          what="lister rotations station")
        courante = next(
            (r for r in rotations if r["statut"] == "EN_COURS" and r["evaluateurId"] == eval_id),
            None,
        )
        if courante is None:
            return  # rien (ou plus rien) à noter ici pour cet évaluateur

        detail = must(base, "GET", f"/evaluateur/rotations/{courante['id']}/groupe", eval_token,
                       what="détail groupe")

        for etu_row in detail["etudiants"]:
            if etu_row["absent"]:
                continue
            etudiant = etudiants_par_id[etu_row["id"]]
            for libelle, item in station.items_par_libelle.items():
                valeur = fonction_valeur(etudiant, item, libelle, rng)
                must(
                    base, "POST", "/evaluateur/notations/saisir", eval_token,
                    {
                        "etudiantId": etudiant.id, "stationId": station.id,
                        "grilleId": station.grille_id, "itemId": item["id"], "valeur": valeur,
                    },
                    ok=(200,), what="saisir notation",
                )
                rapport.noter(libelle, etudiant.theta, valeur)
                if label_severite is not None:
                    rapport.noter_severite(label_severite, valeur)
            must(
                base, "POST",
                f"/evaluateur/etudiants/{etudiant.id}/stations/{station.id}/valider", eval_token,
                {"grilleId": station.grille_id, "absent": False, "commentaire": None},
                ok=(200,), what="verrouiller étudiant",
            )

        must(base, "POST", f"/evaluateur/rotations/{courante['id']}/valider", eval_token,
             ok=(200,), what="verrouiller groupe (validerGroupe)")

        # Indexation STRICTE (pas .get()) : ce champ vient de l'API, et une clé
        # absente doit faire planter le script bruyamment, jamais retomber en
        # silence sur None puis arrêter la boucle après le 1er groupe — la même
        # leçon que le projet applique déjà ailleurs (« jamais un repli qui
        # fabrique/masque un défaut », ADR-0015/ADR-0029 D7).
        if detail["groupeSuivantDisponible"]:
            must(base, "POST", f"/evaluateur/rotations/{courante['id']}/suivant", eval_token,
                 ok=(200,), what="avancer groupe (avancerGroupe)")
        else:
            return


def repartir_lancer_repartir(base, resp_token, examen_id):
    must(base, "PATCH", f"/examens/{examen_id}/statut?statut=CONFIGURE", resp_token,
         what="passer en CONFIGURE")
    rep = must(base, "POST", f"/lots/examens/{examen_id}/repartir", resp_token, ok=(201,),
               what="répartir en lots")
    must(base, "PATCH", f"/examens/{examen_id}/statut?statut=EN_COURS", resp_token,
         what="lancer l'examen")
    lots = sorted(rep["details"], key=lambda d: d["numeroLot"])
    return lots  # [{lotId, numeroLot, taille}, ...]


def demarrer_lot(base, resp_token, lot_id, est_premier_lot):
    must(base, "POST", f"/lots/{lot_id}/presence-et-demarrer", resp_token, body={"absents": []},
         ok=(200,), what=f"présence + démarrage lot {lot_id}")
    if not est_premier_lot:
        # ADR-0014-B : seul le lot 1 s'ouvre automatiquement ; les suivants
        # exigent l'acte explicite du responsable, une fois le lot précédent
        # entièrement terminé (ce que garantit notre séquencement).
        must(base, "POST", f"/lots/{lot_id}/ouvrir", resp_token, ok=(200,),
             what=f"ouvrir lot {lot_id}")

def demarrer_lot_avec_substitution(base, resp_token, lot_id, est_premier_lot, substitutions=None):
    """Comme demarrer_lot, mais permet de transférer une station à un autre
    évaluateur ENTRE la génération et l'ouverture.

    affecterEvaluateurs (exam-service) est bloqué dès que l'examen est
    EN_COURS (StationServiceImpl : isGrilleModifiable exige BROUILLON/
    CONFIGURE). Le seul chemin légitime post-lancement est la suppléance
    ADR-0017 (remplacer-evaluateur, cas C) — elle transfère les rotations
    NON TERMINE d'une station, donc elle doit s'exécuter APRÈS la
    génération du lot (les rotations existent) et AVANT son ouverture
    (aucune n'est encore EN_COURS).

    substitutions: liste de (station_id, nouvel_evaluateur_id, motif).
    """
    must(base, "POST", f"/lots/{lot_id}/presence-et-demarrer", resp_token,
         body={"absents": []}, ok=(200,), what=f"présence + démarrage lot {lot_id}")

    for station_id, nouvel_eval_id, motif in (substitutions or []):
        must(base, "POST", f"/lots/{lot_id}/stations/{station_id}/remplacer-evaluateur",
             resp_token, {"nouvelEvaluateurId": nouvel_eval_id, "motif": motif},
             ok=(200,), what=f"suppléance station {station_id} (ADR-0017)")

    if not est_premier_lot:
        must(base, "POST", f"/lots/{lot_id}/ouvrir", resp_token, ok=(200,),
             what=f"ouvrir lot {lot_id}")

# ─────────────────────────────────────────────────────────────────────────
# Examen de RÉFÉRENCE — porte les 3 défauts plantés
# ─────────────────────────────────────────────────────────────────────────

NOM_EXAMEN_REFERENCE = "IA-F1 — Cohorte de référence (défauts plantés)"
NOM_STATION_DEFAUTS = "Station Défauts"
NOM_STATION_SEVERITE = "Station Sévérité"
NOM_STATION_TEMOIN = "Station Témoin"
LIB_ITEM_IMPOSSIBLE = "Critère impossible"
LIB_ITEM_ALEATOIRE = "Critère sans lien"


def construire_examen_reference(base, resp_token, run_tag, run_id, rng,
                                 eval_a_id, eval_a_token, eval_b_id, eval_b_token,
                                 eval_c_id, eval_c_token, eval_d_id, eval_d_token):
    print(f"\n== Examen de référence ({NOM_EXAMEN_REFERENCE}) ==")

    examen = must(
        base, "POST", "/examens", resp_token,
        {
            "nom": f"{NOM_EXAMEN_REFERENCE} — {run_tag}-{run_id}",
            "matiereId": 1,
            "dateExamen": "2026-06-20",
            "heureDebut": "09:00",
            "dureeStationMin": 12,
            "nbEtudiantsParStation": 6,
        },
        ok=(201,), what="créer examen de référence",
    )
    examen_id = examen["id"]
    print(f"  examen id={examen_id}")

    try:
        # Station Défauts : 4 items, dont les défauts 1 et 2.
        station_defauts = creer_station_avec_grille(
            base, resp_token, examen_id, NOM_STATION_DEFAUTS, [eval_c_id], 20.0,
            [
                ("Geste conforme", "BINAIRE", 5.0, None),
                (LIB_ITEM_IMPOSSIBLE, "BINAIRE", 5.0, None),
                (LIB_ITEM_ALEATOIRE, "BINAIRE", 5.0, None),
                ("Précision du geste", "NUMERIQUE", 5.0, 5.0),
            ],
        )

        # Station Sévérité : 2 items numériques, notée par eval_a (lot1) puis
        # eval_b (lot2) — défaut 3.
        station_severite = creer_station_avec_grille(
            base, resp_token, examen_id, NOM_STATION_SEVERITE, [eval_a_id], 20.0,
            [
                ("Rigueur du protocole", "NUMERIQUE", 10.0, 10.0),
                ("Interprétation du résultat", "NUMERIQUE", 10.0, 10.0),
            ],
        )

        # Station Témoin : 4 items normaux, aucun défaut — sert de repère sain.
        station_temoin = creer_station_avec_grille(
            base, resp_token, examen_id, NOM_STATION_TEMOIN, [eval_d_id], 20.0,
            [
                ("Étape 1 conforme", "BINAIRE", 5.0, None),
                ("Étape 2 conforme", "BINAIRE", 5.0, None),
                ("Qualité globale", "NUMERIQUE", 5.0, 5.0),
                ("Sécurité", "NUMERIQUE", 5.0, 5.0),
            ],
        )

        # 3 stations × 6 étudiants/station = 18 (taille d'un lot) ; on inscrit
        # exactement 2 lots pour que la Station Sévérité soit notée par eval_a
        # sur le lot 1, puis eval_b sur le lot 2.
        lot_size = 18
        etudiants = creer_etudiants_et_inscrire(
            base, resp_token, examen_id, 2 * lot_size, f"REF-{run_tag}-{run_id}", rng, theta_sigma=1.0
        )
        etudiants_par_id = {e.id: e for e in etudiants}

        lots = repartir_lancer_repartir(base, resp_token, examen_id)
        assert len(lots) == 2, f"attendu 2 lots, obtenu {len(lots)} — {lots}"

        rapport = Rapport()

        def note_normale(etu, item, libelle, r):
            if item["type"] == "BINAIRE":
                return valeur_binaire_correlee(etu.theta, r)
            return valeur_numerique_correlee(etu.theta, r, item["valeurMax"])

        def note_defauts(etu, item, libelle, r):
            if libelle == LIB_ITEM_IMPOSSIBLE:
                return valeur_defaut_impossible(r)
            if libelle == LIB_ITEM_ALEATOIRE:
                return valeur_defaut_aleatoire(r)
            return note_normale(etu, item, libelle, r)

        for idx, lot in enumerate(lots):
            print(f"  -- lot {lot['numeroLot']} (taille {lot['taille']}) --")

            subs = ([(station_severite.id, eval_b_id,
                      "F1 — bascule planifiée pour la démonstration de sévérité intra-station")]
                    if idx == 1 else None)
            demarrer_lot_avec_substitution(base, resp_token, lot["lotId"],
                                            est_premier_lot=(idx == 0), substitutions=subs)

            grader_station_lot_courant(base, eval_c_token, eval_c_id, station_defauts,
                                        etudiants_par_id, note_defauts, rapport, rng)
            grader_station_lot_courant(base, eval_d_token, eval_d_id, station_temoin,
                                        etudiants_par_id, note_normale, rapport, rng)

            if idx == 0:
                # Lot 1 : évaluateur A (référence), déjà assigné à la création.
                def note_sev_a(etu, item, libelle, r):
                    return valeur_severite(etu.theta, r, item["valeurMax"], 0.0)
                grader_station_lot_courant(base, eval_a_token, eval_a_id, station_severite,
                                            etudiants_par_id, note_sev_a, rapport, rng,
                                            label_severite="evaluateur_A")
            else:
                # Lot 2 : évaluateur B, décalé de +DECALAGE_SEVERITE / critère.
                def note_sev_b(etu, item, libelle, r):
                    return valeur_severite(etu.theta, r, item["valeurMax"], DECALAGE_SEVERITE)
                grader_station_lot_courant(base, eval_b_token, eval_b_id, station_severite,
                                            etudiants_par_id, note_sev_b, rapport, rng,
                                            label_severite="evaluateur_B")
    finally:
        # Filet de sécurité : même si le script plante en cours de route, l'examen
        # ne doit jamais rester EN_COURS et bloquer les runs suivants (#265).
        st, r = call(base, "PATCH", f"/examens/{examen_id}/statut?statut=TERMINE", resp_token)
        if st == 200:
            print(f"  examen {examen_id} clôturé (TERMINE)")
        else:
            print(f"  ⚠️  Impossible de clôturer l'examen {examen_id} automatiquement "
                  f"(HTTP {st}) — à clôturer à la main.")

    return examen_id, rapport, {
        "station_defauts_id": station_defauts.id,
        "station_severite_id": station_severite.id,
        "station_temoin_id": station_temoin.id,
        "item_impossible_id": station_defauts.items_par_libelle[LIB_ITEM_IMPOSSIBLE]["id"],
        "item_aleatoire_id": station_defauts.items_par_libelle[LIB_ITEM_ALEATOIRE]["id"],
    }


# ─────────────────────────────────────────────────────────────────────────
# Examens de CONTRÔLE — distributions saines, aucun défaut. Un seul lot
# chacun ; volume supplémentaire pour le BI / les tendances.
# ─────────────────────────────────────────────────────────────────────────


def construire_examen_controle(base, resp_token, run_tag, run_id, indice, rng,
                               eval_a_id, eval_a_token,
                               eval_b_id, eval_b_token,
                               eval_c_id, eval_c_token):
    nom = f"IA-F1 — Cohorte contrôle {indice} — {run_tag}-{run_id}"
    print(f"\n== Examen de contrôle {indice} ({nom}) ==")

    examen = must(
        base, "POST", "/examens", resp_token,
        {
            "nom": nom, "matiereId": 1, "dateExamen": "2026-06-21",
            "heureDebut": "09:00", "dureeStationMin": 12, "nbEtudiantsParStation": 6,
        },
        ok=(201,), what="créer examen de contrôle",
    )
    examen_id = examen["id"]

    try:
        # Configuration des stations avec des évaluateurs uniques
        # On crée une liste de tuples (Lettre, ID_Eval, Token_Eval)
        config_stations = [
            ("A", eval_a_id, eval_a_token),
            ("B", eval_b_id, eval_b_token),
            ("C", eval_c_id, eval_c_token)
        ]

        stations_creees = []
        for lettre, eid, etoken in config_stations:
            s = creer_station_avec_grille(
                base, resp_token, examen_id, f"Station {lettre}", [eid], 20.0,
                [
                    ("Étape 1 conforme", "BINAIRE", 5.0, None),
                    ("Étape 2 conforme", "BINAIRE", 5.0, None),
                    ("Qualité globale", "NUMERIQUE", 5.0, 5.0),
                    ("Sécurité", "NUMERIQUE", 5.0, 5.0),
                ],
            )
            stations_creees.append((s, eid, etoken))

        etudiants = creer_etudiants_et_inscrire(
            base, resp_token, examen_id, 18, f"CTRL{indice}-{run_tag}-{run_id}", rng, theta_sigma=1.0
        )
        etudiants_par_id = {e.id: e for e in etudiants}

        lots = repartir_lancer_repartir(base, resp_token, examen_id)
        assert len(lots) == 1, f"attendu 1 lot, obtenu {len(lots)} — {lots}"
        demarrer_lot(base, resp_token, lots[0]["lotId"], est_premier_lot=True)

        def note_normale(etu, item, libelle, r):
            if item["type"] == "BINAIRE":
                return valeur_binaire_correlee(etu.theta, r)
            return valeur_numerique_correlee(etu.theta, r, item["valeurMax"])

        rapport = Rapport()
        # On note chaque station avec son évaluateur respectif
        for s_info, eid, etoken in stations_creees:
            grader_station_lot_courant(base, etoken, eid, s_info,
                                        etudiants_par_id, note_normale, rapport, rng)
    finally:
        # Filet de sécurité : même si le script plante en cours de route, l'examen
        # ne doit jamais rester EN_COURS et bloquer les runs suivants (#265).
        st, r = call(base, "PATCH", f"/examens/{examen_id}/statut?statut=TERMINE", resp_token)
        if st == 200:
            print(f"  examen {examen_id} clôturé (TERMINE)")
        else:
            print(f"  ⚠️  Impossible de clôturer l'examen {examen_id} automatiquement "
                  f"(HTTP {st}) — à clôturer à la main.")

    return examen_id


# ─────────────────────────────────────────────────────────────────────────
# Statistiques simples (stdlib) pour l'auto-vérification en fin de run
# ─────────────────────────────────────────────────────────────────────────


def moyenne(xs):
    return sum(xs) / len(xs) if xs else float("nan")


def pearson(xs, ys):
    n = len(xs)
    if n < 2:
        return float("nan")
    mx, my = moyenne(xs), moyenne(ys)
    sx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    sy = math.sqrt(sum((y - my) ** 2 for y in ys))
    if sx == 0 or sy == 0:
        return float("nan")
    cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    return cov / (sx * sy)


def imprimer_bilan(rapport: Rapport, ids: dict):
    print("\n== Bilan auto-calculé (à partir des valeurs réellement envoyées) ==")

    tv_impossible = rapport.theta_valeur_par_item.get(LIB_ITEM_IMPOSSIBLE, [])
    if tv_impossible:
        v = [x for _, x in tv_impossible]
        print(f"  Défaut 1 — « {LIB_ITEM_IMPOSSIBLE} » : n={len(v)}, "
              f"p observée = {moyenne(v):.3f} (visé ≈ {P_CIBLE_IMPOSSIBLE})")

    tv_aleatoire = rapport.theta_valeur_par_item.get(LIB_ITEM_ALEATOIRE, [])
    if tv_aleatoire:
        thetas = [t for t, _ in tv_aleatoire]
        v = [x for _, x in tv_aleatoire]
        r_aleatoire = pearson(thetas, v)
        print(f"  Défaut 2 — « {LIB_ITEM_ALEATOIRE} » : n={len(v)}, "
              f"corrélation(habileté, note) observée = {r_aleatoire:.3f} (visé ≈ 0)")

    tv_temoin = rapport.theta_valeur_par_item.get("Précision du geste", [])
    if tv_temoin:
        thetas = [t for t, _ in tv_temoin]
        v = [x for _, x in tv_temoin]
        print(f"  Contraste (item normal « Précision du geste ») : "
              f"corrélation(habileté, note) observée = {pearson(thetas, v):.3f} "
              f"(doit être NETTEMENT non nulle — sinon le générateur lui-même est cassé)")

    vA = rapport.valeurs_severite_par_evaluateur.get("evaluateur_A", [])
    vB = rapport.valeurs_severite_par_evaluateur.get("evaluateur_B", [])
    if vA and vB:
        print(f"  Défaut 3 — Station Sévérité : moyenne évaluateur A = {moyenne(vA):.2f}, "
              f"moyenne évaluateur B = {moyenne(vB):.2f}, "
              f"écart observé = {moyenne(vB) - moyenne(vA):+.2f} (visé ≈ +{DECALAGE_SEVERITE:.0f}/critère)")
    print(f"\n  Identifiants clés (examen de référence) : {json.dumps(ids, indent=2)}")


# ─────────────────────────────────────────────────────────────────────────
# main
# ─────────────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080/api/v1")
    parser.add_argument("--n-control", type=int, default=2, help="nombre d'examens de contrôle sans défaut")
    parser.add_argument("--seed", type=int, default=20260821, help="graine RNG — fixe par défaut pour la reproductibilité")
    args = parser.parse_args()

    base = args.base_url
    rng = random.Random(args.seed)
    run_tag = f"seed{args.seed}"
    # #354 — run_id pilote UNIQUEMENT l'unicité (matricules #351), jamais les
    # tirages statistiques. --seed reste fixe par défaut pour que theta/notes
    # soient reproductibles (contrat README) ; sans un identifiant DÉCOUPLÉ,
    # deux runs à seed identique — le cas par défaut — retombent sur le même
    # numero_inscription dès que la base a déjà servi une fois. secrets.token_hex
    # est volontairement NON dérivé de `rng` : le coupler à `rng` recréerait
    # exactement le problème qu'on découple.
    run_id = secrets.token_hex(4)
    print(f"Génération de cohorte synthétique IA/BI — base={base} seed={args.seed} run_id={run_id}")

    admin_token = login(base, "admin@epos.tn", "Admin@1234")
    resp_token = login(base, "resp@epos.tn", "Resp@1234")

    eval_a_id = ensure_evaluateur(base, admin_token, "ia.f1.eval.a@epos.tn", "Sévérité-A", "Prof")
    eval_b_id = ensure_evaluateur(base, admin_token, "ia.f1.eval.b@epos.tn", "Sévérité-B", "Prof")
    eval_c_id = ensure_evaluateur(base, admin_token, "ia.f1.eval.c@epos.tn", "Témoin", "Prof")
    eval_d_id = ensure_evaluateur(base, admin_token, "ia.f1.eval.d@epos.tn", "Témoin-2", "Prof")
    eval_a_token = login(base, "ia.f1.eval.a@epos.tn", "Eval@1234")
    eval_b_token = login(base, "ia.f1.eval.b@epos.tn", "Eval@1234")
    eval_c_token = login(base, "ia.f1.eval.c@epos.tn", "Eval@1234")
    eval_d_token = login(base, "ia.f1.eval.d@epos.tn", "Eval@1234")
    assert user_id_from_token(eval_a_token) == eval_a_id
    assert user_id_from_token(eval_b_token) == eval_b_id
    assert user_id_from_token(eval_c_token) == eval_c_id
    assert user_id_from_token(eval_d_token) == eval_d_id

    examen_ref_id, rapport, ids = construire_examen_reference(
        base, resp_token, run_tag, run_id, rng,
        eval_a_id, eval_a_token, eval_b_id, eval_b_token, eval_c_id, eval_c_token, eval_d_id, eval_d_token
    )
    ids["examen_reference_id"] = examen_ref_id

    controle_ids = []
    for i in range(1, args.n_control + 1):
        controle_ids.append(
            construire_examen_controle(base, resp_token, run_tag, run_id, i, rng, eval_a_id, eval_a_token, eval_b_id, eval_b_token, eval_c_id, eval_c_token)
        )
    ids["examens_controle_ids"] = controle_ids

    imprimer_bilan(rapport, ids)
    print("\nTerminé.")


if __name__ == "__main__":
    main()