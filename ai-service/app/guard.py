"""Garde de périmètre matière — les mêmes règles que scoring (#274, ADR-0029 D4).

- SUPER_ADMIN : lecture partout (ADR-0018 D5 — l'analytics est une lecture ;
  la contrainte « agrégé d'abord » de ADR-0021 D5 s'applique aux endpoints
  inter-matières, pas au refus d'accès).
- RESPONSABLE_MATIERE : uniquement les examens de SES matières.
- EVALUATEUR (ou aucune portée) : aucun accès v1 (plan IA/BI, T3).

La résolution examen → matière vient du snapshot de scoring
(`exam_matiere_snapshot`, V17/#274) via la vue `v_ai_exam_matiere` — la même
vérité que celle que scoring applique à ses propres écritures.
"""

from collections.abc import Callable

from fastapi import HTTPException

from .authorities import Authorities


def check_examen_access(
    auth: Authorities,
    examen_id: int,
    resolve_matiere: Callable[[int], int | None],
) -> None:
    """Lève 401/403/404 ; ne rend rien si l'accès est permis.

    L'ordre des refus est délibéré : l'identité d'abord (401), l'existence
    ensuite (404), le périmètre en dernier (403 nominatif).
    """
    if auth.is_empty:
        raise HTTPException(status_code=401, detail="Aucune identité propagée par le gateway.")

    if auth.is_super_admin:
        return

    matiere_id = resolve_matiere(examen_id)
    if matiere_id is None:
        raise HTTPException(status_code=404, detail=f"Examen introuvable : {examen_id}")

    if matiere_id not in auth.matiere_ids:
        raise HTTPException(
            status_code=403,
            detail=(
                f"Accès refusé : l'examen {examen_id} relève de la matière "
                f"{matiere_id}, hors de votre périmètre."
            ),
        )
