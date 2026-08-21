"""La garde de périmètre — l'ordre des refus et le cas nominal, sans DB."""

import pytest
from fastapi import HTTPException

from app.authorities import parse
from app.guard import check_examen_access


def _resolver(mapping):
    return lambda examen_id: mapping.get(examen_id)


def test_sans_identite_401_avant_toute_resolution():
    appels = []

    def resolver(examen_id):
        appels.append(examen_id)
        return 1

    with pytest.raises(HTTPException) as exc:
        check_examen_access(parse(None), 77, resolver)
    assert exc.value.status_code == 401
    assert appels == []  # aucune requête DB pour un anonyme


def test_super_admin_passe_sans_resolution():
    appels = []

    def resolver(examen_id):
        appels.append(examen_id)
        return 1

    check_examen_access(parse("ROLE_SUPER_ADMIN"), 77, resolver)
    assert appels == []  # lecture partout (ADR-0018 D5), pas de requête inutile


def test_responsable_dans_sa_matiere_passe():
    check_examen_access(parse("ROLE_RESPONSABLE_MATIERE:1"), 77, _resolver({77: 1}))


def test_responsable_hors_matiere_403_nominatif():
    with pytest.raises(HTTPException) as exc:
        check_examen_access(parse("ROLE_RESPONSABLE_MATIERE:1"), 63, _resolver({63: 4}))
    assert exc.value.status_code == 403
    assert "matière 4" in exc.value.detail
    assert "63" in exc.value.detail


def test_evaluateur_403_meme_sur_sa_station():
    # Pas d'accès évaluateur en v1 (plan T3) — le rôle global ne donne aucune portée.
    with pytest.raises(HTTPException) as exc:
        check_examen_access(parse("ROLE_EVALUATEUR"), 77, _resolver({77: 1}))
    assert exc.value.status_code == 403


def test_examen_inconnu_404():
    with pytest.raises(HTTPException) as exc:
        check_examen_access(parse("ROLE_RESPONSABLE_MATIERE:1"), 999, _resolver({}))
    assert exc.value.status_code == 404
