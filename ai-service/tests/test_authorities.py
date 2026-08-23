"""Le parsing de X-User-Authorities — chaque forme du contrat ADR-0005."""

from app import authorities


def test_en_tete_absent_est_vide():
    auth = authorities.parse(None)
    assert auth.is_empty
    assert not auth.is_super_admin
    assert auth.matiere_ids == frozenset()


def test_en_tete_blanc_est_vide():
    assert authorities.parse("  ").is_empty


def test_super_admin_global():
    auth = authorities.parse("ROLE_SUPER_ADMIN")
    assert auth.is_super_admin
    assert auth.matiere_ids == frozenset()


def test_responsable_une_matiere():
    auth = authorities.parse("ROLE_RESPONSABLE_MATIERE:5")
    assert not auth.is_super_admin
    assert auth.matiere_ids == frozenset({5})


def test_cumul_deux_matieres_et_evaluateur():
    # La forme documentée par UserService:468 — deux portées + un rôle global.
    auth = authorities.parse("ROLE_RESPONSABLE_MATIERE:3, ROLE_RESPONSABLE_MATIERE:7,ROLE_EVALUATEUR")
    assert auth.matiere_ids == frozenset({3, 7})
    assert not auth.is_super_admin


def test_evaluateur_seul_na_aucune_portee():
    auth = authorities.parse("ROLE_EVALUATEUR")
    assert not auth.is_empty
    assert auth.matiere_ids == frozenset()


def test_suffixe_malforme_ne_donne_aucun_droit():
    # Un en-tête cassé ne doit ni planter ni ouvrir une porte.
    auth = authorities.parse("ROLE_RESPONSABLE_MATIERE:abc,ROLE_RESPONSABLE_MATIERE:")
    assert auth.matiere_ids == frozenset()
    assert not auth.is_empty
