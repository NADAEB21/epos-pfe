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

Enveloppe de réponse : {success, data, message} — ADR-0004, comme les
services Java.
"""

from contextlib import asynccontextmanager

from fastapi import FastAPI, Header, HTTPException, Path
from fastapi.responses import JSONResponse

from . import authorities as authz
from . import cache, db
from .guard import check_examen_access
from .stats import hash as stats_hash
from .stats import loader, runner


@asynccontextmanager
async def _lifespan(_app: FastAPI):
    """DDL du cache au démarrage, en MEILLEUR EFFORT : ai_db indisponible ne
    doit pas empêcher le service de démarrer (le healthcheck ne sonde pas la
    DB — deux questions distinctes). Le chemin lire/écrire re-tente au premier
    besoin ; ici on évite juste que la toute première requête paie le DDL."""
    try:
        with cache._connexion() as conn:
            cache._assurer_schema(conn)
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


@app.get("/ai/health")
def health() -> dict:
    """Sonde de santé (healthcheck compose + supervision). Pas de DB ici :
    la santé du processus et celle du plan de données sont deux questions."""
    return _envelope(success=True, data={"status": "UP"})


def _verifier_acces_et_cloture(examen_id: int, x_user_authorities: str | None) -> None:
    """Le prologue commun des endpoints d'analyse : périmètre PUIS clôture.

    Lève 401/403/404 (guard), 409 (examen non clos), et laisse remonter toute
    erreur DB à l'appelant (qui la convertit en 503).
    """
    auth = authz.parse(x_user_authorities)
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
                "Les indices ne se calculent que sur un examen clos — "
                f"statut actuel : {statut}."
            ),
        )


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
            detail="Plan de données du module IA indisponible — réessayez.",
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
