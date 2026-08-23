"""ai-service — le socle du module IA/BI (#352, ADR-0029).

Ce scaffold ne porte encore AUCUN calcul : il pose le contrat — santé,
identité propagée par le gateway, garde de périmètre matière — sur lequel
les étages B/C (#359, #362) s'appuieront. L'endpoint `/indices` répond 501
avec un message honnête tant que l'étage B n'existe pas : jamais un stub qui
fabrique des données (leçon du 403 avalé).

Enveloppe de réponse : {success, data, message} — ADR-0004, comme les
services Java.
"""

from fastapi import FastAPI, Header, HTTPException, Path
from fastapi.responses import JSONResponse

from . import authorities as authz
from . import db
from .guard import check_examen_access

app = FastAPI(title="EPOS ai-service", docs_url=None, redoc_url=None, openapi_url=None)


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


@app.get("/ai/examens/{examen_id}/indices")
def indices(
    examen_id: int = Path(ge=1),
    x_user_authorities: str | None = Header(default=None),
):
    """Étage B (#359). Le socle ne sert que la GARDE : périmètre prouvé,
    calcul pas encore livré — 501 honnête, jamais des données inventées."""
    auth = authz.parse(x_user_authorities)
    try:
        check_examen_access(auth, examen_id, db.resolve_matiere)
    except HTTPException:
        raise
    except Exception:
        # Plan de données injoignable : échec BRUYANT (503), pas de repli
        # qui fabriquerait une réponse (ADR-0029 D7).
        raise HTTPException(
            status_code=503,
            detail="Plan de données du module IA indisponible — réessayez.",
        )
    raise HTTPException(
        status_code=501,
        detail="Indices non implémentés — l'étage B arrive avec #359. Le périmètre, lui, est déjà gardé.",
    )
