"""ai-service — étage B du module IA/BI (#352 socle, #359 endpoints réels).

Le socle (#352) posait le contrat — santé, identité propagée par le gateway,
garde de périmètre matière. #359 le remplit : `/indices` et `/evaluateurs`
servent le moteur N5 à travers le cache ai_db (ADR-0029 D3 — « jamais de
re-calcul par requête sur le chemin chaud »), derrière la garde examen-clos
(D2 : « le calcul ne porte que sur des examens CLOS »).

Ordre des refus, délibéré : 401 (identité) → 404 (existence) → 403 (périmètre,
nominatif) → 409 (non clos — APRÈS le périmètre : on ne révèle pas le statut
d'un examen hors périmètre). La garde clos lit un état PERSISTÉ écrit par un
acte humain (v_ai_examens ← Terminer) — aucune horloge, nulle part (ADR-0014).

Panne du plan de données (lecture OU cache) : 503 BRUYANT, jamais un repli qui
fabrique une réponse (D7, leçon du 403 avalé). Le client (écran A) se dégrade,
lui — c'est SA moitié du contrat (ADR-0021 D4).

BI (#365 / N10) : ``/matieres/{id}/tendances`` (le responsable, SA matière —
sessions closes dans le temps, par station) et ``/faculte/synthese``
(SUPER_ADMIN, agrégé d'abord, jamais par étudiant — ADR-0021 D5). Mêmes
agrégats, mêmes droits, aucune arithmétique nouvelle (``app.bi``).

Enveloppe de réponse : {success, data, message} — ADR-0004, comme les
services Java.
"""

from contextlib import asynccontextmanager

from fastapi import Body, FastAPI, Header, HTTPException, Path
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import authorities as authz
from . import bi, cache, db, journal
from .bareme import projection as pj
from .bareme import propositions as props
from .guard import check_examen_access, check_faculte_access, check_matiere_access
from .stats import hash as stats_hash
from .stats import loader, runner


@asynccontextmanager
async def _lifespan(_app: FastAPI):
    """DDL du cache ET du journal au démarrage, en MEILLEUR EFFORT : ai_db
    indisponible ne doit pas empêcher le service de démarrer (le healthcheck
    ne sonde pas la DB — deux questions distinctes). Les chemins lire/écrire
    re-tentent au premier besoin ; ici on évite juste que la toute première
    requête paie le DDL."""
    try:
        with cache._connexion() as conn:
            cache._assurer_schema(conn)
        with journal._connexion() as conn:
            journal._assurer_schema(conn)
    except Exception:  # noqa: BLE001 — voulu : démarrage jamais bloqué par ai_db
        pass
    yield


app = FastAPI(
    title="EPOS ai-service",
    docs_url=None, redoc_url=None, openapi_url=None,
    lifespan=_lifespan,
)


def _envelope(*, success: bool, data=None, message: str | None = None) -> dict:
    return {"success": success, "data": data, "message": message}


@app.exception_handler(HTTPException)
async def http_exception_envelope(_request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content=_envelope(success=False, message=str(exc.detail)),
    )


@app.exception_handler(RequestValidationError)
async def validation_envelope(_request, exc: RequestValidationError):
    """Corps ou chemin mal formé → 400 enveloppé (le 422 par défaut de FastAPI
    n'existe pas dans le contrat ADR-0004 ; les services Java rendent 400)."""
    return JSONResponse(
        status_code=400,
        content=_envelope(success=False, message=f"Requête invalide : {exc.errors()[0].get('msg', 'corps mal formé')}"),
    )


@app.get("/ai/health")
def health() -> dict:
    """Sonde de santé (healthcheck compose + supervision). Pas de DB ici :
    la santé du processus et celle du plan de données sont deux questions."""
    return _envelope(success=True, data={"status": "UP"})


def _verifier_acces_et_cloture(
    examen_id: int,
    x_user_authorities: str | None,
    *,
    sujet: str = "Les indices ne se calculent",
    x_user_id: str | None = None,
) -> authz.Authorities:
    """Le prologue commun des endpoints d'analyse : périmètre PUIS clôture.

    Lève 401/403/404 (guard), 409 (examen non clos), et laisse remonter toute
    erreur DB à l'appelant (qui la convertit en 503). ``sujet`` nomme ce que la
    route fait dans le message 409 (indices / propositions / projection).
    """
    auth = authz.parse(x_user_authorities, x_user_id)
    check_examen_access(auth, examen_id, db.resolve_matiere)

    statut = db.statut_examen(examen_id)
    if statut is None:
        # L'examen a un snapshot scoring (le guard l'a résolu) mais n'existe
        # plus côté exam_db — divergence rare, dite comme telle.
        raise HTTPException(status_code=404, detail=f"Examen introuvable : {examen_id}")
    if statut not in db.STATUTS_CLOS:
        raise HTTPException(
            status_code=409,
            detail=(
                f"{sujet} que sur un examen clos — "
                f"statut actuel : {statut}."
            ),
        )
    return auth


