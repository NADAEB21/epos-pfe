"""L'empreinte des ENTRÉES d'un examen — la clé du cache (#359, ADR-0029 D3).

Deux propriétés portées par cette empreinte :
1. Recalcul automatique quand la donnée bouge : un réajustement (ADR-0013)
   change une valeur → le hash change → le cache rate → on recalcule. Aucune
   invalidation par le temps — pas de TTL, pas d'horloge (ADR-0014 s'applique
   jusqu'ici).
2. Reproductibilité devant le jury : ce qui a été affiché est rejouable —
   mêmes entrées (même hash) ⇒ même résultat, parce que le moteur est
   déterministe (bootstrap à graine fixe, GRAINE=42).

MOTEUR_VERSION fait PARTIE de l'empreinte : changer une graine, un seuil ou
une formule change le résultat SANS changer les données — sans ce sel, le
cache servirait l'ancien calcul avec l'aplomb du neuf. Incrémenter à chaque
évolution du moteur (formules, seuils, gabarits de refus, forme du payload).

Le total V23 (`nb_verrouillees`) est aussi une entrée : une notation
verrouillée sans item est invisible des données chargées — sans lui, son
apparition ne changerait pas l'empreinte et le compteur « sans_aucun_item »
resterait figé.
"""

import hashlib
import json

from app.stats.loader import DonneesExamen

# ⚠️ À incrémenter à CHAQUE évolution du moteur (formules, seuils, graine,
# gabarits, forme du payload). Voir docstring.
MOTEUR_VERSION = "n5-2026-08-24-1"


def _canonique(d: DonneesExamen, nb_verrouillees_total: int) -> str:
    """Sérialisation canonique et stable des entrées (tri explicite partout)."""
    criteres = [
        [
            c.item_id, c.libelle, c.type, c.ponderation, c.valeur_max,
            c.grille_id, c.station_id, c.note_max,
        ]
        for c in sorted(d.criteres.values(), key=lambda c: c.item_id)
    ]
    notations = [
        [
            n.notation_id, n.station_id, n.grille_id, n.etudiant_id,
            n.saisi_par, n.score_final,
            sorted(n.valeurs.items()),
        ]
        for n in sorted(d.notations, key=lambda n: n.notation_id)
    ]
    return json.dumps(
        {
            "moteur": MOTEUR_VERSION,
            "nb_verrouillees_total": nb_verrouillees_total,
            "criteres": criteres,
            "notations": notations,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )


def empreinte(d: DonneesExamen, nb_verrouillees_total: int) -> str:
    """sha256 hex de la sérialisation canonique des entrées + version moteur."""
    return hashlib.sha256(
        _canonique(d, nb_verrouillees_total).encode("utf-8")
    ).hexdigest()
