"""Lecture de `X-User-Authorities` — le contrat posé par le gateway (#13).

Le gateway valide le JWT et propage les autorités vérifiées dans cet en-tête
(format: `ROLE_SUPER_ADMIN` global, `ROLE_RESPONSABLE_MATIERE:5` scopé —
voir ADR-0005). ai-service ne re-valide PAS le jeton : il n'est joignable
qu'à travers le gateway (aucun port hôte, ADR-0029 D4), comme les services
Java qui font confiance au même en-tête.
"""

from dataclasses import dataclass

ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN"
_RESPONSABLE_PREFIX = "ROLE_RESPONSABLE_MATIERE:"


@dataclass(frozen=True)
class Authorities:
    raw: tuple[str, ...]

    @property
    def is_super_admin(self) -> bool:
        return ROLE_SUPER_ADMIN in self.raw

    @property
    def matiere_ids(self) -> frozenset[int]:
        """Les matières où l'appelant est RESPONSABLE_MATIERE (portées ADR-0005)."""
        ids = set()
        for authority in self.raw:
            if authority.startswith(_RESPONSABLE_PREFIX):
                suffix = authority[len(_RESPONSABLE_PREFIX):]
                # Un suffixe non numérique est un en-tête malformé : on l'ignore
                # plutôt que de planter — l'appelant n'y gagne aucun droit.
                if suffix.isdigit():
                    ids.add(int(suffix))
        return frozenset(ids)

    @property
    def is_empty(self) -> bool:
        return not self.raw


def parse(header: str | None) -> Authorities:
    parts = tuple(p.strip() for p in (header or "").split(",") if p.strip())
    return Authorities(parts)