def _payload_examen(examen_id: int) -> dict:
    """Le calcul complet (indices + évaluateurs) à travers le cache ai_db.

    Un seul chargement, une seule empreinte, une seule ligne de cache portant
    les DEUX vues du même état des entrées. L'empreinte inclut le total V23 et
    MOTEUR_VERSION (voir app.stats.hash) : un réajustement, une notation
    verrouillée de plus ou une évolution du moteur forcent le recalcul —
    jamais l'horloge.
    """
    donnees = loader.charger_examen(examen_id)
    total_verrouillees = db.nb_notations_verrouillees(examen_id)
    empreinte = stats_hash.empreinte(donnees, total_verrouillees)

    cache_hit = cache.lire(examen_id, empreinte)
    if cache_hit is not None:
        return cache_hit

    sans_aucun_item = max(
        0, total_verrouillees - donnees.exclusions.notations_analysees
    )
    payload = {
        "entrees_hash": empreinte,
        "moteur_version": stats_hash.MOTEUR_VERSION,
        "indices": runner.calculer_indices(examen_id, donnees),
        "evaluateurs": runner.calculer_evaluateurs(examen_id, donnees),
    }
    # L'angle mort structurel de la vue (notation verrouillée sans AUCUN item,
    # jointure interne) — compté depuis le total V23 et DIT dans les deux vues.
    for vue in ("indices", "evaluateurs"):
        payload[vue]["exclusions"]["sans_aucun_item"] = sans_aucun_item
    cache.ecrire(examen_id, empreinte, stats_hash.MOTEUR_VERSION, payload)
    return payload


def _servir(examen_id: int, x_user_authorities: str | None, vue: str) -> dict:
    try:
        _verifier_acces_et_cloture(examen_id, x_user_authorities)
        payload = _payload_examen(examen_id)
    except HTTPException:
        raise
    except Exception:
        # Plan de données injoignable (lecture ai_reader OU cache ai_writer) :
        # échec BRUYANT (503), pas de repli qui fabriquerait une réponse
        # (ADR-0029 D7).
        raise HTTPException(
            status_code=503,
            detail="L'analyse est momentanément indisponible — réessayez.",
        )
    return _envelope(
        success=True,
        data={
            **payload[vue],
            "entrees_hash": payload["entrees_hash"],
            "moteur_version": payload["moteur_version"],
        },
    )


@app.get("/ai/examens/{examen_id}/indices")
def indices(
    examen_id: int = Path(ge=1),
    x_user_authorities: str | None = Header(default=None),
):
    """Difficulté, discrimination, α, concentration — chaque valeur avec son
    statut (contrat de refus) et son IC. Servi depuis le cache ai_db."""
    return _servir(examen_id, x_user_authorities, "indices")


@app.get("/ai/examens/{examen_id}/evaluateurs")
def evaluateurs(
    examen_id: int = Path(ge=1),
    x_user_authorities: str | None = Header(default=None),
):
    """Sévérité par évaluateur, INTRA-STATION uniquement (ADR-0021 D2) — des
    ids et des écarts avec IC, jamais un palmarès. `saisi_par` NULL exclu et
    compté. Servi depuis le même cache que /indices."""
    return _servir(examen_id, x_user_authorities, "evaluateurs")


# ── Étage C : propositions (#362 / N8) ───────────────────────────────────────

_INDISPONIBLE = "L'analyse est momentanément indisponible — réessayez."

DECISIONS = frozenset({"ACCEPTER", "REFUSER"})


def _contexte_bareme(examen_id: int):
    """Ce que la projection demande en plus des indices : les données (le
    delta lit score_final et les valeurs), le barème courant et le snapshot de
    grille (vues V26)."""
    donnees = loader.charger_examen(examen_id)
    courant = props.bareme_depuis_lignes(db.bareme_courant(examen_id))
    grilles = props.grilles_depuis_lignes(db.grilles_snapshot(examen_id))
    return donnees, courant, grilles


def _construire_propositions(examen_id: int) -> dict:
    payload = _payload_examen(examen_id)          # indices, via le cache
    donnees, courant, grilles = _contexte_bareme(examen_id)
    decisions = journal.lire_examen(examen_id)
    construit = props.construire(
        examen_id=examen_id,
        entrees_hash=payload["entrees_hash"],
        moteur_version=payload["moteur_version"],
        donnees=donnees,
        indices=payload["indices"],
        courant=courant,
        grilles=grilles,
        decisions=decisions,
    )
    # ÉCRITURE STRICTE (ADR-0015) : une proposition qui ne peut pas être
    # tracée n'est pas servie — l'échec remonte (→ 503), pas de repli.
    journal.enregistrer(props.lignes_journal(construit))
    return construit


@app.get("/ai/examens/{examen_id}/propositions")
def propositions(
    examen_id: int = Path(ge=1),
    x_user_authorities: str | None = Header(default=None),
):
    """Les opérations D8 applicables (rang de défendabilité, déclencheur
    chiffré, effet projeté AVANT décision — ADR-0021 D10) + ce que scoring
    refuserait, DIT. Chaque proposition est journalisée dans ai_db avant
    d'être servie. Aucune écriture vers scoring, jamais (ADR-0030 D1)."""
    try:
        _verifier_acces_et_cloture(
            examen_id, x_user_authorities, sujet="Les propositions ne se calculent"
        )
        construit = _construire_propositions(examen_id)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail=_INDISPONIBLE)
    return _envelope(success=True, data=construit)


@app.post("/ai/examens/{examen_id}/propositions/{proposition_id}/decision")
def decider(
    examen_id: int = Path(ge=1),
    proposition_id: str = Path(min_length=8, max_length=64, pattern=r"^[0-9a-f]+$"),
    corps: dict = Body(...),
    x_user_authorities: str | None = Header(default=None),
    x_user_id: str | None = Header(default=None),
):
    """L'acte du responsable sur une proposition — ACCEPTER ou REFUSER, avec
    motif, journalisé UNE fois (le refus aussi, ADR-0030 D1). Le barème, lui,
    est écrit par le client web dans scoring (porte N7) ; ici on trace la
    décision et la version rapportée. Première route d'écriture d'ai-service —
    toujours ai_db seulement.

    Refus : 401 (identité ou X-User-Id absent) → 404 → 403 → 409 (non clos) →
    400 (corps) → 404 (proposition inconnue ou d'un autre examen) → 409
    (données changées depuis la proposition, ou déjà décidée).
    """
    decision = str(corps.get("decision") or "").strip().upper()
    motif = str(corps.get("motif") or "").strip()
    version_resultat = corps.get("bareme_version_resultat")
    try:
        auth = _verifier_acces_et_cloture(
            examen_id, x_user_authorities,
            sujet="Les décisions ne se prennent", x_user_id=x_user_id,
        )
        if auth.user_id is None:
            raise HTTPException(
                status_code=401,
                detail="Aucun identifiant d'utilisateur propagé par le gateway (X-User-Id) — "
                       "une décision porte son auteur.",
            )
        if decision not in DECISIONS:
            raise HTTPException(status_code=400,
                                detail="decision doit valoir ACCEPTER ou REFUSER.")
        if not motif:
            raise HTTPException(status_code=400,
                                detail="motif est obligatoire (justification de la décision).")
        if version_resultat is not None and not isinstance(version_resultat, int):
            raise HTTPException(status_code=400,
                                detail="bareme_version_resultat doit être un entier ou null.")

        ligne = journal.lire(proposition_id)
        if ligne is None or ligne["examen_id"] != examen_id:
            raise HTTPException(status_code=404,
                                detail=f"Proposition inconnue pour l'examen {examen_id} : {proposition_id}")
        payload = _payload_examen(examen_id)
        if ligne["entrees_hash"] != payload["entrees_hash"]:
            raise HTTPException(
                status_code=409,
                detail="Proposition périmée — les données de l'examen ont changé depuis "
                       "qu'elle a été calculée ; recharger les propositions.",
            )
        if ligne["decision"] is not None:
            raise HTTPException(
                status_code=409,
                detail=f"Proposition déjà décidée ({ligne['decision']}) — une décision ne s'écrase pas.",
            )
        resultat = journal.decider(proposition_id, decision, motif, auth.user_id, version_resultat)
        if resultat is None:
            # Course : décidée entre notre lecture et notre UPDATE.
            raise HTTPException(status_code=409,
                                detail="Proposition déjà décidée — une décision ne s'écrase pas.")
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail=_INDISPONIBLE)
    return _envelope(success=True, data=resultat,
                     message=f"Décision {decision} journalisée pour la proposition {proposition_id}")


@app.post("/ai/examens/{examen_id}/projection")
def projection(
    examen_id: int = Path(ge=1),
    corps: dict = Body(...),
    x_user_authorities: str | None = Header(default=None),
):
    """L'effet projeté d'une liste d'opérations COMPOSÉE par le responsable
    (la forme du fil scoring — le même corps qu'il POSTera), calculé par la
    même arithmétique que scoring appliquera : la prévisualisation D10 d'une
    repondération ou de toute combinaison manuelle. Pure lecture : rien n'est
    journalisé, rien n'est mis en cache, rien n'est écrit. 400 nominatif sur
    ce que scoring refuserait à la création."""
    try:
        _verifier_acces_et_cloture(examen_id, x_user_authorities, sujet="La projection ne se calcule")
        brut = corps.get("operations")
        if not isinstance(brut, list):
            raise HTTPException(status_code=400, detail="operations est obligatoire (liste, vide = retour à l'origine).")
        operations = [pj.Operation.from_wire(o) for o in brut if isinstance(o, dict)]
        if len(operations) != len(brut):
            raise HTTPException(status_code=400, detail="Chaque opération est un objet {type, cibleItemId, cibleStationId, nouvelleEchelle}.")
        donnees, courant, grilles = _contexte_bareme(examen_id)
        refus = pj.valider(operations, donnees.criteres, props.items_snapshotes(donnees), grilles, courant)
        if refus is not None:
            raise HTTPException(status_code=400, detail=f"Modification refusée ({refus.code}) : {refus.detail}.")
        avant = pj.appliquer(list(courant.operations), donnees.criteres, grilles) if courant else None
        apres = pj.appliquer(operations, donnees.criteres, grilles)
        eff = pj.effet(avant, apres, donnees, grilles)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail=_INDISPONIBLE)
    return _envelope(success=True, data={
        "examen_id": examen_id,
        "bareme_courant": (
            {"version": courant.version, "operations": [o.as_wire() for o in courant.operations]}
            if courant else None
        ),
        "operations": [o.as_wire() for o in operations],
        "couverture_snapshot_complete": eff is not None,
        "max_delibere_par_station": {str(k): v for k, v in sorted(apres.max_delibere_par_station.items())},
        "max_original_par_station": {str(k): v for k, v in sorted(apres.max_original_par_station.items())},
        "effet_projete": eff,
    })


# ── BI : la face transversale (#365 / N10) ───────────────────────────────────

@app.get("/ai/matieres/{matiere_id}/tendances")
def tendances(
    matiere_id: int = Path(ge=1),
    x_user_authorities: str | None = Header(default=None),
):
    """Les sessions CLOSES d'une matière dans l'ordre des dates — distribution,
    taux de réussite, échec par station, barème délibéré éventuel. Périmètre :
    le responsable de la matière ou le SUPER_ADMIN ; l'évaluateur n'a pas
    d'accès v1. 401 → 403 nominatif → 200 (vide = lecture AUCUN_EXAMEN_CLOS,
    jamais 404 : le catalogue des matières vit dans auth_db)."""
    try:
        check_matiere_access(authz.parse(x_user_authorities), matiere_id)
        data = bi.tendances_matiere(matiere_id)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail=_INDISPONIBLE)
    return _envelope(success=True, data=data)


@app.get("/ai/faculte/synthese")
def synthese(x_user_authorities: str | None = Header(default=None)):
    """Agrégats inter-matières pour le SUPER_ADMIN — AGRÉGÉ D'ABORD (ADR-0021
    D5) : aucun identifiant d'étudiant, de notation ni d'évaluateur ne sort ;
    sous l'effectif minimal, un refus nommé plutôt qu'un nombre. 401 → 403."""
    try:
        check_faculte_access(authz.parse(x_user_authorities))
        data = bi.synthese_faculte()
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail=_INDISPONIBLE)
    return _envelope(success=True, data=data)
